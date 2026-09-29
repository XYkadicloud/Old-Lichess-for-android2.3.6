package org.lichessold.net;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.spongycastle.asn1.ASN1Encodable;
import org.spongycastle.asn1.ASN1Integer;
import org.spongycastle.asn1.ASN1Sequence;
import org.spongycastle.asn1.ASN1String;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.BasicConstraints;
import org.spongycastle.asn1.x509.Certificate;
import org.spongycastle.asn1.x509.Extension;
import org.spongycastle.asn1.x509.Extensions;
import org.spongycastle.asn1.x509.GeneralName;
import org.spongycastle.asn1.x509.GeneralNames;
import org.spongycastle.crypto.Digest;
import org.spongycastle.crypto.digests.SHA1Digest;
import org.spongycastle.crypto.digests.SHA224Digest;
import org.spongycastle.crypto.digests.SHA256Digest;
import org.spongycastle.crypto.digests.SHA384Digest;
import org.spongycastle.crypto.digests.SHA512Digest;
import org.spongycastle.crypto.params.AsymmetricKeyParameter;
import org.spongycastle.crypto.params.ECPublicKeyParameters;
import org.spongycastle.crypto.params.RSAKeyParameters;
import org.spongycastle.crypto.signers.ECDSASigner;
import org.spongycastle.crypto.signers.RSADigestSigner;
import org.spongycastle.crypto.util.PublicKeyFactory;

/**
 * X.509 证书链校验。**完全走 Spongy Castle 的轻量密码学 API。**
 *
 * 为什么不能用 java.security.Signature：
 *   API 10（Android 2.3.6）里没有 "SHA256withECDSA"（API 11 才加入），
 *   而 Let's Encrypt / 现代 CA 大量使用 ECDSA 证书。用系统 API 会在真机上
 *   直接 NoSuchAlgorithmException。所以：
 *     - RSA  → RSADigestSigner（自己喂 Digest）
 *     - ECDSA → 自己算摘要 + ASN.1 拆 r/s + ECDSASigner.verifySignature(hash, r, s)
 *
 * 校验内容（缺一不可）：
 *   1. 链能否从叶证书一路拼到内置信任锚
 *   2. 每一环的签名是否用签发者的公钥验得过
 *   3. 中间证书的 BasicConstraints CA 位
 *   4. 每张证书的有效期（并把手机当前时间一起报出来，方便判断是不是手机时间不对）
 *   5. 叶证书 SAN / CN 是否匹配目标主机名
 */
public final class CertVerifier {

    private static final int MAX_CHAIN = 8;

    /** 校验结果，供网络诊断页逐项显示。 */
    public static final class Result {

        public String protocolVersion = "";
        public String cipherSuite = "";
        public int chainLength;
        public String[] subjects = new String[0];
        public String[] issuers = new String[0];
        public String anchor = "";
        public boolean hostnameMatched;
        public String hostname = "";
        public String notBefore = "";
        public String notAfter = "";
        public long verifyMillis;

