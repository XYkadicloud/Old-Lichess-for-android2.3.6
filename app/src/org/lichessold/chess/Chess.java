package org.lichessold.chess;

/**
 * 棋盘常量与工具方法。
 *
 * 棋盘用 0x88 布局：索引 = rank * 16 + file。
 *   a1 = 0, h1 = 7, a8 = 112, h8 = 119
 * 越界判定就是 (sq & 0x88) != 0，不需要边界检查表，在 ARM11 上够快。
 *
 * 棋子编码：白正黑负，绝对值是类型。
 *   +1 白兵, +2 白马, +3 白象, +4 白车, +5 白后, +6 白王
 *   -1 黑兵, ... , -6 黑王
 */
public final class Chess {

    public static final int EMPTY = 0;
    public static final int PAWN = 1;
    public static final int KNIGHT = 2;
    public static final int BISHOP = 3;
    public static final int ROOK = 4;
    public static final int QUEEN = 5;
    public static final int KING = 6;

    public static final int WHITE = 0;
    public static final int BLACK = 1;

    /** 升变候选顺序（后、车、象、马），界面按这个顺序排。 */
    public static final int[] PROMOTIONS = { QUEEN, ROOK, BISHOP, KNIGHT };

    // 方向偏移（0x88）
    public static final int[] KNIGHT_OFFSETS = { 31, 33, 18, 14, -31, -33, -18, -14 };
    public static final int[] KING_OFFSETS = { 16, -16, 1, -1, 15, 17, -15, -17 };
    public static final int[] BISHOP_OFFSETS = { 15, 17, -15, -17 };
    public static final int[] ROOK_OFFSETS = { 16, -16, 1, -1 };

    /** 兵的前进方向与起始横线。 */
    public static final int[] PAWN_PUSH = { 16, -16 };
    public static final int[] PAWN_START_RANK = { 1, 6 };   // rank 索引（0 基）
    public static final int[] PAWN_PROMO_RANK = { 7, 0 };

    public static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private Chess() {
    }

    public static boolean onBoard(int sq) {
        return (sq & 0x88) == 0;
    }

    public static int file(int sq) {
        return sq & 7;
    }

    public static int rank(int sq) {
        return sq >> 4;
    }

    public static int square(int file, int rank) {
        return rank * 16 + file;
    }

    public static int type(int piece) {
        return piece < 0 ? -piece : piece;
    }

    public static int colorOf(int piece) {
        return piece > 0 ? WHITE : BLACK;
    }

    /** 白棋为正，黑棋为负。 */
    public static int signOf(int color) {
        return color == WHITE ? 1 : -1;
    }

    /** 'e2' -> 索引；非法返回 -1。 */
    public static int parseSquare(String s) {
        if (s == null || s.length() < 2) {
            return -1;
        }
        char f = s.charAt(0);
        char r = s.charAt(1);
        if (f < 'a' || f > 'h' || r < '1' || r > '8') {
            return -1;
        }
        return square(f - 'a', r - '1');
    }

    /** 索引 -> 'e2'。 */
    public static String squareName(int sq) {
        if (!onBoard(sq)) {
            return "-";
        }
        return "" + (char) ('a' + file(sq)) + (char) ('1' + rank(sq));
    }

    /** 单字符转棋子类型（大写=白）。返回 0 表示不认识的字符。 */
    public static int pieceFromChar(char c) {
        switch (c) {
            case 'P':
                return PAWN;
            case 'N':
                return KNIGHT;
            case 'B':
                return BISHOP;
            case 'R':
                return ROOK;
            case 'Q':
                return QUEEN;
            case 'K':
                return KING;
            case 'p':
                return -PAWN;
            case 'n':
                return -KNIGHT;
            case 'b':
                return -BISHOP;
            case 'r':
                return -ROOK;
            case 'q':
                return -QUEEN;
            case 'k':
                return -KING;
            default:
                return 0;
        }
    }

    /** 棋子转 FEN 字符。 */
    public static char charOf(int piece) {
        char c;
        switch (type(piece)) {
            case PAWN:
                c = 'p';
                break;
            case KNIGHT:
                c = 'n';
                break;
            case BISHOP:
                c = 'b';
                break;
            case ROOK:
                c = 'r';
                break;
            case QUEEN:
                c = 'q';
                break;
            case KING:
                c = 'k';
                break;
            default:
                return '.';
        }
        return piece > 0 ? Character.toUpperCase(c) : c;
    }

    /** 棋子类型 -> 大写字母（用于 SAN 里表示棋子）。 */
    public static char sanLetter(int type) {
        switch (type) {
            case KNIGHT:
                return 'N';
            case BISHOP:
                return 'B';
            case ROOK:
                return 'R';
            case QUEEN:
                return 'Q';
            case KING:
                return 'K';
            default:
                return 'P';
        }
    }
}
