package org.lichessold.desktop;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;

import org.lichessold.chess.Ai;
import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.GameStatus;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;
import org.lichessold.chess.Pgn;
import org.lichessold.chess.San;
import org.lichessold.net.ChallengeOptions;
import org.lichessold.net.SeekOptions;
import org.lichessold.net.TrustAnchors;

/**
 * 无界面自检。构建脚本最后会跑它，用来确认"这个桌面版是好的"。
 *
 * 为什么需要它：桌面版复用的是手机版的内核，一旦哪次改动把内核改坏了，
 * 光靠"界面能打开"是发现不了的 —— 界面能开，但走子规则错、SAN 记错、
 * 棋子画不出来，都要下几步棋才暴露。这里把所有能离线验证的东西一次跑完，
 * 任何一项失败就让构建失败。
 *
 * 检查项：
 *   1. 棋子矢量数据完整（12 枚 / 70 子路径 / 坐标在 0..1 内）
 *   2. 走子生成 perft(1..4) = 20 / 400 / 8902 / 197281
 *   3. FEN 往返
 *   4. SAN 生成与解析
 *   5. PGN 重放（谜题靠它重建局面）
 *   6. 将死判定（学者杀）
 *   7. 引擎能给出合法走子
 *   8. 时间档表全部合法（seek / challenge 的单位陷阱）
 *   9. 信任锚能加载
 */
public final class SelfCheck {

    private static int passed;
    private static int failed;

    private SelfCheck() {
    }