        public String describe() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("链长度 ").append(chainLength).append('\n');
            for (int i = 0; i < subjects.length; i++) {
                sb.append("  [").append(i).append("] ").append(subjects[i])
                        .append("  <- 签发者 ").append(issuers[i]).append('\n');
            }
            sb.append("信任锚: ").append(anchor).append('\n');
            sb.append("主机名: ").append(hostname)
                    .append(hostnameMatched ? " 匹配 OK" : " 不匹配 !!").append('\n');
            sb.append("有效期: ").append(notBefore).append(" ~ ").append(notAfter).append('\n');
            sb.append("校验耗时: ").append(verifyMillis).append(" ms");
            return sb.toString();
        }
    }

    private CertVerifier() {
    }

    public static Result verify(Certificate[] serverChain, String host, TrustAnchors anchors)
            throws NetException {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        r.hostname = host;

        if (serverChain == null || serverChain.length == 0) {
            throw new NetException(NetException.STAGE_CERT, "服务端没有下发任何证书");
        }

        // ---- 1. 构建链：叶 -> ... -> 中间 -> 信任锚
        List<Certificate> path = new ArrayList<Certificate>(MAX_CHAIN);
        Certificate current = serverChain[0];
        Certificate anchor = null;

        for (int depth = 0; depth < MAX_CHAIN; depth++) {
            path.add(current);

            // 自己就是信任锚？
            if (anchors.isTrusted(current)) {
                anchor = current;
                break;
            }

            // 找一个能签它的信任锚
            Certificate byAnchor = anchors.findBySubject(current.getIssuer());
            if (byAnchor != null && verifySignedBy(current, byAnchor)) {
                anchor = byAnchor;
                break;
            }

            // 否则从服务端下发的链里找中间证书
            Certificate next = null;
            String want = TrustAnchors.dnKey(current.getIssuer());
            for (int i = 0; i < serverChain.length; i++) {
                Certificate cand = serverChain[i];
                if (cand == current) {
                    continue;
                }
                if (TrustAnchors.dnKey(cand.getSubject()).equals(want)) {
                    if (verifySignedBy(current, cand)) {
                        next = cand;
                        break;
                    }
                }
            }
            if (next == null) {
                throw new NetException(NetException.STAGE_CERT,
                        "证书链不完整或签发者不受信任。\n"
                                + "缺少的签发者: " + TrustAnchors.shortName(current.getIssuer()) + "\n"
                                + "（服务端只下发了 " + serverChain.length + " 张证书）");
            }
            // 中间证书必须是 CA
            if (!isCa(next)) {
                throw new NetException(NetException.STAGE_CERT,
                        "中间证书没有 CA 权限: " + TrustAnchors.shortName(next.getSubject()));
            }
            current = next;
        }

        if (anchor == null) {
            throw new NetException(NetException.STAGE_CERT,
                    "证书链超过 " + MAX_CHAIN + " 层仍没找到受信任的根");
        }

        // ---- 2. 有效期（把手机时间也报出来，便于判断是不是手机时间跑偏了）
        for (int i = 0; i < path.size(); i++) {
            Certificate c = path.get(i);
            Date nb = c.getStartDate().getDate();
            Date na = c.getEndDate().getDate();
            if (i == 0) {
                r.notBefore = fmtDate(nb);
                r.notAfter = fmtDate(na);
            }
            long now = System.currentTimeMillis();
            if (now < nb.getTime()) {
                throw new NetException(NetException.STAGE_CERT,
                        "证书还没生效。\n证书: " + TrustAnchors.shortName(c.getSubject())
                                + "\n生效时间: " + fmtDate(nb)
                                + "\n手机当前时间: " + fmtDate(new Date(now))
                                + "\n→ 请检查手机的日期时间设置");
            }
            if (now > na.getTime()) {
                throw new NetException(NetException.STAGE_CERT,
                        "证书已过期。\n证书: " + TrustAnchors.shortName(c.getSubject())
                                + "\n过期时间: " + fmtDate(na)
                                + "\n手机当前时间: " + fmtDate(new Date(now))
                                + "\n→ 请先检查手机的日期时间设置，再确认证书是否真的过期");
            }
        }

        // ---- 3. 主机名
        r.hostnameMatched = hostnameMatches(path.get(0), host);
        if (!r.hostnameMatched) {
            throw new NetException(NetException.STAGE_CERT,
                    "证书主机名不匹配。\n期望: " + host
                            + "\n证书 CN: " + TrustAnchors.shortName(path.get(0).getSubject())
                            + "\n证书 SAN: " + describeSan(path.get(0)));
        }

        // ---- 4. 填充结果
        r.chainLength = path.size();
        r.subjects = new String[path.size()];
        r.issuers = new String[path.size()];
        for (int i = 0; i < path.size(); i++) {
            r.subjects[i] = TrustAnchors.shortName(path.get(i).getSubject());
            r.issuers[i] = TrustAnchors.shortName(path.get(i).getIssuer());
        }
        r.anchor = TrustAnchors.shortName(anchor.getSubject());
        r.verifyMillis = System.currentTimeMillis() - t0;
        return r;
    }

    // ------------------------------------------------------------- 签名验签

    /** cert 是否由 issuer 签发（用 issuer 的公钥验证 cert 的签名）。 */
    public static boolean verifySignedBy(Certificate cert, Certificate issuer) {
        try {
            String oid = cert.getSignatureAlgorithm().getAlgorithm().getId();
            byte[] tbs = cert.getTBSCertificate().getEncoded("DER");
            byte[] sig = cert.getSignature().getBytes();
            AsymmetricKeyParameter key = PublicKeyFactory.createKey(issuer.getSubjectPublicKeyInfo());

            if (key instanceof RSAKeyParameters) {
                return verifyRsa(oid, key, tbs, sig);
            }
            if (key instanceof ECPublicKeyParameters) {
                return verifyEcdsa(oid, key, tbs, sig);
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 同上，但入参是 DER 字节。给测试用，避免测试代码依赖 Spongy Castle 类型
     * （这样测试可以在不加载 3MB jar 索引的情况下编译，省内存）。
     */
    public static boolean verifyDerSignedBy(byte[] certDer, byte[] issuerDer) {
        try {
            return verifySignedBy(Certificate.getInstance(certDer),
                    Certificate.getInstance(issuerDer));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean verifyRsa(String oid, AsymmetricKeyParameter key,
                                     byte[] tbs, byte[] sig) {
        Digest digest = digestFor(oid);
        if (digest == null) {
            return false;
        }
        RSADigestSigner signer = new RSADigestSigner(digest);
        signer.init(false, key);
        signer.update(tbs, 0, tbs.length);
        return signer.verifySignature(sig);
    }

    private static boolean verifyEcdsa(String oid, AsymmetricKeyParameter key,
                                       byte[] tbs, byte[] sig) {
        Digest digest = digestFor(oid);
        if (digest == null) {
            return false;
        }
        digest.update(tbs, 0, tbs.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);

        // ECDSA 签名是 DER SEQUENCE { r INTEGER, s INTEGER }
        ASN1Sequence seq = ASN1Sequence.getInstance(sig);
        BigInteger r = ASN1Integer.getInstance(seq.getObjectAt(0)).getValue();
        BigInteger s = ASN1Integer.getInstance(seq.getObjectAt(1)).getValue();

        ECDSASigner signer = new ECDSASigner();
        signer.init(false, key);
        return signer.verifySignature(hash, r, s);
    }

    /** 签名算法 OID -> 摘要。不认识的算法返回 null（一律拒绝，不降级）。 */
    private static Digest digestFor(String oid) {
        if (oid == null) {
            return null;
        }
        if ("1.2.840.113549.1.1.5".equals(oid) || "1.2.840.10045.4.1".equals(oid)
                || "1.3.14.3.2.29".equals(oid)) {
            return new SHA1Digest();
        }
        if ("1.2.840.113549.1.1.14".equals(oid) || "1.2.840.10045.4.3.1".equals(oid)) {
            return new SHA224Digest();
        }
        if ("1.2.840.113549.1.1.11".equals(oid) || "1.2.840.10045.4.3.2".equals(oid)) {
            return new SHA256Digest();
        }
        if ("1.2.840.113549.1.1.12".equals(oid) || "1.2.840.10045.4.3.3".equals(oid)) {
            return new SHA384Digest();
        }
        if ("1.2.840.113549.1.1.13".equals(oid) || "1.2.840.10045.4.3.4".equals(oid)) {
            return new SHA512Digest();
        }
        return null;
    }

    // ------------------------------------------------------------ 扩展项

    private static boolean isCa(Certificate cert) {
        try {
            Extensions exts = cert.getTBSCertificate().getExtensions();
            if (exts == null) {
                return false;
            }
            Extension bc = exts.getExtension(Extension.basicConstraints);
            if (bc == null) {
                return false;
            }
            ASN1Encodable v = exts.getExtensionParsedValue(Extension.basicConstraints);
            if (v == null) {
                return false;
            }
            return BasicConstraints.getInstance(v).isCA();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取叶证书 SAN 里的所有 DNS 名；没有 SAN 则返回空数组。 */
    public static String[] dnsNames(Certificate cert) {
        List<String> out = new ArrayList<String>(4);
        try {
            Extensions exts = cert.getTBSCertificate().getExtensions();
            if (exts == null) {
                return new String[0];
            }
            ASN1Encodable v = exts.getExtensionParsedValue(Extension.subjectAlternativeName);
            if (v == null) {
                return new String[0];
            }
            GeneralNames names = GeneralNames.getInstance(v);
            GeneralName[] arr = names.getNames();
            for (int i = 0; i < arr.length; i++) {
                if (arr[i].getTagNo() == GeneralName.dNSName) {
                    ASN1Encodable n = arr[i].getName();
                    if (n instanceof ASN1String) {
                        out.add(((ASN1String) n).getString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new String[out.size()]);
    }

    private static String describeSan(Certificate cert) {
        String[] dns = dnsNames(cert);
        if (dns.length == 0) {
            return "(无 SAN)";
        }
        StringBuilder sb = new StringBuilder(96);
        for (int i = 0; i < dns.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(dns[i]);
        }
        return sb.toString();
    }

    /** 主机名匹配：优先 SAN，退到 CN。支持 * 通配（只匹配一层）。 */
    public static boolean hostnameMatches(Certificate cert, String host) {
        if (host == null || host.length() == 0) {
            return false;
        }
        String h = host.toLowerCase(Locale.US);
        String[] dns = dnsNames(cert);
        if (dns.length > 0) {
            for (int i = 0; i < dns.length; i++) {
                if (matchOne(dns[i], h)) {
                    return true;
                }
            }
            return false;
        }
        // 没有 SAN 时退到 CN
        String cn = cnOf(cert.getSubject());
        return cn != null && matchOne(cn, h);
    }

    private static boolean matchOne(String pattern, String host) {
        if (pattern == null) {
            return false;
        }
        String p = pattern.trim().toLowerCase(Locale.US);
        if (p.length() == 0) {
            return false;
        }
        if (p.equals(host)) {
            return true;
        }
        if (p.startsWith("*.")) {
            String suffix = p.substring(1); // ".example.com"
            if (!host.endsWith(suffix)) {
                return false;
            }
            String label = host.substring(0, host.length() - suffix.length());
            // 通配只能匹配一层，且不能为空
            return label.length() > 0 && label.indexOf('.') < 0;
        }
        return false;
    }

    private static String cnOf(X500Name name) {
        try {
            org.spongycastle.asn1.x500.RDN[] cn =
                    name.getRDNs(org.spongycastle.asn1.x500.style.BCStyle.CN);
            if (cn != null && cn.length > 0 && cn[0].getFirst() != null) {
                return org.spongycastle.asn1.x500.style.IETFUtils
                        .valueToString(cn[0].getFirst().getValue());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String fmtDate(Date d) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        return f.format(d);
    }
}
