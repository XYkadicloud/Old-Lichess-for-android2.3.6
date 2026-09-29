package org.lichessold.chess;

/**
 * 走子生成。
 *
 * 先生成"伪合法"走子，再用 make/unmake 过滤掉会让己方王被吃的。
 * 这个做法比直接生成合法走子慢一点，但正确性容易保证 —— 而正确性用 perft
 * 逐层对数验证，比"看起来对"可靠得多。
 *
 * **缓冲区设计**：走子生成是搜索里调用最频繁的函数，每次调用都 new 一个
 * 1KB 的 int[] 会让 ARM11 上的 GC 抖动到不可用。所以按"层数"预分配缓冲区，
 * 递归到第 N 层就用第 N 个缓冲区，零分配。
 */
public final class MoveGen {

    /** 一个局面最多 218 个合法走子，留够余量。 */
    public static final int MAX_MOVES = 256;

    /**
     * 给界面用的共享实例。界面是单线程的，够用。
     * 层数按"一整局最多多少个半回合"给足，缓冲区是懒分配的，用不到就不占内存。
     */
    public static final MoveGen SHARED = new MoveGen(300);

    private final int[][] buffers;

    public MoveGen(int maxPly) {
        buffers = new int[Math.max(1, maxPly)][];
    }

    /** 取第 ply 层的走子缓冲区。perft 和搜索用它，避免每次调用都 new 数组。 */
    public int[] bufferAt(int ply) {
        return buffer(ply);
    }

    private int[] buffer(int ply) {
        if (ply < 0) {
            ply = 0;
        }
        if (ply >= buffers.length) {
            // 超深层数极少见，退化成临时分配，保证不越界
            return new int[MAX_MOVES];
        }
        int[] b = buffers[ply];
        if (b == null) {
            b = new int[MAX_MOVES];
            buffers[ply] = b;
        }
        return b;
    }

    // ------------------------------------------------------------ 伪合法

    /** 生成伪合法走子，返回个数。写入调用方给的数组，自身不分配。 */
    public static int generate(Board b, int[] out) {
        int n = 0;
        int us = b.side;
        int them = us == Chess.WHITE ? Chess.BLACK : Chess.WHITE;

        for (int from = 0; from < 128; from++) {
            if ((from & 0x88) != 0) {
                continue;
            }
            int piece = b.sq[from];
            if (piece == 0 || Chess.colorOf(piece) != us) {
                continue;
            }
            switch (Chess.type(piece)) {
                case Chess.PAWN:
                    n = genPawn(b, from, us, them, out, n);
                    break;
                case Chess.KNIGHT:
                    for (int i = 0; i < 8; i++) {
                        int to = from + Chess.KNIGHT_OFFSETS[i];
                        if (!Chess.onBoard(to)) {
                            continue;
                        }
                        int target = b.sq[to];
                        if (target == 0) {
                            out[n++] = Move.of(from, to);
                        } else if (Chess.colorOf(target) == them) {
                            out[n++] = Move.withFlag(Move.of(from, to), Move.FLAG_CAPTURE);
                        }
                    }
                    break;
                case Chess.BISHOP:
                    n = genSlide(b, from, them, Chess.BISHOP_OFFSETS, out, n);
                    break;
                case Chess.ROOK:
                    n = genSlide(b, from, them, Chess.ROOK_OFFSETS, out, n);
                    break;
                case Chess.QUEEN:
                    n = genSlide(b, from, them, Chess.BISHOP_OFFSETS, out, n);
                    n = genSlide(b, from, them, Chess.ROOK_OFFSETS, out, n);
                    break;
                case Chess.KING:
                    for (int i = 0; i < 8; i++) {
                        int to = from + Chess.KING_OFFSETS[i];
                        if (!Chess.onBoard(to)) {
                            continue;
                        }
                        int target = b.sq[to];
                        if (target == 0) {
                            out[n++] = Move.of(from, to);
                        } else if (Chess.colorOf(target) == them) {
                            out[n++] = Move.withFlag(Move.of(from, to), Move.FLAG_CAPTURE);
                        }
                    }
                    n = genCastle(b, from, us, out, n);
                    break;
                default:
                    break;
            }
        }
        return n;
    }

    private static int genPawn(Board b, int from, int us, int them, int[] out, int n) {
        int push = Chess.PAWN_PUSH[us];
        int startRank = Chess.PAWN_START_RANK[us];
        int promoRank = Chess.PAWN_PROMO_RANK[us];

        int one = from + push;
        if (Chess.onBoard(one) && b.sq[one] == 0) {
            if (Chess.rank(one) == promoRank) {
                n = addPromotions(out, n, from, one, false);
            } else {
                out[n++] = Move.of(from, one);
                if (Chess.rank(from) == startRank) {
                    int two = one + push;
                    if (Chess.onBoard(two) && b.sq[two] == 0) {
                        out[n++] = Move.withFlag(Move.of(from, two), Move.FLAG_DOUBLE_PUSH);
                    }
                }
            }
        }

        int d1 = from + push - 1;
        int d2 = from + push + 1;
        for (int k = 0; k < 2; k++) {
            int to = k == 0 ? d1 : d2;
            if (!Chess.onBoard(to)) {
                continue;
            }
            int target = b.sq[to];
            if (target != 0 && Chess.colorOf(target) == them) {
                if (Chess.rank(to) == promoRank) {
                    n = addPromotions(out, n, from, to, true);
                } else {
                    out[n++] = Move.withFlag(Move.of(from, to), Move.FLAG_CAPTURE);
                }
            } else if (target == 0 && to == b.ep) {
                out[n++] = Move.withFlag(Move.of(from, to),
                        Move.FLAG_CAPTURE | Move.FLAG_EP);
            }
        }
        return n;
    }

