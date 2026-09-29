import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lichessold.json.Json;
import org.lichessold.net.CertVerifier;
import org.lichessold.net.Http;
import org.lichessold.net.HttpResponse;
import org.lichessold.net.NetException;
import org.lichessold.net.TlsConnection;
import org.lichessold.net.TrustAnchors;
import org.lichessold.util.Log;
import org.lichessold.util.LogSink;

/**
 * 网络栈冒烟测试。在桌面 JVM 上跑，验证的是和手机上**完全相同的代码**
 * （net/ json/ util/ 这三个包没有任何 Android 依赖）。
 *
 * 覆盖：
 *   A. Mozilla CA bundle 解析
 *   B. TLS 1.2 握手 + SNI + 密码套件协商
 *   C. 真实证书链校验（ECDSA 与 RSA 两条验签路径都会走到）
 *   D. 篡改签名必须被拒绝（证明验签不是走过场）
 *   E. 不相关的信任锚必须被拒绝
 *   F. 连接复用：多次请求只握手一次
 *   G. 真实 Lichess API（无需令牌的接口 + 需要鉴权接口的 401）
 *   H. 真实 NDJSON 流式读取（chunked）
 *
 * 用法： java SmokeTest <mozilla-cacert.pem> [fixture-cert1.pem] [fixture-cert2.pem]
 */
