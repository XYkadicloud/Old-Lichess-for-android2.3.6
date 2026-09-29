package org.lichessold.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.lichessold.util.Log;

/**
 * 极简 HTTP/1.1 客户端（纯 Java），跑在自带的 TLS 之上。
 *
 * 支持：
 *  - GET / POST
 *  - Content-Length / Transfer-Encoding: chunked / 读到连接关闭 三种 body
 *  - gzip 响应（非流式）
 *  - 301/302/303/307 重定向（最多 3 跳）
 *  - NDJSON 流式逐行读取（长连接，绝不缓冲整个响应）
 *  - **连接复用**：TLS 握手在 ARM11 上要几百毫秒到几秒，每个请求都重连是灾难。
 *    这里保持一条 keep-alive 连接重复使用，最多 20 次或直到服务端要求关闭。
 *  - **自动重试**：Lichess 前置的负载均衡偶尔会回 handshake_failure 或直接断连，
 *    这不是我们的 bug。对网络类错误自动重试 2 次。
 *
 * 不支持也不需要：HTTP/2、cookie、multipart、代理。保持代码量和内存占用最小。
 */
public final class Http {

    public static final String DEFAULT_HOST = "lichess.org";

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_CONN_USES = 20;
    private static final int RETRY_COUNT = 2;

    /** 逐行回调。返回 false 表示主动停止读取。 */
    public interface LineHandler {
        boolean onLine(String line);
    }

    private final String host;
    private final int port;
    private final TrustAnchors anchors;
    private int readTimeout = 20000;
    private int connectTimeout = 20000;
    private String userAgent = "LichessOld/0.3.0 (Android 2.3.6; +https://lichess.org)";

    private TlsConnection conn;
    private int connUses;
    private long lastHandshakeMillis;

    public Http(TrustAnchors anchors) {
        this(DEFAULT_HOST, 443, anchors);
    }

    public Http(String host, int port, TrustAnchors anchors) {
        this.host = host;
        this.port = port;
        this.anchors = anchors;
    }

    public String host() {
        return host;
    }

    public Http setReadTimeout(int millis) {
        this.readTimeout = millis;
        return this;
    }

    public Http setConnectTimeout(int millis) {
        this.connectTimeout = millis;
        return this;
    }

    public Http setUserAgent(String ua) {
        this.userAgent = ua;
        return this;
    }

    /** 最近一次 TLS 握手的耗时，诊断页用来展示设备性能。 */
    public long lastHandshakeMillis() {
        return lastHandshakeMillis;
    }

    /** 关闭底层连接。Activity 退出时调用，避免挂着空闲 socket 耗电。 */
    public synchronized void close() {
        dropConn();
    }

    // ---------------------------------------------------------------- 请求

    public HttpResponse get(String path, Map<String, String> headers) throws NetException {
        return request("GET", path, headers, null);
    }

    public HttpResponse post(String path, Map<String, String> headers, String body)
            throws NetException {
        return request("POST", path, headers, body);
    }

    public HttpResponse request(String method, String path, Map<String, String> headers,
                                String body) throws NetException {
        NetException last = null;
        for (int attempt = 0; attempt <= RETRY_COUNT; attempt++) {
            try {
                return requestFollowing(method, path, headers, body);
            } catch (NetException e) {
                last = e;
                if (!isRetryable(e) || attempt == RETRY_COUNT) {
                    throw e;
                }
                Log.w("HTTP", "请求失败将重试(" + (attempt + 1) + "/" + RETRY_COUNT + "): "
                        + NetException.stageName(e.getStage()) + " " + e.getMessage());
                dropConn();
                sleepQuietly(300L * (attempt + 1));
            }
        }
        throw last;
    }

