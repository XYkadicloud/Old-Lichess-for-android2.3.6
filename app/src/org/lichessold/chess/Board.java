package org.lichessold.chess;

/**
 * 棋盘与局面状态。0x88 布局，走子用 make/unmake（不复制棋盘）。
 *
 * 所有状态都在固定长度的数组里，走子过程零对象分配 —— ARM11 上 GC 抖动
 * 比算得慢更致命。
 */
public final class Board {

    public static final int A1 = 0;
    public static final int E1 = 4;
    public static final int H1 = 7;
    public static final int A8 = 112;
    public static final int E8 = 116;
    public static final int H8 = 119;

    public static final int CASTLE_WK = 1;
    public static final int CASTLE_WQ = 2;
    public static final int CASTLE_BK = 4;
    public static final int CASTLE_BQ = 8;

    private static final int MAX_PLY = 512;

    /** 0x88 棋盘，0 = 空。 */
    public final int[] sq = new int[128];
    public int side;
    public int castling;
    public int ep;              // -1 表示无
    public int halfmove;
    public int fullmove;
    public int kingW = -1;
    public int kingB = -1;

    /** 走子历史（UCI 用不到，但 PGN 和重复局面判定要用）。 */
    public final int[] history = new int[MAX_PLY * 2];
    public int historySize;

    // ---- undo 栈
    private final int[] uCaptured = new int[MAX_PLY * 2];
    private final int[] uCastling = new int[MAX_PLY * 2];
    private final int[] uEp = new int[MAX_PLY * 2];
    private final int[] uHalfmove = new int[MAX_PLY * 2];
    private final int[] uKingW = new int[MAX_PLY * 2];
    private final int[] uKingB = new int[MAX_PLY * 2];
    private final int[] uFullmove = new int[MAX_PLY * 2];
    private int top;

    /** 走到某个格子上需要清掉哪些易位权。 */
    private static final int[] CASTLE_MASK = new int[128];

    static {
        for (int i = 0; i < 128; i++) {
            CASTLE_MASK[i] = 0xF;
        }
        CASTLE_MASK[A1] &= ~CASTLE_WQ;
        CASTLE_MASK[H1] &= ~CASTLE_WK;
        CASTLE_MASK[E1] &= ~(CASTLE_WK | CASTLE_WQ);
        CASTLE_MASK[A8] &= ~CASTLE_BQ;
        CASTLE_MASK[H8] &= ~CASTLE_BK;
        CASTLE_MASK[E8] &= ~(CASTLE_BK | CASTLE_BQ);
    }

    public Board() {
        setStart();
    }

    public void setStart() {
        setFen(Chess.START_FEN);
    }

    // ------------------------------------------------------------------ FEN

    public boolean setFen(String fen) {
        if (fen == null) {
            return false;
        }
        for (int i = 0; i < 128; i++) {
            sq[i] = 0;
        }
        kingW = -1;
        kingB = -1;
        castling = 0;
        ep = -1;
        halfmove = 0;
        fullmove = 1;
        side = Chess.WHITE;
        top = 0;
        historySize = 0;

        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 2) {
            return false;
        }

        // 1) 棋子摆放
        int rank = 7;
        int file = 0;
        String rows = parts[0];
        for (int i = 0; i < rows.length(); i++) {
            char c = rows.charAt(i);
            if (c == '/') {
                rank--;
                file = 0;
                if (rank < 0) {
                    break;
                }
                continue;
            }
            if (c >= '1' && c <= '8') {
                file += (c - '0');
                continue;
            }
            int piece = Chess.pieceFromChar(c);
            if (piece == 0 || file > 7 || rank < 0) {
                return false;
            }
            int s = Chess.square(file, rank);
            sq[s] = piece;
            if (piece == Chess.KING) {
                kingW = s;
            } else if (piece == -Chess.KING) {
                kingB = s;
            }
            file++;
        }
        if (kingW < 0 || kingB < 0) {
            return false;
        }

        // 2) 行棋方
        side = "b".equals(parts[1]) ? Chess.BLACK : Chess.WHITE;

        // 3) 易位权
        if (parts.length > 2 && !"-".equals(parts[2])) {
            String cs = parts[2];
            for (int i = 0; i < cs.length(); i++) {
                switch (cs.charAt(i)) {
                    case 'K':
                        castling |= CASTLE_WK;
                        break;
                    case 'Q':
                        castling |= CASTLE_WQ;
                        break;
                    case 'k':
                        castling |= CASTLE_BK;
                        break;
                    case 'q':
                        castling |= CASTLE_BQ;
                        break;
                    default:
                        break;
                }
            }
        }

        // 4) 吃过路兵目标格
        if (parts.length > 3 && !"-".equals(parts[3])) {
            ep = Chess.parseSquare(parts[3]);
        }