public final class SmokeTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Log.setSink(new LogSink() {
            public void write(String line) {
                if (System.getenv("LICHESSOLD_VERBOSE") != null) {
                    System.out.println("      | " + line);
                }
            }
        });

        String mozillaPem = args.length > 0 ? args[0] : "app/assets/cacerts.pem";
        String cert1Pem = args.length > 1 ? args[1] : null;
        String cert2Pem = args.length > 2 ? args[2] : null;

        // ---------------- A
        TrustAnchors mozilla;
        try {
            InputStream in = new FileInputStream(mozillaPem);
            mozilla = TrustAnchors.fromPem(in);
            in.close();
            check("A. 解析 Mozilla CA bundle", mozilla.size() >= 100,
                    "解析出 " + mozilla.size() + " 个根证书");
        } catch (Throwable t) {
            check("A. 解析 Mozilla CA bundle", false, t.toString());
            summary();
            return;
        }

        // ---------------- B + C：真实握手与链校验
        CertVerifier.Result info = null;
        try {
            TlsConnection c = connectWithRetry("lichess.org", mozilla);
            info = c.certInfo();
            String proto = info.protocolVersion;
            String cipher = info.cipherSuite;
            c.close();
            check("B. TLS 1.2 握手 + SNI + 密码套件协商", "TLS 1.2".equals(proto),
                    proto + " / " + cipher);
            check("C. 真实证书链校验通过", info.chainLength >= 3 && info.hostnameMatched,
                    "链长 " + info.chainLength + "，锚=" + info.anchor
                            + "，主机名匹配=" + info.hostnameMatched
                            + "，验签耗时 " + info.verifyMillis + "ms\n"
                            + info.describe());
        } catch (Throwable t) {
            check("B. TLS 1.2 握手 + SNI + 密码套件协商", false, t.toString());
            check("C. 真实证书链校验通过", false, "握手没成功");
            summary();
            return;
        }

        // ---------------- D：篡改签名必须被拒绝
        if (cert1Pem != null && cert2Pem != null) {
            try {
                byte[] der1 = loadDer(cert1Pem);
                byte[] der2 = loadDer(cert2Pem);
                boolean good = CertVerifier.verifyDerSignedBy(der1, der2);

                byte[] tampered = new byte[der1.length];
                System.arraycopy(der1, 0, tampered, 0, der1.length);
                // 翻掉签名字节里的一个 bit（签名在 DER 末尾）
                tampered[tampered.length - 40] ^= 0x01;
                boolean afterTamper = CertVerifier.verifyDerSignedBy(tampered, der2);

                check("D. 篡改签名必须被拒绝", good && !afterTamper,
                        "原签名验过=" + good + "，篡改后验过=" + afterTamper
                                + "（必须为 true / false）");
            } catch (Throwable t) {
                check("D. 篡改签名必须被拒绝", false, t.toString());
            }
        } else {
            System.out.println("[SKIP] D. 篡改签名必须被拒绝（没提供 fixture）");
        }

        // ---------------- E：不相关的信任锚必须被拒绝
        try {
            TrustAnchors wrong = mozilla.only(0);
            boolean rejected = false;
            String detail;
            try {
                TlsConnection c = TlsConnection.connect("lichess.org", 443, 15000, 15000, wrong);
                c.close();
                detail = "本该失败却成功了！锚=" + wrong.cnAt(0);
            } catch (NetException e) {
                rejected = e.getStage() == NetException.STAGE_CERT
                        || e.getStage() == NetException.STAGE_TLS;
                detail = "锚=" + wrong.cnAt(0) + " → " + NetException.stageName(e.getStage())
                        + ": " + firstLine(e.getMessage());
            }
            check("E. 不相关的信任锚必须被拒绝", rejected, detail);
        } catch (Throwable t) {
            check("E. 不相关的信任锚必须被拒绝", false, t.toString());
        }

        // ---------------- F：连接复用
        Http http = new Http(mozilla).setReadTimeout(20000);
        try {
            long t0 = System.currentTimeMillis();
            http.get("/api/account", Http.headers("application/json", null));
            long first = System.currentTimeMillis() - t0;
            long hs1 = http.lastHandshakeMillis();

            t0 = System.currentTimeMillis();
            http.get("/api/account", Http.headers("application/json", null));
            http.get("/api/account", Http.headers("application/json", null));
            long next2 = System.currentTimeMillis() - t0;
            long hs2 = http.lastHandshakeMillis();

            check("F. 连接复用（后续请求不再握手）", hs2 == hs1,
                    "第 1 次请求 " + first + "ms（握手 " + hs1 + "ms）；"
                            + "第 2+3 次合计 " + next2 + "ms（握手仍为 " + hs2 + "ms）");
        } catch (Throwable t) {
            check("F. 连接复用（后续请求不再握手）", false, t.toString());
        }

        // ---------------- G
        try {
            HttpResponse r = http.get("/api/account", Http.headers("application/json", null));
            check("G1. GET /api/account 无令牌 401", r.status == 401,
                    "HTTP " + r.status + " " + clip(r.text(), 90));
        } catch (Throwable t) {
            check("G1. GET /api/account 无令牌 401", false, t.toString());
        }

        try {
            HttpResponse r = http.get("/api/puzzle/daily", Http.headers("application/json", null));
            boolean ok = r.status == 200;
            String detail = "HTTP " + r.status;
            if (ok) {
                Json j = Json.parse(r.text());
                String pid = j.obj("puzzle").str("id", "");
                String fen = j.obj("puzzle").str("fen", "");
                String[] sol = j.obj("puzzle").arr("solution").asStringArray();
                ok = pid.length() > 0 && fen.length() > 0 && sol.length > 0;
                detail = "puzzle=" + pid + " rating=" + j.obj("puzzle").i("rating", 0)
                        + " solution=" + sol.length + " 步 fen=" + clip(fen, 48);
            }
            check("G2. GET /api/puzzle/daily + JSON 解析", ok, detail);
        } catch (Throwable t) {
            check("G2. GET /api/puzzle/daily + JSON 解析", false, t.toString());
        }

        try {
            HttpResponse r = http.get("/api/puzzle/next", Http.headers("application/json", null));
            boolean ok = r.status == 200;
            String detail = "HTTP " + r.status;
            if (ok) {
                Json j = Json.parse(r.text());
                String[] sol = j.obj("puzzle").arr("solution").asStringArray();
                String pgn = j.obj("game").str("pgn", "");
                int ply = j.obj("puzzle").i("initialPly", -1);
                ok = sol.length > 0 && pgn.length() > 0 && ply >= 0;
                detail = "solution=" + sol.length + " 步 initialPly=" + ply
                        + " pgn=" + pgn.split(" ").length + " 步";
            }
            check("G3. GET /api/puzzle/next", ok, detail);
        } catch (Throwable t) {
            check("G3. GET /api/puzzle/next", false, t.toString());
        }

        try {
            HttpResponse r = http.get("/api/user/thibault", Http.headers("application/json", null));
            boolean ok = r.status == 200;
            String detail = "HTTP " + r.status;
            if (ok) {
                Json j = Json.parse(r.text());
                ok = "thibault".equals(j.str("username", ""));
                detail = "username=" + j.str("username", "?")
                        + " blitz=" + j.obj("perfs").obj("blitz").i("rating", -1);
            }
            check("G4. GET /api/user/{name}", ok, detail);
        } catch (Throwable t) {
            check("G4. GET /api/user/{name}", false, t.toString());
        }

        try {
            HttpResponse r = http.get("/api/account/playing", Http.headers("application/json", null));
            check("G5. GET /api/account/playing 无令牌 401", r.status == 401, "HTTP " + r.status);
        } catch (Throwable t) {
            check("G5. GET /api/account/playing 无令牌 401", false, t.toString());
        }

        try {
            Map<String, String> form = new LinkedHashMap<String, String>();
            form.put("level", "1");
            form.put("clock.limit", "60");
            form.put("clock.increment", "0");
            HttpResponse r = http.post("/api/challenge/ai",
                    Http.formHeaders(null), Http.formEncode(form));
            check("G6. POST /api/challenge/ai 无令牌 401", r.status == 401, "HTTP " + r.status);
        } catch (Throwable t) {
            check("G6. POST /api/challenge/ai 无令牌 401", false, t.toString());
        }

        // ---------------- H：NDJSON 流
        try {
            final int[] count = new int[1];
            final String[] sample = new String[1];
            final boolean[] sawFen = new boolean[1];
            http.setReadTimeout(15000);
            http.stream("GET", "/api/tv/feed", Http.headers("application/x-ndjson", null),
                    null, new Http.LineHandler() {
                        public boolean onLine(String line) {
                            count[0]++;
                            if (sample[0] == null) {
                                sample[0] = line;
                            }
                            if (line.indexOf("\"fen\"") >= 0) {
                                sawFen[0] = true;
                            }
                            return count[0] < 3;
                        }
                    });
            check("H. NDJSON 流式读取（chunked + 逐行）", count[0] > 0,
                    "读到 " + count[0] + " 行，含 fen=" + sawFen[0]
                            + "，首行=" + clip(sample[0], 120));
        } catch (Throwable t) {
            check("H. NDJSON 流式读取（chunked + 逐行）", false, t.toString());
        }

        http.close();
        summary();
    }

    // ------------------------------------------------------------------ 工具

    private static TlsConnection connectWithRetry(String host, TrustAnchors anchors)
            throws NetException {
        NetException last = null;
        for (int i = 0; i < 4; i++) {
            try {
                return TlsConnection.connect(host, 443, 15000, 15000, anchors);
            } catch (NetException e) {
                last = e;
                try {
                    Thread.sleep(400L * (i + 1));
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw last;
    }

    private static byte[] loadDer(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        byte[] der = TrustAnchors.readFirstPemCertificate(in);
        in.close();
        return der;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            bos.write(b, 0, n);
        }
        return bos.toByteArray();
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (detail != null && detail.length() > 0) {
            System.out.println("       " + detail.replace("\n", "\n       "));
        }
    }

    private static void summary() {
        System.out.println();
        System.out.println("================================");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("================================");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "null";
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
