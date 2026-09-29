import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;

/**
 * 棋规正确性测试。
 *
 * 核心是 **perft**：从给定局面出发，逐层统计叶子节点数（每个叶子 = 一条完整的
 * 合法走子序列）。perft 的数字是国际象棋界公认的基准值，只要每一层都对得上，
 * 就说明走子生成（包括王车易位、吃过路兵、升变、将军/被将限制）是**正确的**，
 * 而不是"看起来对"。这是棋类引擎唯一可信的验证手段。
 *
 * 另外测：FEN 往返、UCI 往返、make/unmake 后局面必须完全复原。
 */
public final class ChessTest {

    private static int pass = 0;
    private static int fail = 0;

    /** 公认的 perft 基准值。 */
    private static final String[] FENS = {
            Chess.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
    };

    private static final String[] NAMES = {
            "起始局面",
            "Kiwipete（易位/复杂局面）",
            "局面3（吃过路兵/残局）",
            "局面4（升变/易位）",
            "局面5（升变压力）",
            "局面6（中局）",
    };

    private static final long[][] EXPECTED = {
            { 20, 400, 8902, 197281, 4865609 },
            { 48, 2039, 97862, 4085603 },
            { 14, 191, 2812, 43238, 674624 },
            { 6, 264, 9467, 422333 },
            { 44, 1486, 62379, 2103487 },
            { 46, 2079, 89890, 3894594 },
    };

    private static final MoveGen GEN = new MoveGen(64);

    public static void main(String[] args) {
        boolean deep = System.getenv("LICHESSOLD_DEEP") != null;

        System.out.println("=== 棋规测试 ===");
        System.out.println();

        testFenRoundTrip();
        testUciRoundTrip();
        testMakeUnmake();
        testInitialPosition();

        System.out.println();
        System.out.println("=== perft（走子生成正确性证明）===");
        System.out.println();

        int maxDepth = deep ? 5 : 4;
        for (int p = 0; p < FENS.length; p++) {
            Board b = new Board();
            if (!b.setFen(FENS[p])) {
                check("FEN 解析: " + NAMES[p], false, FENS[p]);
                continue;
            }
            int limit = Math.min(maxDepth, EXPECTED[p].length);
            // 后几个局面深度降一级，避免在弱机上跑太久
            if (!deep && p >= 3) {
                limit = Math.min(3, EXPECTED[p].length);
            }
            for (int d = 1; d <= limit; d++) {
                long t0 = System.currentTimeMillis();
                long got = perft(b, d);
                long ms = System.currentTimeMillis() - t0;
                long want = EXPECTED[p][d - 1];
                String rate = ms > 0 ? (got * 1000L / ms) + " 节点/秒" : "";
                check(NAMES[p] + " perft(" + d + ")", got == want,
                        "得到 " + got + "，期望 " + want + "（" + ms + "ms, " + rate + "）");
                if (got != want) {
                    break;
                }
            }
        }

        System.out.println();
        summary();
    }

    // ------------------------------------------------------------------ perft

    private static long perft(Board b, int depth) {
        if (depth == 0) {
            return 1;
        }
        int[] moves = GEN.bufferAt(b.ply());
        int n = GEN.legal(b, moves);
        if (depth == 1) {
            return n;
        }
        long nodes = 0;
        int us = b.side;
        for (int i = 0; i < n; i++) {
            b.make(moves[i]);
            // make 之后要保证走子确实合法（双保险，能抓出 make/unmake 的错误）
            if (b.inCheck(us)) {
                System.out.println("  !! 生成了非法走子: " + Move.toUci(moves[i]));
                fail++;
            }
            nodes += perft(b, depth - 1);
            b.unmake();
        }
        return nodes;
    }

    // ------------------------------------------------------------------ 其它

    private static void testFenRoundTrip() {
        for (int i = 0; i < FENS.length; i++) {
            Board b = new Board();
            boolean ok = b.setFen(FENS[i]);
            String out = ok ? b.fen() : "(解析失败)";
            check("FEN 往返: " + NAMES[i], ok && FENS[i].equals(out),
                    ok ? (FENS[i].equals(out) ? out : "输入 " + FENS[i] + " → 输出 " + out)
                            : "解析失败");
        }
    }

    private static void testUciRoundTrip() {
        String[] cases = { "e2e4", "g1f3", "e7e8q", "a7a8n", "h2h1q", "e1g1" };
        for (int i = 0; i < cases.length; i++) {
            int mv = Move.fromUci(cases[i]);
            String back = mv == Move.NONE ? "(解析失败)" : Move.toUci(mv);
            check("UCI 往返: " + cases[i], cases[i].equals(back), "得到 " + back);
        }
    }

    private static void testMakeUnmake() {
        String[] seqs = {
                "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6",
                "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6",
                "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6",
        };
        for (int i = 0; i < seqs.length; i++) {
            Board b = new Board();
            String before = b.fen();
            String[] moves = seqs[i].split(" ");
            int applied = 0;
            for (int k = 0; k < moves.length; k++) {
                int mv = GEN.findByUci(b, moves[k]);
                if (mv == Move.NONE) {
                    break;
                }
                b.make(mv);
                applied++;
            }
            for (int k = 0; k < applied; k++) {
                b.unmake();
            }
            check("make/unmake 复原（第 " + (i + 1) + " 组，" + applied + " 步）",
                    before.equals(b.fen()),
                    before.equals(b.fen()) ? "局面完全复原" : "复原后 " + b.fen());
        }
    }

    private static void testInitialPosition() {
        Board b = new Board();
        int[] moves = new int[MoveGen.MAX_MOVES];
        int n = GEN.legal(b, moves);
        check("起始局面合法走子数 = 20", n == 20, "得到 " + n);

        // e2e4 之后黑方 20 步
        int e4 = GEN.findByUci(b, "e2e4");
        boolean found = e4 != Move.NONE;
        check("能找到 e2e4", found, found ? "OK" : "找不到");
        if (found) {
            b.make(e4);
            int n2 = GEN.legal(b, moves);
            check("1.e4 之后黑方合法走子数 = 20", n2 == 20, "得到 " + n2);
            b.unmake();
        }

        // 起始局面不是将军、不是将死
        check("起始局面无将军", !b.inCheck(Chess.WHITE) && !b.inCheck(Chess.BLACK), "OK");
    }

    // ------------------------------------------------------------------ 工具

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (!ok && detail != null) {
            System.out.println("       " + detail);
        }
    }

    private static void summary() {
        System.out.println("================================");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("================================");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
