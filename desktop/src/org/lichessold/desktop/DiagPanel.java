package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.lichessold.chess.Board;
import org.lichessold.net.CertVerifier;
import org.lichessold.net.Http;
import org.lichessold.net.HttpResponse;
import org.lichessold.net.NetException;
import org.lichessold.net.TlsConnection;
import org.lichessold.net.TrustAnchors;
import org.lichessold.util.Log;

/**
 * 网络诊断页。对应 Android 版的 {@code org.lichessold.ui.DiagActivity}。
 *
 * 逐步骤显示 DNS → TCP → TLS 握手 → 证书链校验 → HTTP 的每一步结果，
 * 每一步都带耗时和关键细节（协议版本、密码套件、证书链每一环的签发者、
 * 信任锚、主机名是否匹配、有效期）。
 *
 * 桌面版比手机版更用得上这一页：如果这里全绿而手机上不通，
 * 问题基本可以锁定在设备侧的 TLS 栈或系统时间上。
 */
public final class DiagPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String HOST = "lichess.org";

    private final JTextArea output = DesktopTheme.output();
    private final JScrollPane scroll;
    private final JButton runButton;
    private final StringBuilder sb = new StringBuilder(4096);
    private boolean running;

    public DiagPanel() {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.setBackground(DesktopTheme.BG);

        runButton = DesktopTheme.smallPrimary("开始诊断");
        runButton.setPreferredSize(new Dimension(100, 30));
        runButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!running) {
                    runDiagnostics();
                }
            }
        });

        JButton save = DesktopTheme.smallButton("保存结果");
        save.setPreferredSize(new Dimension(100, 30));
        save.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                saveResult();
            }
        });

        JButton clear = DesktopTheme.smallButton("清空");
        clear.setPreferredSize(new Dimension(80, 30));
        clear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                sb.setLength(0);
                output.setText("");
            }
        });

        JButton back = DesktopTheme.smallButton("返回");
        back.setPreferredSize(new Dimension(80, 30));
        back.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.back();
            }
        });

        top.add(runButton);
        top.add(save);
        top.add(clear);
        top.add(back);

        scroll = DesktopTheme.scroll(output);

        // 标题栏 + 按钮钉在顶部，输出区吃掉剩余空间
        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(DesktopTheme.BG);
        north.add(DesktopTheme.header("网络诊断"), BorderLayout.NORTH);
        north.add(top, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        line("点「开始诊断」逐项检查与 " + HOST + " 的连接。");
        line("每一步都会显示结果和耗时。失败时会说明卡在哪一步。");
        line("");
    }

    // -------------------------------------------------------------- 诊断流程

    private void runDiagnostics() {
        running = true;
        runButton.setEnabled(false);
        sb.setLength(0);
        output.setText("");
        line("===== 网络诊断开始 =====");
        line("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        line("目标: " + HOST + ":443");
        line("");

        DesktopAsync.run("diag", new DesktopAsync.Job<Boolean>() {
            public Boolean run() {
                return doDiagnostics();
            }
        }, new DesktopAsync.Done<Boolean>() {
            public void done(Boolean ok, Throwable error) {
                running = false;
                runButton.setEnabled(true);
                if (error != null) {
                    line("");
                    line("!! 诊断过程本身出错: " + error);
                }
                line("");
                line("===== 诊断结束 =====");
                line(Boolean.TRUE.equals(ok) ? "结论: 全部通过 ✓" : "结论: 有步骤失败 ✗");
                Log.i("Diag", "诊断结束，结果=" + ok);
            }
        });
    }

    private boolean doDiagnostics() {
        boolean allOk = true;

        // --- 1. 内置根证书
        step("1. 加载内置根证书（assets/cacerts.pem）");
        TrustAnchors anchors = DesktopTrust.get();
        if (anchors == null) {
            fail("加载失败: " + DesktopTrust.lastError());
            return false;
        }
        ok("共 " + anchors.size() + " 个根证书，来源 " + DesktopTrust.source());

        // --- 2. DNS
        step("2. DNS 解析 " + HOST);
        long t0 = System.currentTimeMillis();
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(HOST);
            StringBuilder b = new StringBuilder(64);
            for (int i = 0; i < addrs.length; i++) {
                if (i > 0) {
                    b.append(", ");
                }
                b.append(addrs[i].getHostAddress());
            }
            ok(b.toString() + "   (" + (System.currentTimeMillis() - t0) + "ms)");
        } catch (Throwable e) {
            fail("解析失败: " + e);
            line("→ 检查这台机器是否联网、DNS 是否可用。");
            return false;
        }

        // --- 3~6. TLS + 证书链
        step("3. TCP 连接 + TLS 握手（自带 TLS 1.2，显式发送 SNI）");
        CertVerifier.Result cert;
        try {
            t0 = System.currentTimeMillis();
            TlsConnection c = TlsConnection.connect(HOST, 443, 20000, 20000, anchors);
            long hs = System.currentTimeMillis() - t0;
            cert = c.certInfo();
            ok("握手成功，耗时 " + hs + "ms");
            line("   协议版本: " + cert.protocolVersion);
            line("   密码套件: " + cert.cipherSuite);
            line("   证书校验耗时: " + cert.verifyMillis + "ms");
            c.close();
        } catch (NetException e) {
            fail("[" + NetException.stageName(e.getStage()) + "] " + e.getMessage());
            line("");
            line("→ 失败发生在「" + NetException.stageName(e.getStage()) + "」这一步。");
            if (e.getStage() == NetException.STAGE_CERT) {
                line("→ 证书类失败：先确认这台机器的系统日期时间是否正确。");
            }
            return false;
        } catch (Throwable e) {
            fail("未知错误: " + e);
            return false;
        }

        step("4. 证书链校验结果");
        line("   链长度: " + cert.chainLength);
        for (int i = 0; i < cert.subjects.length; i++) {
            line("   [" + i + "] " + cert.subjects[i] + "  ← 签发者 " + cert.issuers[i]);
        }
        line("   信任锚: " + cert.anchor);
        line("   主机名: " + cert.hostname + "  "
                + (cert.hostnameMatched ? "匹配 ✓" : "不匹配 ✗"));
        line("   有效期: " + cert.notBefore + " ~ " + cert.notAfter);
        if (!cert.hostnameMatched) {
            allOk = false;
            line("   !! 主机名不匹配，这不应该发生");
        } else {
            ok("证书链完整且受信任");
        }

        // --- 7. HTTP 无令牌
        step("5. HTTP 请求（不带令牌）GET /api/account");
        Http http;
        try {
            http = new Http(anchors).setReadTimeout(20000);
            http.setUserAgent(DesktopNet.USER_AGENT);
        } catch (Throwable e) {
            fail("创建 HTTP 客户端失败: " + e);
            return false;
        }
        try {
            t0 = System.currentTimeMillis();
            HttpResponse r = http.get("/api/account",
                    Http.headers("application/json", null));
            long ms = System.currentTimeMillis() - t0;
            if (r.status == 401) {
                ok("HTTP 401（符合预期：没有令牌就该被拒绝）   " + ms + "ms");
                line("   响应: " + clip(r.text(), 120));
            } else {
                line("   HTTP " + r.status + "（期望 401）   " + ms + "ms");
                line("   响应: " + clip(r.text(), 200));
                if (r.status == 200) {
                    ok("居然直接返回了 200，说明令牌可能是全局共享的");
                } else {
                    allOk = false;
                    fail("状态码不是 401，可能是 CDN 或中间设备在干扰");
                }
            }
        } catch (Throwable e) {
            allOk = false;
            fail("请求失败: " + e);
        }

        // --- 8. 公开接口
        step("6. HTTP 请求（公开接口）GET /api/puzzle/daily");
        try {
            t0 = System.currentTimeMillis();
            HttpResponse r = http.get("/api/puzzle/daily",
                    Http.headers("application/json", null));
            long ms = System.currentTimeMillis() - t0;
            if (r.isOk()) {
                ok("HTTP 200   " + ms + "ms，响应 " + r.body.length + " 字节");
            } else {
                allOk = false;
                fail("HTTP " + r.status + " " + clip(r.text(), 150));
            }
        } catch (Throwable e) {
            allOk = false;
            fail("请求失败: " + e);
        }

        // --- 9. 连接复用
        step("7. 连接复用（第二次请求不应该再握手）");
        try {
            long hs1 = http.lastHandshakeMillis();
            t0 = System.currentTimeMillis();
            http.get("/api/puzzle/daily", Http.headers("application/json", null));
            long ms = System.currentTimeMillis() - t0;
            long hs2 = http.lastHandshakeMillis();
            if (hs2 == hs1) {
                ok("复用成功，第二次请求只花 " + ms + "ms（首次握手 " + hs1 + "ms）");
            } else {
                line("   第二次又握手了（" + hs2 + "ms），可能是服务端要求关闭连接");
            }
        } catch (Throwable e) {
            line("   复用测试失败（不影响使用）: " + e);
        }

        // --- 10. 本地棋规自检
        step("8. 本地棋规自检（走子生成 perft）");
        try {
            t0 = System.currentTimeMillis();
            long n3 = perft(new Board(), 3);
            long n4 = perft(new Board(), 4);
            long ms = System.currentTimeMillis() - t0;
            if (n3 == 8902 && n4 == 197281) {
                ok("perft(3)=8902 perft(4)=197281 正确，耗时 " + ms + "ms");
                line("   推算本机搜索速度约 " + (n4 * 1000L / Math.max(1, ms))
                        + " 节点/秒（用于估算 AI 思考时间）");
            } else {
                allOk = false;
                fail("perft 结果不对: perft(3)=" + n3 + " perft(4)=" + n4);
            }
        } catch (Throwable e) {
            allOk = false;
            fail("棋规自检出错: " + e);
        }

        // --- 11. 桌面环境
        step("9. 运行环境");
        line("   Java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        line("   系统: " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        line("   堆上限: " + (Runtime.getRuntime().maxMemory() / 1024L / 1024L) + " MB");
        line("   数据目录: " + DesktopPaths.dataDir().getAbsolutePath());

        http.close();
        return allOk;
    }

    private static long perft(Board b, int depth) {
        if (depth == 0) {
            return 1;
        }
        int[] moves = org.lichessold.chess.MoveGen.SHARED.bufferAt(b.ply());
        int n = org.lichessold.chess.MoveGen.SHARED.legal(b, moves);
        if (depth == 1) {
            return n;
        }
        long total = 0;
        for (int i = 0; i < n; i++) {
            b.make(moves[i]);
            total += perft(b, depth - 1);
            b.unmake();
        }
        return total;
    }

    // -------------------------------------------------------------- 输出

    private void line(String s) {
        sb.append(s).append('\n');
        DesktopAsync.post(new Runnable() {
            public void run() {
                output.setText(sb.toString());
                output.setCaretPosition(output.getDocument().getLength());
            }
        });
    }

    private void step(String title) {
        line("");
        line("──────────────────────────");
        line("▶ " + title);
    }

    private void ok(String detail) {
        line("   ✓ " + detail);
    }

    private void fail(String detail) {
        line("   ✗ " + detail);
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private void saveResult() {
        File f = new File(DesktopPaths.dataDir(), "diag.txt");
        OutputStreamWriter w = null;
        try {
            FileOutputStream fos = new FileOutputStream(f, false);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(sb.toString());
            w.flush();
            DesktopApp.toast(this, "已保存到\n" + f.getAbsolutePath());
            Log.i("Diag", "诊断结果已保存到 " + f.getAbsolutePath());
        } catch (Throwable e) {
            DesktopApp.toast(this, "保存失败: " + e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