    public static void main(String[] args) {
        System.out.println("lichess desktop 自检  " + DesktopVersion.full());
        System.out.println("---------------------------------------------");

        checkPieces();
        checkBoardRender();
        checkPerft();
        checkFenRoundTrip();
        checkSan();
        checkPgn();
        checkCheckmate();
        checkEngine();
        checkTimeTables();
        checkTrust();

        System.out.println("---------------------------------------------");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("自检失败");
            System.exit(1);
        }
        System.out.println("自检全部通过");
    }

    /**
     * 离屏渲染一张开局棋盘，确认棋子/棋盘真的画得出来。
     *
     * 为什么要这一步：棋子造型是"生成的数据 + 手写的绘制代码"，
     * 数据错了（比如命令流错位）在 Java 侧只表现为异常或一片空白，
     * 而界面能打开、日志干净 —— 光看启动是发现不了的。
     * 这里直接数像素：必须有浅格色、深格色、近黑、近白四种颜色。
     */
    private static void checkBoardRender() {
        try {
            BoardPanel bp = new BoardPanel();
            bp.setBoard(new Board());
            bp.setSize(480, 480);

            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    480, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            bp.paint(g);
            g.dispose();

            int light = 0;
            int dark = 0;
            int nearBlack = 0;
            int nearWhite = 0;
            for (int y = 0; y < 480; y += 2) {
                for (int x = 0; x < 480; x += 2) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    if (rgb == 0xF0D9B5) {
                        light++;
                    } else if (rgb == 0xB58863) {
                        dark++;
                    } else if ((rgb & 0xFF) < 40 && ((rgb >> 8) & 0xFF) < 40
                            && ((rgb >> 16) & 0xFF) < 40) {
                        nearBlack++;
                    } else if ((rgb & 0xFF) > 220 && ((rgb >> 8) & 0xFF) > 220
                            && ((rgb >> 16) & 0xFF) > 220) {
                        nearWhite++;
                    }
                }
            }

            boolean ok = light > 500 && dark > 500 && nearBlack > 200 && nearWhite > 200;
            report("棋盘离屏渲染", ok, "480x480 像素采样：浅格 " + light
                    + " / 深格 " + dark + " / 深色棋子 " + nearBlack
                    + " / 浅色棋子 " + nearWhite
                    + (ok ? "（32 枚棋子都在）" : "（颜色不全，棋子可能没画出来）"));
        } catch (Throwable t) {
            report("棋盘离屏渲染", false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 各项

    private static void checkPieces() {
        final int expectedPieces = 12;
        final int expectedSubpaths = 70;

        int pieces = PieceArt2D.pieceCount();
        int subs = 0;
        int minBad = 0;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;

        for (int p = 0; p < pieces; p++) {
            int n = PieceArt2D.count(p);
            subs += n;
            for (int i = 0; i < n; i++) {
                Path2D.Float path = PieceArt2D.path(p, i);
                if (path == null) {
                    minBad++;
                    continue;
                }
                double[] c = new double[6];
                for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
                    int type = it.currentSegment(c);
                    int count = type == PathIterator.SEG_CUBICTO ? 6
                            : (type == PathIterator.SEG_QUADTO ? 4
                            : (type == PathIterator.SEG_CLOSE ? 0 : 2));
                    for (int k = 0; k + 1 < count; k += 2) {
                        if (c[k] < lo) {
                            lo = (float) c[k];
                        }
                        if (c[k] > hi) {
                            hi = (float) c[k];
                        }
                        if (c[k + 1] < lo) {
                            lo = (float) c[k + 1];
                        }
                        if (c[k + 1] > hi) {
                            hi = (float) c[k + 1];
                        }
                    }
                }
            }
        }

        boolean ok = pieces == expectedPieces && subs == expectedSubpaths
                && minBad == 0 && lo > -0.1f && hi < 1.1f;
        report("棋子矢量数据", ok, pieces + " 枚 / " + subs + " 子路径 / 坐标范围 ["
                + fmt(lo) + ", " + fmt(hi) + "]"
                + (minBad > 0 ? " / " + minBad + " 个空路径" : ""));
    }

    private static void checkPerft() {
        long[] expect = { 1, 20, 400, 8902, 197281 };
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (int d = 1; d <= 4; d++) {
            long n = perft(new Board(), d);
            sb.append("perft(").append(d).append(")=").append(n);
            if (n != expect[d]) {
                ok = false;
                sb.append("(应为 ").append(expect[d]).append(")");
            }
            if (d < 4) {
                sb.append("  ");
            }
        }
        report("走子生成 perft", ok, sb.toString());
    }

    private static long perft(Board b, int depth) {
        if (depth == 0) {
            return 1;
        }
        int[] moves = MoveGen.SHARED.bufferAt(b.ply());
        int n = MoveGen.SHARED.legal(b, moves);
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

    private static void checkFenRoundTrip() {
        String[] fens = {
                Chess.START_FEN,
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        };
        boolean ok = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fens.length; i++) {
            Board b = new Board();
            if (!b.setFen(fens[i])) {
                ok = false;
                sb.append("第 ").append(i).append(" 条解析失败; ");
                continue;
            }
            String back = b.fen();
            if (!fens[i].equals(back)) {
                ok = false;
                sb.append("第 ").append(i).append(" 条往返不一致: ").append(back).append("; ");
            }
        }
        report("FEN 往返", ok, ok ? fens.length + " 条局面全部一致" : sb.toString());
    }

    private static void checkSan() {
        Board b = new Board();
        String[] uci = { "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6" };
        String[] want = { "e4", "e5", "Nf3", "Nc6", "Bb5", "a6" };
        boolean ok = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uci.length; i++) {
            int mv = MoveGen.SHARED.findByUci(b, uci[i]);
            if (mv == Move.NONE) {
                ok = false;
                sb.append("找不到 ").append(uci[i]).append("; ");
                break;
            }
            String san = San.toSan(b, mv);
            if (!want[i].equals(san)) {
                ok = false;
                sb.append(uci[i]).append(" -> ").append(san)
                        .append("(应为 ").append(want[i]).append("); ");
            }
            b.make(mv);
        }
        report("SAN 生成", ok, ok ? "西班牙开局 6 步记谱正确" : sb.toString());

        // 反向：解析 SAN
        Board b2 = new Board();
        boolean ok2 = true;
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < want.length; i++) {
            int mv = San.parse(b2, want[i]);
            if (mv == Move.NONE) {
                ok2 = false;
                sb2.append("解析不出 ").append(want[i]).append("; ");
                break;
            }
            if (!Move.toUci(mv).equals(uci[i])) {
                ok2 = false;
                sb2.append(want[i]).append(" -> ").append(Move.toUci(mv))
                        .append("(应为 ").append(uci[i]).append("); ");
            }
            b2.make(mv);
        }
        report("SAN 解析", ok2, ok2 ? "6 步全部还原成正确 UCI" : sb2.toString());
    }

    private static void checkPgn() {
        // 谜题用的就是这条路：/api/puzzle/next 只给 PGN，没有 fen
        String pgn = "[Event \"Test\"]\n[Result \"1-0\"]\n\n"
                + "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 1-0";
        Board b = new Board();
        int applied = Pgn.replay(b, pgn, 10);
        // 走完 10 个半回合：白方刚走完 O-O，黑方 Be7，接下来该白走，
        // 回合数 6；半回合计数从 a6 之后算起共 4 步。
        String wantFen =
                "r1bqk2r/1pppbppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQ1RK1 w kq - 4 6";
        boolean ok = applied == 10 && wantFen.equals(b.fen());
        report("PGN 重放", ok, ok ? "重放 " + applied + " 步，局面与预期一致"
                : "只重放了 " + applied + " 步，得到 " + b.fen());

        String[] uciList = Pgn.toUciList("1. d4 d5 2. c4 e6");
        boolean ok2 = uciList.length == 4
                && "d2d4".equals(uciList[0]) && "d7d5".equals(uciList[1])
                && "c2c4".equals(uciList[2]) && "e7e6".equals(uciList[3]);
        report("PGN 转 UCI", ok2, ok2 ? "4 步正确" : java.util.Arrays.toString(uciList));
    }

    private static void checkCheckmate() {
        // 学者杀：1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6 4.Qxf7#
        Board b = new Board();
        String[] moves = { "e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7" };
        for (int i = 0; i < moves.length; i++) {
            int mv = MoveGen.SHARED.findByUci(b, moves[i]);
            if (mv == Move.NONE) {
                report("将死判定", false, "第 " + (i + 1) + " 步 " + moves[i] + " 找不到");
                return;
            }
            b.make(mv);
        }
        int status = GameStatus.of(b, MoveGen.SHARED);
        boolean ok = status == GameStatus.CHECKMATE;
        report("将死判定", ok, GameStatus.describe(status, b.side)
                + "（status=" + status + "）");

        // 逼和：经典局面
        Board stale = new Board();
        stale.setFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        int st2 = GameStatus.of(stale, MoveGen.SHARED);
        boolean ok2 = st2 == GameStatus.STALEMATE;
        report("逼和判定", ok2, GameStatus.describe(st2, stale.side));

        // 子力不足
        Board insuf = new Board();
        insuf.setFen("8/8/4k3/8/8/3BK3/8/8 w - - 0 1");
        boolean ok3 = GameStatus.isInsufficientMaterial(insuf);
        report("子力不足判定", ok3, ok3 ? "王+象 vs 王 判为和棋" : "漏判了");
    }

    private static void checkEngine() {
        Board b = new Board();
        Ai ai = new Ai();
        long t0 = System.currentTimeMillis();
        Ai.Result r = ai.search(b, 4, 1500);
        long ms = System.currentTimeMillis() - t0;

        if (r.move == Move.NONE) {
            report("离线引擎", false, "开局没有返回走子");
            return;
        }
        // 返回的走子必须真的合法
        int[] legal = MoveGen.SHARED.bufferAt(0);
        int n = MoveGen.SHARED.legal(b, legal);
        boolean legalOk = false;
        for (int i = 0; i < n; i++) {
            if (legal[i] == r.move) {
                legalOk = true;
                break;
            }
        }
        report("离线引擎", legalOk, "开局走 " + Move.toUci(r.move)
                + "，深度 " + r.depth + "，节点 " + r.nodes + "，耗时 " + ms + "ms"
                + "（约 " + (r.nodes * 1000L / Math.max(1, ms)) + " 节点/秒）");

        // 一步杀必须能找到
        Board mate = new Board();
        mate.setFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        Ai.Result r2 = new Ai().search(mate, 4, 2000);
        boolean ok2 = r2.move != Move.NONE && "a1a8".equals(Move.toUci(r2.move));
        report("引擎找杀", ok2, ok2 ? "找到 Ra8#（" + r2.describe() + "）"
                : "没找到 Ra8#，走了 " + Move.toUci(r2.move));
    }

    private static void checkTimeTables() {
        int badSeek = SeekOptions.firstBadIndex();
        report("找对手时间档", badSeek < 0, badSeek < 0
                ? SeekOptions.count() + " 档全部合法（均 >= 480 秒，满足 Board API 的 Rapid 下限）"
                : "第 " + badSeek + " 档不合法");

        int badChallenge = ChallengeOptions.firstBadIndex();
        report("挑战时间档", badChallenge < 0, badChallenge < 0
                ? ChallengeOptions.count() + " 档全部合法"
                : "第 " + badChallenge + " 档不合法");
    }

    private static void checkTrust() {
        TrustAnchors anchors = DesktopTrust.get();
        if (anchors == null) {
            report("信任锚", false, DesktopTrust.lastError());
            return;
        }
        boolean ok = anchors.size() >= 50;
        report("信任锚", ok, anchors.size() + " 个根证书，来源 " + DesktopTrust.source());
    }

    // ------------------------------------------------------------------ 工具

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [通过] " + name + "  —— " + detail);
        } else {
            failed++;
            System.out.println("  [失败] " + name + "  —— " + detail);
        }
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    /** 供外部调用：跑一遍自检并返回是否全部通过（界面上想加"自检"入口时用它）。 */
    public static boolean runQuiet() {
        passed = 0;
        failed = 0;
        checkPieces();
        checkBoardRender();
        checkPerft();
        checkFenRoundTrip();
        checkSan();
        checkPgn();
        checkCheckmate();
        checkEngine();
        checkTimeTables();
        checkTrust();
        return failed == 0;
    }
}
