package org.lichessold.net;

import java.io.IOException;
import java.io.InputStream;

/**
 * 按行读取的输入流包装（纯 Java）。
 *
 * 用于 NDJSON 流式响应：一次只吐一行，绝不把整个响应读进内存
 * —— Lichess 的事件流是长连接，读完整响应等于永远不返回。
 *
 * 同时提供 readFully()，因为 HTTP 响应头和 body 的读取要共用同一个缓冲区，
 * 分两个 Reader 会把已经读进缓冲区的字节丢掉。
 */
public final class LineReader {

    private static final int DEFAULT_BUF = 2048;

    private final InputStream in;
    private byte[] buf;
    private int pos;
    private int len;
    private final StringBuilder sb = new StringBuilder(256);

    public LineReader(InputStream in) {
        this(in, DEFAULT_BUF);
    }

    public LineReader(InputStream in, int bufferSize) {
        this.in = in;
        this.buf = new byte[Math.max(256, bufferSize)];
    }

    /**
     * 读一行（以 \n 结束，自动去掉 \r）。
     * 空行返回 ""（Lichess 的心跳就是空行，不能当 EOF）。
     * 到流末尾返回 null。
     */
    public String readLine() throws IOException {
        sb.setLength(0);
        boolean any = false;
        while (true) {
            if (pos >= len) {
                if (!fill()) {
                    return any ? sb.toString() : null;
                }
            }
            byte b = buf[pos++];
            any = true;
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            // 用 ISO-8859-1 语义直接映射，UTF-8 多字节序列会被拼回来后再解码
            sb.append((char) (b & 0xFF));
        }
    }

    /**
     * 读一行并解码成 UTF-8 字符串。
     * HTTP 头是 ASCII，但 JSON 正文里有中文（用户名、聊天），必须按 UTF-8 解。
     */
    public String readLineUtf8() throws IOException {
        String raw = readLine();
        if (raw == null) {
            return null;
        }
        return toUtf8(raw);
    }

    /** 把 readLine 得到的 latin1 字符串还原成真正的 UTF-8 字符串。 */
    public static String toUtf8(String latin1) {
        int n = latin1.length();
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (latin1.charAt(i) & 0xFF);
        }
        try {
            return new String(b, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return latin1;
        }
    }

    /** 精确读取 n 个字节。到流末尾提前结束，返回实际读到的字节数。 */
    public int readFully(byte[] out, int off, int n) throws IOException {
        int total = 0;
        while (total < n) {
            if (pos < len) {
                int take = Math.min(n - total, len - pos);
                System.arraycopy(buf, pos, out, off + total, take);
                pos += take;
                total += take;
                continue;
            }
            if (!fill()) {
                break;
            }
        }
        return total;
    }

    private boolean fill() throws IOException {
        pos = 0;
        len = 0;
        int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            return false;
        }
        len = n;
        return true;
    }

    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
