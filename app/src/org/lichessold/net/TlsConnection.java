package org.lichessold.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Hashtable;
import java.util.Vector;

import org.spongycastle.crypto.tls.Certificate;
import org.spongycastle.crypto.tls.CipherSuite;
import org.spongycastle.crypto.tls.DefaultTlsClient;
import org.spongycastle.crypto.tls.ECPointFormat;
import org.spongycastle.crypto.tls.NamedCurve;
import org.spongycastle.crypto.tls.ProtocolVersion;
import org.spongycastle.crypto.tls.ServerName;
import org.spongycastle.crypto.tls.ServerNameList;
import org.spongycastle.crypto.tls.TlsAuthentication;
import org.spongycastle.crypto.tls.TlsClientProtocol;
import org.spongycastle.crypto.tls.TlsCredentials;
import org.spongycastle.crypto.tls.TlsExtensionsUtils;

import org.lichessold.util.Log;

/**
 * 自带 TLS 1.2 连接（Spongy Castle）。
 *
 * 这是整个项目技术风险最高的一块。Android 2.3.6 的系统 TLS 栈有三个硬伤：
 *   1. 不发送 SNI（server_name 扩展）→ 有 CDN/反代的站点直接握手失败或返回错证书
 *   2. 只启用 TLS 1.0 → Lichess 要求 TLS 1.2+
 *   3. 根证书库过旧 → 缺 ISRG Root X1/X2 等新根
 * 所以这里用纯 Java 的 TlsClientProtocol 自己实现，显式发 SNI，自带信任锚，
 * **证书校验一步不省**（见 CertVerifier）。
 */
public final class TlsConnection {

    private static final int DEFAULT_CONNECT_TIMEOUT = 20000;

    /** 支持的密码套件。按强度排序，ECDSA 和 RSA 两套都留着以兼容不同 CA。 */
    private static final int[] CIPHER_SUITES = {
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
    };

    private static final int[] NAMED_CURVES = {
            NamedCurve.secp256r1,
            NamedCurve.secp384r1,
            NamedCurve.secp521r1,
    };

    private final Socket socket;
    private final TlsClientProtocol protocol;
    private final InputStream in;
    private final OutputStream out;
    private final String host;
    private final CertVerifier.Result certInfo;

    private TlsConnection(Socket socket, TlsClientProtocol protocol, String host,
                          CertVerifier.Result certInfo) {
        this.socket = socket;
        this.protocol = protocol;
        this.in = protocol.getInputStream();
        this.out = protocol.getOutputStream();
        this.host = host;
        this.certInfo = certInfo;
    }

    public InputStream in() {
        return in;
    }

    public OutputStream out() {
        return out;
    }

    public String host() {
        return host;
    }

    public CertVerifier.Result certInfo() {
        return certInfo;
    }

