package org.lichessold.chess;

/**
 * 走子编码。用一个 int 表示，避免每个走子分配对象（ARM11 上 GC 抖动很致命）。
 *
 * 位布局：
 *   0- 7  from（0x88 索引）
 *   8-15  to
 *  16-18  升变棋子类型（0 = 不升变）
 *  19-24  标志位
 */
public final class Move {

    public static final int FLAG_CAPTURE = 1 << 19;
    public static final int FLAG_EP = 1 << 20;
    public static final int FLAG_CASTLE_K = 1 << 21;
    public static final int FLAG_CASTLE_Q = 1 << 22;
    public static final int FLAG_DOUBLE_PUSH = 1 << 23;
    public static final int FLAG_PROMOTION = 1 << 24;

    public static final int NONE = 0;

    private Move() {
    }

    public static int of(int from, int to) {
        return (from & 0xFF) | ((to & 0xFF) << 8);
    }

    public static int of(int from, int to, int promo) {
        return (from & 0xFF) | ((to & 0xFF) << 8) | ((promo & 0x7) << 16);
    }

    public static int withFlag(int move, int flag) {
        return move | flag;
    }

    public static int from(int move) {
        return move & 0xFF;
    }

    public static int to(int move) {
        return (move >> 8) & 0xFF;
    }

    public static int promo(int move) {
        return (move >> 16) & 0x7;
    }

    public static boolean has(int move, int flag) {
        return (move & flag) != 0;
    }

    /** 转 UCI，例如 e2e4 / e7e8q。Lichess Board API 要的就是这个格式。 */
    public static String toUci(int move) {
        StringBuilder sb = new StringBuilder(5);
        sb.append(Chess.squareName(from(move)));
        sb.append(Chess.squareName(to(move)));
        int p = promo(move);
        if (p != 0) {
            // UCI 里升变字母必须是小写（e7e8q），大写会让 Lichess 拒绝走子
            sb.append(Character.toLowerCase(Chess.sanLetter(p)));
        }
        return sb.toString();
    }

    /** 解析 UCI，例如 "e7e8q"。非法返回 NONE。 */
    public static int fromUci(String uci) {
        if (uci == null || uci.length() < 4) {
            return NONE;
        }
        int f = Chess.parseSquare(uci.substring(0, 2));
        int t = Chess.parseSquare(uci.substring(2, 4));
        if (f < 0 || t < 0) {
            return NONE;
        }
        int promo = 0;
        if (uci.length() >= 5) {
            int p = Chess.pieceFromChar(uci.charAt(4));
            promo = p < 0 ? -p : p;
        }
        return of(f, t, promo);
    }

    /** 只比较 from/to/promo，忽略标志位。 */
    public static boolean sameEndpoints(int a, int b) {
        return from(a) == from(b) && to(a) == to(b) && promo(a) == promo(b);
    }

    public static String describe(int move) {
        return toUci(move) + " flags=0x" + Integer.toHexString(move & 0x1FE0000);
    }
}