    private static int addPromotions(int[] out, int n, int from, int to, boolean capture) {
        for (int i = 0; i < Chess.PROMOTIONS.length; i++) {
            int mv = Move.of(from, to, Chess.PROMOTIONS[i]) | Move.FLAG_PROMOTION;
            if (capture) {
                mv |= Move.FLAG_CAPTURE;
            }
            out[n++] = mv;
        }
        return n;
    }

    private static int genSlide(Board b, int from, int them, int[] dirs, int[] out, int n) {
        for (int i = 0; i < dirs.length; i++) {
            int dir = dirs[i];
            int to = from + dir;
            while (Chess.onBoard(to)) {
                int target = b.sq[to];
                if (target == 0) {
                    out[n++] = Move.of(from, to);
                } else {
                    if (Chess.colorOf(target) == them) {
                        out[n++] = Move.withFlag(Move.of(from, to), Move.FLAG_CAPTURE);
                    }
                    break;
                }
                to += dir;
            }
        }
        return n;
    }

    private static int genCastle(Board b, int from, int us, int[] out, int n) {
        if (us == Chess.WHITE) {
            if (from != Board.E1 || b.inCheck(Chess.WHITE)) {
                return n;
            }
            if ((b.castling & Board.CASTLE_WK) != 0
                    && b.sq[Board.H1] == Chess.ROOK
                    && b.sq[5] == 0 && b.sq[6] == 0
                    && !b.isAttacked(5, Chess.BLACK)
                    && !b.isAttacked(6, Chess.BLACK)) {
                out[n++] = Move.withFlag(Move.of(from, 6), Move.FLAG_CASTLE_K);
            }
            if ((b.castling & Board.CASTLE_WQ) != 0
                    && b.sq[Board.A1] == Chess.ROOK
                    && b.sq[3] == 0 && b.sq[2] == 0 && b.sq[1] == 0
                    && !b.isAttacked(3, Chess.BLACK)
                    && !b.isAttacked(2, Chess.BLACK)) {
                out[n++] = Move.withFlag(Move.of(from, 2), Move.FLAG_CASTLE_Q);
            }
        } else {
            if (from != Board.E8 || b.inCheck(Chess.BLACK)) {
                return n;
            }
            if ((b.castling & Board.CASTLE_BK) != 0
                    && b.sq[Board.H8] == -Chess.ROOK
                    && b.sq[117] == 0 && b.sq[118] == 0
                    && !b.isAttacked(117, Chess.WHITE)
                    && !b.isAttacked(118, Chess.WHITE)) {
                out[n++] = Move.withFlag(Move.of(from, 118), Move.FLAG_CASTLE_K);
            }
            if ((b.castling & Board.CASTLE_BQ) != 0
                    && b.sq[Board.A8] == -Chess.ROOK
                    && b.sq[115] == 0 && b.sq[114] == 0 && b.sq[113] == 0
                    && !b.isAttacked(115, Chess.WHITE)
                    && !b.isAttacked(114, Chess.WHITE)) {
                out[n++] = Move.withFlag(Move.of(from, 114), Move.FLAG_CASTLE_Q);
            }
        }
        return n;
    }

    // -------------------------------------------------------------- 合法性

    /** 这个走子走后，己方的王是否安全。 */
    public static boolean isLegal(Board b, int move) {
        int us = b.side;
        b.make(move);
        boolean ok = !b.inCheck(us);
        b.unmake();
        return ok;
    }

    /** 生成合法走子，返回个数。写入调用方给的数组。 */
    public int legal(Board b, int[] out) {
        // 用 ply+1 的缓冲区：调用方通常把 ply 那层留给自己存走子表
        int[] tmp = buffer(b.ply() + 1);
        int n = generate(b, tmp);
        int us = b.side;
        int count = 0;
        for (int i = 0; i < n; i++) {
            b.make(tmp[i]);
            if (!b.inCheck(us)) {
                out[count++] = tmp[i];
            }
            b.unmake();
        }
        return count;
    }

    public boolean hasLegalMove(Board b) {
        int[] tmp = buffer(b.ply() + 1);
        int n = generate(b, tmp);
        int us = b.side;
        for (int i = 0; i < n; i++) {
            b.make(tmp[i]);
            boolean ok = !b.inCheck(us);
            b.unmake();
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在合法走子里找匹配 UCI 的那个。找到返回编码后的走子，找不到返回 Move.NONE。
     * 用于把 Lichess 返回的 UCI 落子、以及谜题解答落到本地棋盘上。
     */
    public int findByUci(Board b, String uci) {
        int want = Move.fromUci(uci);
        if (want == Move.NONE) {
            return Move.NONE;
        }
        int[] moves = buffer(b.ply());
        int n = legal(b, moves);
        for (int i = 0; i < n; i++) {
            if (Move.sameEndpoints(moves[i], want)) {
                return moves[i];
            }
        }
        return Move.NONE;
    }

    /** 便捷版：用共享实例查 UCI。 */
    public static int lookupUci(Board b, String uci) {
        return SHARED.findByUci(b, uci);
    }
}