    public void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    public void close() {
        try {
            protocol.close();
        } catch (Throwable ignored) {
        }
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- connect

    public static TlsConnection connect(String host, int port, int readTimeoutMs,
                                        TrustAnchors anchors) throws NetException {
        return connect(host, port, DEFAULT_CONNECT_TIMEOUT, readTimeoutMs, anchors);
    }

    public static TlsConnection connect(String host, int port, int connectTimeoutMs,
                                        int readTimeoutMs, TrustAnchors anchors)
            throws NetException {

        // ---- 1. DNS
        long t0 = System.currentTimeMillis();
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new NetException(NetException.STAGE_DNS,
                    "域名解析失败: " + host + "（检查手机是否连上 Wi-Fi / DNS 是否可用）", e);
        }
        if (addrs == null || addrs.length == 0) {
            throw new NetException(NetException.STAGE_DNS, "域名解析没有返回任何地址: " + host);
        }
        StringBuilder ips = new StringBuilder(64);
        for (int i = 0; i < addrs.length; i++) {
            if (i > 0) {
                ips.append(", ");
            }
            ips.append(addrs[i].getHostAddress());
        }
        Log.d("TLS", "DNS " + host + " -> " + ips + " (" + (System.currentTimeMillis() - t0) + "ms)");

        // ---- 2. TCP（逐个地址试，IPv4 优先）
        IOException lastError = null;
        Socket socket = null;
        for (int attempt = 0; attempt < addrs.length; attempt++) {
            InetAddress addr = pickOrdered(addrs, attempt);
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                long t1 = System.currentTimeMillis();
                s.connect(new InetSocketAddress(addr, port), connectTimeoutMs);
                s.setSoTimeout(readTimeoutMs);
                Log.d("TLS", "TCP " + addr.getHostAddress() + ":" + port + " 已连接 ("
                        + (System.currentTimeMillis() - t1) + "ms)");
                socket = s;
                break;
            } catch (IOException e) {
                lastError = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                Log.d("TLS", "TCP " + addr.getHostAddress() + " 失败: " + e.getMessage());
            }
        }
        if (socket == null) {
            throw new NetException(NetException.STAGE_TCP,
                    "TCP 连接失败（试过 " + addrs.length + " 个地址）。"
                            + "检查 Wi-Fi 是否能上外网、有没有被防火墙拦。", lastError);
        }