        // 5) 半回合 / 回合数
        if (parts.length > 4) {
            try {
                halfmove = Integer.parseInt(parts[4]);
            } catch (NumberFormatException ignored) {
                halfmove = 0;
            }
        }
        if (parts.length > 5) {
            try {
                fullmove = Integer.parseInt(parts[5]);
            } catch (NumberFormatException ignored) {
                fullmove = 1;
            }
        }
        return true;
    }

    public String fen() {
        StringBuilder sb = new StringBuilder(80);
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int p = sq[Chess.square(file, rank)];
                if (p == 0) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(Chess.charOf(p));
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        sb.append(side == Chess.WHITE ? " w " : " b ");
        if (castling == 0) {
            sb.append('-');
        } else {
            if ((castling & CASTLE_WK) != 0) {
                sb.append('K');
            }
            if ((castling & CASTLE_WQ) != 0) {
                sb.append('Q');
            }
            if ((castling & CASTLE_BK) != 0) {
                sb.append('k');
            }
            if ((castling & CASTLE_BQ) != 0) {
                sb.append('q');
            }
        }
        sb.append(' ').append(ep < 0 ? "-" : Chess.squareName(ep));
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    // ------------------------------------------------------------- 查询

    public int kingSquare(int color) {
        return color == Chess.WHITE ? kingW : kingB;
    }

    public void copyFrom(Board other) {
        System.arraycopy(other.sq, 0, sq, 0, 128);
        side = other.side;
        castling = other.castling;
        ep = other.ep;
        halfmove = other.halfmove;
        fullmove = other.fullmove;
        kingW = other.kingW;
        kingB = other.kingB;
        top = 0;
        historySize = 0;
    }

    public Board copy() {
        Board b = new Board();
        b.copyFrom(this);
        return b;
    }

    /** square 是否被 bySide 一方攻击。 */
    public boolean isAttacked(int square, int bySide) {
        // 兵
        if (bySide == Chess.WHITE) {
            int a = square - 15;
            int b = square - 17;
            if (Chess.onBoard(a) && sq[a] == Chess.PAWN) {
                return true;
            }
            if (Chess.onBoard(b) && sq[b] == Chess.PAWN) {
                return true;
            }
        } else {
            int a = square + 15;
            int b = square + 17;
            if (Chess.onBoard(a) && sq[a] == -Chess.PAWN) {
                return true;
            }
            if (Chess.onBoard(b) && sq[b] == -Chess.PAWN) {
                return true;
            }
        }

        // 马
        int knight = bySide == Chess.WHITE ? Chess.KNIGHT : -Chess.KNIGHT;
        for (int i = 0; i < 8; i++) {
            int t = square + Chess.KNIGHT_OFFSETS[i];
            if (Chess.onBoard(t) && sq[t] == knight) {
                return true;
            }
        }

        // 王
        int king = bySide == Chess.WHITE ? Chess.KING : -Chess.KING;
        for (int i = 0; i < 8; i++) {
            int t = square + Chess.KING_OFFSETS[i];
            if (Chess.onBoard(t) && sq[t] == king) {
                return true;
            }
        }

        // 象 / 后（斜线）
        int bishop = bySide == Chess.WHITE ? Chess.BISHOP : -Chess.BISHOP;
        int queen = bySide == Chess.WHITE ? Chess.QUEEN : -Chess.QUEEN;
        for (int i = 0; i < 4; i++) {
            int dir = Chess.BISHOP_OFFSETS[i];
            int t = square + dir;
            while (Chess.onBoard(t)) {
                int p = sq[t];
                if (p != 0) {
                    if (p == bishop || p == queen) {
                        return true;
                    }
                    break;
                }
                t += dir;
            }
        }

        // 车 / 后（直线）
        int rook = bySide == Chess.WHITE ? Chess.ROOK : -Chess.ROOK;
        for (int i = 0; i < 4; i++) {
            int dir = Chess.ROOK_OFFSETS[i];
            int t = square + dir;
            while (Chess.onBoard(t)) {
                int p = sq[t];
                if (p != 0) {
                    if (p == rook || p == queen) {
                        return true;
                    }
                    break;
                }
                t += dir;
            }
        }
        return false;
    }

    /** color 一方的王是否被将军。 */
    public boolean inCheck(int color) {
        int k = kingSquare(color);
        if (k < 0) {
            return false;
        }
        return isAttacked(k, color == Chess.WHITE ? Chess.BLACK : Chess.WHITE);
    }

    // ----------------------------------------------------------- make/unmake

    public void make(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int piece = sq[from];
        int captured = sq[to];

        uCaptured[top] = captured;
        uCastling[top] = castling;
        uEp[top] = ep;
        uHalfmove[top] = halfmove;
        uKingW[top] = kingW;
        uKingB[top] = kingB;
        uFullmove[top] = fullmove;
        top++;

        // 吃过路兵：被吃的兵不在 to 上
        if (Move.has(move, Move.FLAG_EP)) {
            int capSq = to + (side == Chess.WHITE ? -16 : 16);
            uCaptured[top - 1] = sq[capSq];
            sq[capSq] = 0;
            captured = side == Chess.WHITE ? -Chess.PAWN : Chess.PAWN;
        }

        // 移动棋子
        sq[to] = piece;
        sq[from] = 0;

        // 升变
        int promo = Move.promo(move);
        if (promo != 0) {
            sq[to] = side == Chess.WHITE ? promo : -promo;
        }

        // 王车易位时同步挪车
        if (Move.has(move, Move.FLAG_CASTLE_K)) {
            int rf = from + 3;
            int rt = from + 1;
            sq[rt] = sq[rf];
            sq[rf] = 0;
        } else if (Move.has(move, Move.FLAG_CASTLE_Q)) {
            int rf = from - 4;
            int rt = from - 1;
            sq[rt] = sq[rf];
            sq[rf] = 0;
        }

        // 王的位置
        if (piece == Chess.KING) {
            kingW = to;
        } else if (piece == -Chess.KING) {
            kingB = to;
        }

        // 易位权
        castling &= CASTLE_MASK[from] & CASTLE_MASK[to];

        // 吃过路兵目标格
        if (Move.has(move, Move.FLAG_DOUBLE_PUSH)) {
            ep = from + (side == Chess.WHITE ? 16 : -16);
        } else {
            ep = -1;
        }

        // 半回合计数
        if (Chess.type(piece) == Chess.PAWN || captured != 0) {
            halfmove = 0;
        } else {
            halfmove++;
        }

        if (side == Chess.BLACK) {
            fullmove++;
        }
        side = side == Chess.WHITE ? Chess.BLACK : Chess.WHITE;

        history[historySize++] = move;
    }

    public void unmake() {
        if (top <= 0) {
            return;
        }
        top--;
        historySize--;

        int move = history[historySize];
        int from = Move.from(move);
        int to = Move.to(move);

        side = side == Chess.WHITE ? Chess.BLACK : Chess.WHITE;

        castling = uCastling[top];
        ep = uEp[top];
        halfmove = uHalfmove[top];
        kingW = uKingW[top];
        kingB = uKingB[top];
        fullmove = uFullmove[top];

        int piece = sq[to];
        // 升变还原成兵
        if (Move.has(move, Move.FLAG_PROMOTION)) {
            piece = side == Chess.WHITE ? Chess.PAWN : -Chess.PAWN;
        }
        sq[from] = piece;
        sq[to] = 0;

        // 易位的车挪回去
        if (Move.has(move, Move.FLAG_CASTLE_K)) {
            int rf = from + 3;
            int rt = from + 1;
            sq[rf] = sq[rt];
            sq[rt] = 0;
        } else if (Move.has(move, Move.FLAG_CASTLE_Q)) {
            int rf = from - 4;
            int rt = from - 1;
            sq[rf] = sq[rt];
            sq[rt] = 0;
        }

        // 还原被吃的子
        int captured = uCaptured[top];
        if (captured != 0) {
            if (Move.has(move, Move.FLAG_EP)) {
                int capSq = to + (side == Chess.WHITE ? -16 : 16);
                sq[capSq] = captured;
            } else {
                sq[to] = captured;
            }
        }
    }

    /** 空走一步（null move），用于搜索里的空着裁剪。 */
    public void makeNull() {
        uCaptured[top] = 0;
        uCastling[top] = castling;
        uEp[top] = ep;
        uHalfmove[top] = halfmove;
        uKingW[top] = kingW;
        uKingB[top] = kingB;
        uFullmove[top] = fullmove;
        top++;
        ep = -1;
        side = side == Chess.WHITE ? Chess.BLACK : Chess.WHITE;
    }

    public void unmakeNull() {
        top--;
        side = side == Chess.WHITE ? Chess.BLACK : Chess.WHITE;
        castling = uCastling[top];
        ep = uEp[top];
        halfmove = uHalfmove[top];
        kingW = uKingW[top];
        kingB = uKingB[top];
    }

    public int ply() {
        return top;
    }

    public void resetHistory() {
        historySize = 0;
    }

    // ------------------------------------------------------------- 简单局面键

    /**
     * 局面键，用于重复局面判定。
     * 不用 Zobrist（要一张随机表 + 初始化开销），直接算个 64 位多项式哈希，
     * 速度对这个小棋盘够用，且实现简单不容易错。
     */
    public long positionKey() {
        long h = 1469598103934665603L;
        for (int i = 0; i < 128; i++) {
            if ((i & 0x88) != 0) {
                continue;
            }
            int p = sq[i];
            if (p != 0) {
                h ^= (i * 31L + p * 131L);
                h *= 1099511628211L;
            }
        }
        h ^= side * 7919L;
        h ^= castling * 104729L;
        h ^= (ep + 2) * 1299709L;
        return h;
    }
}
