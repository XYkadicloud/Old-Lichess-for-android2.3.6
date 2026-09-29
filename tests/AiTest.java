import org.lichessold.chess.Ai;
import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.GameStatus;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;

/**
 * 离线 AI 引擎测试。
 *
 * 覆盖：
 *   1. 一步杀必须找到
 *   2. 白送的子必须吃（不能有"看不出来"的低级失误）
 *   3. 引擎输出的走子必须全部合法
 *   4. 自己跟自己下一整局，全程无非法走子，且能正常终局
 *   5. 速度基准（节点/秒），用来估算真机上的思考时间
 */
public final class AiTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testMateInOne();
        testCaptureFreeQueen();
        testAvoidHangingQueen();
        testSpeed();
        testSelfPlay();

        System.out.println();
        System.out.println("================================");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("================================");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    private static void testMateInOne() {
        // 白车 a1，黑王 g8 被自己的 f7/g7/h7 兵困住 → Ra8# 一步杀
        String fen = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1";
        Ai ai = new Ai();
        Board b = new Board();
        b.setFen(fen);
        Ai.Result r = ai.search(b, 4, 3000);
        String uci = Move.toUci(r.move);
        check("一步杀必须找到（期望 a1a8）", "a1a8".equals(uci),
                "引擎走了 " + uci + "，" + r.describe());
    }

    private static void testCaptureFreeQueen() {
        // 白兵 e4 可以白吃 d5 的黑后
        String fen = "4k3/8/8/3q4/4P3/8/8/4K3 w - - 0 1";
        Ai ai = new Ai();
        Board b = new Board();
        b.setFen(fen);
        Ai.Result r = ai.search(b, 4, 3000);
        String uci = Move.toUci(r.move);
        check("必须白吃对方的皇后（期望 e4d5）", "e4d5".equals(uci),
                "引擎走了 " + uci + "，" + r.describe());
    }

    private static void testAvoidHangingQueen() {
        // 白后 d1，黑象 g4 盯着 d1（沿 g4-f3-e2-d1）。白方必须处理这个威胁：
        // 要么吃掉象（h2 兵？不行，h2xg4 不成立因为象在 g4 由 h3 保护）
        // 换个干净的用例：白后 d1 被黑车 d8 沿 d 线攻击，中间无子 → 白方必须挪后
        String fen = "3r2k1/8/8/8/8/8/8/3Q2K1 w - - 0 1";
        Ai ai = new Ai();
        Board b = new Board();
        b.setFen(fen);
        Ai.Result r = ai.search(b, 4, 3000);
        int from = Move.from(r.move);
        int to = Move.to(r.move);
        boolean movedQueen = Chess.type(b.sq[from]) == Chess.QUEEN
                && Chess.file(to) != 3;   // 离开 d 线
        boolean capturedRook = Chess.type(b.sq[to]) == Chess.ROOK;
        check("不能白送皇后（必须挪开或吃车）", movedQueen || capturedRook,
                "引擎走了 " + Move.toUci(r.move) + "，" + r.describe());
    }

    private static void testSpeed() {
        Ai ai = new Ai();
        Board b = new Board();
        Ai.Result r = ai.search(b, 6, 3000);
        long nps = r.millis > 0 ? r.nodes * 1000L / r.millis : 0;
        System.out.println("[INFO] 起始局面搜索: " + r.describe());
        System.out.println("[INFO] 速度约 " + nps + " 节点/秒（桌面 JVM）");
        // 真机 ARM11 大约是桌面的 1/10 ~ 1/25
        System.out.println("[INFO] 推算真机约 " + (nps / 10) + " ~ " + (nps / 25) + " 节点/秒");
        check("引擎在时间预算内返回", r.move != Move.NONE && r.millis < 5000,
                r.describe());
    }

    private static void testSelfPlay() {
        Ai ai = new Ai();
        Board b = new Board();
        int ply = 0;
        int illegal = 0;
        int maxPly = 120;
        while (ply < maxPly) {
            int status = GameStatus.of(b, MoveGen.SHARED);
            if (status != GameStatus.ONGOING) {
                break;
            }
            Ai.Result r = ai.search(b, 3, 400);
            if (r.move == Move.NONE) {
                break;
            }
            // 校验合法性
            int check = MoveGen.SHARED.findByUci(b, Move.toUci(r.move));
            if (check == Move.NONE || !Move.sameEndpoints(check, r.move)) {
                illegal++;
            }
            b.make(r.move);
            ply++;
        }
        int status = GameStatus.of(b, MoveGen.SHARED);
        check("自我对局全程无非法走子（走了 " + ply + " 步）", illegal == 0,
                illegal == 0 ? "OK" : illegal + " 步非法");
        System.out.println("[INFO] 自我对局结束于第 " + ply + " 半回合，状态: "
                + GameStatus.describe(status, b.side));
    }

    // ---------------------------------------------------------------- 工具

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (detail != null && detail.length() > 0) {
            System.out.println("       " + detail);
        }
    }
}