    private HttpResponse requestFollowing(String method, String path,
                                          Map<String, String> headers, String body)
            throws NetException {
        String current = path;
        String currentMethod = method;
        String currentBody = body;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse r = requestOnce(currentMethod, current, headers, currentBody);
            if (r.status == 301 || r.status == 302 || r.status == 303 || r.status == 307) {
                String loc = r.header("location");
                if (loc == null || loc.length() == 0) {
                    return r;
                }
                Log.d("HTTP", "重定向 " + r.status + " -> " + loc);
                if (loc.startsWith("https://" + host)) {
                    current = loc.substring(("https://" + host).length());
                } else if (loc.startsWith("/")) {
                    current = loc;
                } else {
                    return r;
                }
                if (r.status == 303) {
                    currentMethod = "GET";
                    currentBody = null;
                }
                continue;
            }
            return r;
        }
        throw new NetException(NetException.STAGE_HTTP, "重定向次数过多");
    }

    private HttpResponse requestOnce(String method, String path, Map<String, String> headers,
                                     String body) throws NetException {
        TlsConnection c = obtain();
        try {
            byte[] bodyBytes = utf8(body);
            writeHead(c, method, path, headers, bodyBytes, false, true);
            if (bodyBytes != null && bodyBytes.length > 0) {
                c.out().write(bodyBytes);
            }
            c.out().flush();

            LineReader lr = new LineReader(c.in());
            int status = readStatus(lr);
            Map<String, String> respHeaders = readHeaders(lr);
            Log.d("HTTP", method + " " + path + " -> " + status);

            byte[] data = readBody(lr, respHeaders);
            if (shouldClose(respHeaders)) {
                dropConn();
            } else {
                connUses++;
            }
            return new HttpResponse(status, reasonOf(status), respHeaders, data);
        } catch (NetException ne) {
            dropConn();
            throw ne;
        } catch (IOException e) {
            dropConn();
            throw new NetException(NetException.STAGE_HTTP,
                    "读响应失败: " + e.getClass().getName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
        }
    }

    /** NDJSON 流式读取。独占一条连接，读完即关闭（不复用）。 */
    public void stream(String method, String path, Map<String, String> headers,
                       String body, LineHandler handler) throws NetException {
        dropConn();
        TlsConnection c = TlsConnection.connect(host, port, connectTimeout, readTimeout, anchors);
        try {
            byte[] bodyBytes = utf8(body);
            writeHead(c, method, path, headers, bodyBytes, true, false);
            if (bodyBytes != null && bodyBytes.length > 0) {
                c.out().write(bodyBytes);
            }
            c.out().flush();

            LineReader lr = new LineReader(c.in());
            int status = readStatus(lr);
            Map<String, String> respHeaders = readHeaders(lr);
            Log.d("HTTP", "STREAM " + path + " -> " + status);

            if (status != 200) {
                byte[] err = readBody(lr, respHeaders);
                throw new NetException(NetException.STAGE_HTTP,
                        "流式请求失败: HTTP " + status + " " + utf8String(err));
            }

            while (true) {
                String line = lr.readLineUtf8();
                if (line == null) {
                    Log.d("HTTP", "STREAM " + path + " 服务端关闭连接");
                    break;
                }
                if (line.length() == 0) {
                    continue; // 心跳
                }
                boolean go;
                try {
                    go = handler.onLine(line);
                } catch (Throwable t) {
                    Log.e("HTTP", "流处理回调异常", t);
                    go = false;
                }
                if (!go) {
                    Log.d("HTTP", "STREAM " + path + " 本地主动停止");
                    break;
                }
            }
        } catch (NetException ne) {
            throw ne;
        } catch (IOException e) {
            throw new NetException(NetException.STAGE_HTTP,
                    "流式读取中断: " + e.getClass().getName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------ 连接管理

    private synchronized TlsConnection obtain() throws NetException {
        if (conn != null && connUses < MAX_CONN_USES) {
            return conn;
        }
        dropConn();
        long t0 = System.currentTimeMillis();
        conn = TlsConnection.connect(host, port, connectTimeout, readTimeout, anchors);
        lastHandshakeMillis = System.currentTimeMillis() - t0;
        connUses = 0;
        Log.d("HTTP", "新建 TLS 连接，握手耗时 " + lastHandshakeMillis + "ms");
        return conn;
    }

    private synchronized void dropConn() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
        connUses = 0;
    }

    private static boolean shouldClose(Map<String, String> headers) {
        String v = headers.get("connection");
        return v != null && v.toLowerCase(Locale.US).indexOf("close") >= 0;
    }

    /** 哪些错误值得重试：网络抖动值得，HTTP 状态码类的错误不值得。 */
    private static boolean isRetryable(NetException e) {
        int s = e.getStage();
        return s == NetException.STAGE_DNS
                || s == NetException.STAGE_TCP
                || s == NetException.STAGE_TLS
                || s == NetException.STAGE_HTTP;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------- 协议细节

    private void writeHead(TlsConnection c, String method, String path,
                           Map<String, String> headers, byte[] body,
                           boolean streaming, boolean keepAlive)
            throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("User-Agent: ").append(userAgent).append("\r\n");
        sb.append("Accept-Encoding: identity\r\n");
        if (streaming) {
            sb.append("Accept: application/x-ndjson\r\n");
        }
        if (headers != null) {
            for (Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
                 it.hasNext(); ) {
                Map.Entry<String, String> e = it.next();
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        if (body != null && body.length > 0) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        } else if ("POST".equals(method)) {
            sb.append("Content-Length: 0\r\n");
        }
        sb.append(keepAlive ? "Connection: keep-alive\r\n" : "Connection: close\r\n");
        sb.append("\r\n");
        c.out().write(sb.toString().getBytes("ISO-8859-1"));
    }

    private int readStatus(LineReader lr) throws IOException, NetException {
        String line = lr.readLine();
        if (line == null) {
            throw new NetException(NetException.STAGE_HTTP, "服务端没有返回任何内容");
        }
        int sp1 = line.indexOf(' ');
        if (sp1 < 0) {
            throw new NetException(NetException.STAGE_HTTP, "响应状态行格式异常: " + line);
        }
        int sp2 = line.indexOf(' ', sp1 + 1);
        String code = sp2 < 0 ? line.substring(sp1 + 1) : line.substring(sp1 + 1, sp2);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new NetException(NetException.STAGE_HTTP, "状态码无法解析: " + line);
        }
    }

    private Map<String, String> readHeaders(LineReader lr) throws IOException {
        Map<String, String> map = new LinkedHashMap<String, String>();
        while (true) {
            String line = lr.readLine();
            if (line == null || line.length() == 0) {
                break;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String k = line.substring(0, c).trim().toLowerCase(Locale.US);
            String v = line.substring(c + 1).trim();
            if (map.containsKey(k)) {
                map.put(k, map.get(k) + ", " + v);
            } else {
                map.put(k, v);
            }
        }
        return map;
    }

    private byte[] readBody(LineReader lr, Map<String, String> headers)
            throws IOException, NetException {
        String te = headers.get("transfer-encoding");
        String cl = headers.get("content-length");
        String ce = headers.get("content-encoding");

        InputStream src;
        if (te != null && te.toLowerCase(Locale.US).indexOf("chunked") >= 0) {
            src = new ChunkedInputStream(lr);
        } else if (cl != null) {
            long n;
            try {
                n = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                n = -1;
            }
            src = new FixedLengthInputStream(lr, n);
        } else {
            src = new UntilEofInputStream(lr);
        }

        if (ce != null && ce.toLowerCase(Locale.US).indexOf("gzip") >= 0) {
            try {
                src = new GZIPInputStream(src);
            } catch (IOException ignored) {
                // 解压失败按原样读
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[4096];
        int n;
        while ((n = src.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BODY) {
                throw new NetException(NetException.STAGE_HTTP,
                        "响应体超过 " + (MAX_BODY / 1024) + "KB，已放弃（防止 OOM）");
            }
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------ body 流实现

    private static final class FixedLengthInputStream extends InputStream {
        private final LineReader lr;
        private long remaining;

        FixedLengthInputStream(LineReader lr, long length) {
            this.lr = lr;
            this.remaining = length < 0 ? Long.MAX_VALUE : length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            byte[] one = new byte[1];
            int n = lr.readFully(one, 0, 1);
            if (n <= 0) {
                remaining = 0;
                return -1;
            }
            remaining--;
            return one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int want = (int) Math.min((long) len, remaining);
            int n = lr.readFully(b, off, want);
            if (n <= 0) {
                remaining = 0;
                return -1;
            }
            remaining -= n;
            return n;
        }
    }

    /** 读到连接关闭为止。只在服务端既不给 Content-Length 也不给 chunked 时使用。 */
    private static final class UntilEofInputStream extends InputStream {
        private final LineReader lr;

        UntilEofInputStream(LineReader lr) {
            this.lr = lr;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            String line = lr.readLine();
            if (line == null) {
                return -1;
            }
            byte[] raw = line.getBytes("ISO-8859-1");
            int take = Math.min(len, raw.length);
            System.arraycopy(raw, 0, b, off, take);
            if (take < raw.length) {
                throw new IOException("单行数据超过缓冲区");
            }
            return take;
        }
    }

    /** chunked 解码流。 */
    private static final class ChunkedInputStream extends InputStream {
        private final LineReader lr;
        private long chunkLeft;
        private boolean done;
        private boolean needChunkHeader = true;

        ChunkedInputStream(LineReader lr) {
            this.lr = lr;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) {
                return -1;
            }
            if (needChunkHeader && !readChunkHeader()) {
                return -1;
            }
            if (chunkLeft == 0) {
                lr.readLine(); // chunk 数据后的 CRLF
                needChunkHeader = true;
                if (!readChunkHeader()) {
                    return -1;
                }
            }
            int want = (int) Math.min((long) len, chunkLeft);
            int n = lr.readFully(b, off, want);
            if (n <= 0) {
                done = true;
                return -1;
            }
            chunkLeft -= n;
            return n;
        }

        private boolean readChunkHeader() throws IOException {
            needChunkHeader = false;
            String line = lr.readLine();
            if (line == null) {
                done = true;
                return false;
            }
            int semi = line.indexOf(';');
            if (semi >= 0) {
                line = line.substring(0, semi);
            }
            line = line.trim();
            if (line.length() == 0) {
                return readChunkHeader();
            }
            long size;
            try {
                size = Long.parseLong(line, 16);
            } catch (NumberFormatException e) {
                done = true;
                return false;
            }
            if (size == 0) {
                while (true) {
                    String t = lr.readLine();
                    if (t == null || t.length() == 0) {
                        break;
                    }
                }
                done = true;
                return false;
            }
            chunkLeft = size;
            return true;
        }
    }

    // ------------------------------------------------------------------ 工具

    public static String formEncode(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(128);
        for (Iterator<Map.Entry<String, String>> it = params.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            try {
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            } catch (UnsupportedEncodingException ex) {
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** 常用请求头构造。 */
    public static Map<String, String> headers(String accept, String token) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (accept != null) {
            h.put("Accept", accept);
        }
        if (token != null && token.length() > 0) {
            h.put("Authorization", "Bearer " + token);
        }
        return h;
    }

    public static Map<String, String> formHeaders(String token) {
        Map<String, String> h = headers("application/json", token);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return h;
    }

    private static byte[] utf8(String s) {
        if (s == null) {
            return null;
        }
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    private static String utf8String(byte[] b) {
        try {
            return new String(b, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(b);
        }
    }

    static String reasonOf(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 201:
                return "Created";
            case 204:
                return "No Content";
            case 301:
                return "Moved Permanently";
            case 302:
                return "Found";
            case 303:
                return "See Other";
            case 304:
                return "Not Modified";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            case 502:
                return "Bad Gateway";
            case 503:
                return "Service Unavailable";
            default:
                return "";
        }
    }
}