        // ---- 3. TLS 握手 + 证书校验
        CertVerifier.Result certInfo = new CertVerifier.Result();
        try {
            InputStream sin = socket.getInputStream();
            OutputStream sout = socket.getOutputStream();
            TlsClientProtocol p = new TlsClientProtocol(sin, sout, new SecureRandom());
            ClientImpl client = new ClientImpl(host, anchors, certInfo);
            long t2 = System.currentTimeMillis();
            p.connect(client);
            Log.i("TLS", "握手成功 " + certInfo.protocolVersion + " / " + certInfo.cipherSuite
                    + " (" + (System.currentTimeMillis() - t2) + "ms)");
            return new TlsConnection(socket, p, host, certInfo);
        } catch (NetException ne) {
            closeQuietly(socket);
            throw ne;
        } catch (Throwable t) {
            closeQuietly(socket);
            String msg = t.getClass().getName();
            if (t.getMessage() != null) {
                msg = msg + ": " + t.getMessage();
            }
            throw new NetException(NetException.STAGE_TLS,
                    "TLS 握手失败: " + msg, t);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    /** 让 IPv4 排在前面：老设备上 IPv6 经常不可用，先试 v4 少浪费时间。 */
    private static InetAddress pickOrdered(InetAddress[] addrs, int index) {
        Vector<InetAddress> v4 = new Vector<InetAddress>();
        Vector<InetAddress> v6 = new Vector<InetAddress>();
        for (int i = 0; i < addrs.length; i++) {
            if (addrs[i] instanceof java.net.Inet6Address) {
                v6.addElement(addrs[i]);
            } else {
                v4.addElement(addrs[i]);
            }
        }
        if (index < v4.size()) {
            return v4.elementAt(index);
        }
        return v6.elementAt(index - v4.size());
    }

    // ---------------------------------------------------------- TlsClient 实现

    private static final class ClientImpl extends DefaultTlsClient {

        private final String host;
        private final TrustAnchors anchors;
        private final CertVerifier.Result info;

        ClientImpl(String host, TrustAnchors anchors, CertVerifier.Result info) {
            this.host = host;
            this.anchors = anchors;
            this.info = info;
            // 显式声明椭圆曲线与点格式：老服务端/老客户端在缺这两项时可能协商失败
            this.namedCurves = NAMED_CURVES;
            this.clientECPointFormats = new short[] { ECPointFormat.uncompressed };
        }

        @Override
        public ProtocolVersion getClientVersion() {
            return ProtocolVersion.TLSv12;
        }

        @Override
        public ProtocolVersion getMinimumVersion() {
            // 低于 TLS 1.2 直接放弃，不降级
            return ProtocolVersion.TLSv12;
        }

        @Override
        public int[] getCipherSuites() {
            return CIPHER_SUITES;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Hashtable getClientExtensions() throws IOException {
            Hashtable ext = TlsExtensionsUtils.ensureExtensionsInitialised(
                    super.getClientExtensions());
            // ★ 这一行就是 Android 2.3 系统 TLS 栈缺的东西：显式声明 server_name
            Vector names = new Vector(1);
            names.addElement(new ServerName((short) 0, host));
            TlsExtensionsUtils.addServerNameExtension(ext, new ServerNameList(names));
            return ext;
        }

        @Override
        public void notifyServerVersion(ProtocolVersion serverVersion) throws IOException {
            super.notifyServerVersion(serverVersion);
            info.protocolVersion = serverVersion == null ? "?" : serverVersion.toString();
            Log.d("TLS", "服务端协议版本: " + info.protocolVersion);
        }

        @Override
        public void notifySelectedCipherSuite(int selectedCipherSuite) {
            super.notifySelectedCipherSuite(selectedCipherSuite);
            info.cipherSuite = cipherSuiteName(selectedCipherSuite);
            Log.d("TLS", "协商密码套件: " + info.cipherSuite);
        }

        @Override
        public TlsAuthentication getAuthentication() throws IOException {
            return new TlsAuthentication() {
                public void notifyServerCertificate(Certificate serverCertificate)
                        throws IOException {
                    org.spongycastle.asn1.x509.Certificate[] chain =
                            serverCertificate.getCertificateList();
                    try {
                        CertVerifier.Result r =
                                CertVerifier.verify(chain, host, anchors);
                        copyInto(info, r);
                        Log.i("TLS", "证书链校验通过，锚=" + r.anchor);
                    } catch (NetException ne) {
                        // 校验失败 → 抛出去，握手终止。绝不降级为"跳过校验"。
                        Log.e("TLS", "证书校验失败: " + ne.getMessage());
                        throw ne;
                    }
                }

                public TlsCredentials getClientCredentials(
                        org.spongycastle.crypto.tls.CertificateRequest request) {
                    // 不做客户端证书认证
                    return null;
                }
            };
        }
    }

    private static void copyInto(CertVerifier.Result dst, CertVerifier.Result src) {
        dst.chainLength = src.chainLength;
        dst.subjects = src.subjects;
        dst.issuers = src.issuers;
        dst.anchor = src.anchor;
        dst.hostname = src.hostname;
        dst.hostnameMatched = src.hostnameMatched;
        dst.notBefore = src.notBefore;
        dst.notAfter = src.notAfter;
        dst.verifyMillis = src.verifyMillis;
    }

    /** 把密码套件编号转成可读名字，只覆盖我们启用的那些。 */
    public static String cipherSuiteName(int cs) {
        switch (cs) {
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:
                return "ECDHE-ECDSA-AES128-GCM-SHA256";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:
                return "ECDHE-RSA-AES128-GCM-SHA256";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:
                return "ECDHE-ECDSA-AES256-GCM-SHA384";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:
                return "ECDHE-RSA-AES256-GCM-SHA384";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:
                return "ECDHE-ECDSA-AES128-SHA256";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256:
                return "ECDHE-RSA-AES128-SHA256";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384:
                return "ECDHE-ECDSA-AES256-SHA384";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384:
                return "ECDHE-RSA-AES256-SHA384";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA:
                return "ECDHE-ECDSA-AES128-SHA";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA:
                return "ECDHE-RSA-AES128-SHA";
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:
                return "ECDHE-ECDSA-AES256-SHA";
            case CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:
                return "ECDHE-RSA-AES256-SHA";
            case CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256:
                return "AES128-GCM-SHA256";
            case CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA:
                return "AES128-SHA";
            case CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA:
                return "AES256-SHA";
            default:
                return "0x" + Integer.toHexString(cs);
        }
    }
}
