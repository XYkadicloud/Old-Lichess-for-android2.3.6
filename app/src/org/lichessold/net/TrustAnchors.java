package org.lichessold.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.spongycastle.asn1.x500.AttributeTypeAndValue;
import org.spongycastle.asn1.x500.RDN;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x500.style.IETFUtils;
import org.spongycastle.asn1.x509.Certificate;
import org.spongycastle.util.encoders.Base64;

/**
 * 信任锚集合（内置根证书）。
 *
 * 输入是 PEM 文本（app/assets/cacerts.pem，来自 Mozilla CA bundle），
 * 解析成 Spongy Castle 的 x509.Certificate 对象。
 *
 * 关键点：**不使用 android 的 CertificateFactory**。因为 API 10 的
 * CertificateFactory 走的是系统 BouncyCastle，验签时要用 java.security.Signature，
 * 而 API 10 没有 SHA256withECDSA（API 11 才加入）。整个校验链路统一走
 * Spongy Castle 的轻量密码学 API，行为在 API 10 上完全可控。
 */
public final class TrustAnchors {

    private final List<Certificate> anchors;
    /** 归一化 DN -> 锚证书，用于快速查找签发者。 */
    private final List<String> anchorDnKeys;

    private TrustAnchors(List<Certificate> anchors) {
        this.anchors = anchors;
        this.anchorDnKeys = new ArrayList<String>(anchors.size());
        for (int i = 0; i < anchors.size(); i++) {
            anchorDnKeys.add(dnKey(anchors.get(i).getSubject()));
        }
    }

    public static TrustAnchors fromPem(InputStream in) throws IOException {
        List<byte[]> ders = parsePem(in);
        List<Certificate> certs = new ArrayList<Certificate>(ders.size());
        for (int i = 0; i < ders.size(); i++) {
            try {
                certs.add(Certificate.getInstance(ders.get(i)));
            } catch (Throwable t) {
                // 个别证书解析失败不影响整体
            }
        }
        if (certs.isEmpty()) {
            throw new IOException("PEM 里没有解析出任何证书");
        }
        return new TrustAnchors(certs);
    }

    public int size() {
        return anchors.size();
    }

    public Certificate get(int i) {
        return anchors.get(i);
    }

    /** 只保留第 index 个锚，用于测试"不相关的信任锚必须被拒绝"。 */
    public TrustAnchors only(int index) {
        List<Certificate> one = new ArrayList<Certificate>(1);
        one.add(anchors.get(index));
        return new TrustAnchors(one);
    }

    /** 第 index 个锚的 CN，便于测试里挑一个和当前链无关的根。 */
    public String cnAt(int index) {
        return shortName(anchors.get(index).getSubject());
    }

    /** 按主体 DN 找信任锚；找不到返回 null。 */
    public Certificate findBySubject(X500Name subject) {
        if (subject == null) {
            return null;
        }
        String key = dnKey(subject);
        for (int i = 0; i < anchorDnKeys.size(); i++) {
            if (anchorDnKeys.get(i).equals(key)) {
                return anchors.get(i);
            }
        }
        return null;
    }

    /** 是否直接信任这张证书（自签根）。 */
    public boolean isTrusted(Certificate cert) {
        Certificate found = findBySubject(cert.getSubject());
        return found != null && found.getSerialNumber().equals(cert.getSerialNumber());
    }

    // ------------------------------------------------------------------ PEM

    private static List<byte[]> parsePem(InputStream in) throws IOException {
        List<byte[]> out = new ArrayList<byte[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        try {
            StringBuilder b64 = new StringBuilder(2048);
            boolean inCert = false;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }
                if (line.startsWith("-----BEGIN")) {
                    inCert = line.indexOf("CERTIFICATE") >= 0;
                    b64.setLength(0);
                    continue;
                }
                if (line.startsWith("-----END")) {
                    if (inCert && b64.length() > 0) {
                        try {
                            out.add(Base64.decode(b64.toString()));
                        } catch (Throwable ignored) {
                            // 跳过坏块
                        }
                    }
                    inCert = false;
                    b64.setLength(0);
                    continue;
                }
                if (inCert) {
                    b64.append(line);
                }
            }
        } finally {
            try {
                r.close();
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------ DN 归一化

    /**
     * 把 X500Name 归一化成可比较的字符串。
     *
     * 为什么不直接比 DER 字节：同一个 DN 可以用不同的字符串类型（PrintableString /
     * UTF8String）编码，DER 字节会不一样，但语义相同。真实证书链里这会导致
     * "明明有根证书却说找不到"。所以按 RFC4514 逆序拼 type=value。
     */
    public static String dnKey(X500Name name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(96);
        try {
            RDN[] rdns = name.getRDNs();
            for (int i = rdns.length - 1; i >= 0; i--) {
                AttributeTypeAndValue[] avas = rdns[i].getTypesAndValues();
                for (int j = 0; j < avas.length; j++) {
                    sb.append(avas[j].getType().getId()).append('=');
                    try {
                        sb.append(IETFUtils.valueToString(avas[j].getValue()));
                    } catch (Throwable t) {
                        sb.append(avas[j].getValue().toString());
                    }
                    sb.append('|');
                }
            }
        } catch (Throwable t) {
            return String.valueOf(name);
        }
        return sb.toString().toLowerCase(Locale.US);
    }

    /** 生成用于展示的简短 DN（只取 CN，没有就退回完整 DN）。 */
    public static String shortName(X500Name name) {
        if (name == null) {
            return "?";
        }
        try {
            RDN[] cn = name.getRDNs(org.spongycastle.asn1.x500.style.BCStyle.CN);
            if (cn != null && cn.length > 0) {
                AttributeTypeAndValue ava = cn[0].getFirst();
                if (ava != null) {
                    return IETFUtils.valueToString(ava.getValue());
                }
            }
        } catch (Throwable ignored) {
        }
        String s = String.valueOf(name);
        return s.length() > 64 ? s.substring(0, 64) + "..." : s;
    }

    /** 供诊断页显示：把所有锚证书的 CN 拼起来（只在需要时调用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(anchors.size() * 24);
        for (int i = 0; i < anchors.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shortName(anchors.get(i).getSubject()));
        }
        return sb.toString();
    }

    /** 便于测试：从 DER 字节数组直接构造。 */
    public static TrustAnchors fromDer(List<byte[]> ders) throws IOException {
        List<Certificate> certs = new ArrayList<Certificate>(ders.size());
        for (int i = 0; i < ders.size(); i++) {
            certs.add(Certificate.getInstance(ders.get(i)));
        }
        if (certs.isEmpty()) {
            throw new IOException("no certificates");
        }
        return new TrustAnchors(certs);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 读 PEM 文件里的第一张证书，返回 DER 字节。给测试用。 */
    public static byte[] readFirstPemCertificate(InputStream in) throws IOException {
        List<byte[]> ders = parsePem(in);
        if (ders.isEmpty()) {
            throw new IOException("PEM 里没有证书");
        }
        return ders.get(0);
    }
}
