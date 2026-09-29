package org.lichessold.chess;

/**
 * 离线 AI 引擎。
 *
 * 为 832MHz 的 ARM11 调优，取舍如下：
 *   有：迭代加深 + alpha-beta + MVV-LVA 排序 + 杀手着法 + 静态搜索 + 小置换表
 *   没有：空着裁剪、LMR、SEE、开局库 —— 这些在弱机上收益不确定，代码风险更大
 *
 * 静态搜索（quiescence）是必须的：没有它，搜索会在"刚吃完子但还没吃回来"的
 * 位置停下，导致 AI 白送子。这是最简单的引擎最容易犯的错。
 *
 * 置换表故意做得很小（16384 项，约 256KB）：手机上堆只有 64MB，
 * 而且缓存大了在 ARM11 上访存反而更慢。
 */
public final class Ai {

    public static final int MATE = 100000;
    public static final int INF = 1000000;

    private static final int TT_BITS = 14;
    private static final int TT_SIZE = 1 << TT_BITS;
    private static final int TT_MASK = TT_SIZE - 1;

    private static final int TT_EXACT = 0;
    private static final int TT_LOWER = 1;
    private static final int TT_UPPER = 2;

    private static final int[] PIECE_VALUE = { 0, 100, 320, 330, 500, 900, 0 };

    private static final int MAX_PLY = 48;

    /** 搜索里的信息，方便界面显示"思考深度 / 评分"。 */
    public static final class Result {
        public int move = Move.NONE;
        public int score;
        public int depth;
        public long nodes;
        public long millis;
        public boolean mate;
        public int mateIn;

        public String describe() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("深度 ").append(depth)
                    .append("，评分 ").append(score)
                    .append("，节点 ").append(nodes)
                    .append("，耗时 ").append(millis).append("ms");
            if (mate) {
                sb.append("，").append(mateIn > 0 ? mateIn + " 步杀" : "被将死");
            }
            return sb.toString();
        }
    }

    private final MoveGen gen;
    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttMove = new int[TT_SIZE];
    private final int[] ttInfo = new int[TT_SIZE];

    private final int[] killer1 = new int[MAX_PLY];
    private final int[] killer2 = new int[MAX_PLY];
    /**
     * 排序用的分数缓冲。
     * negamax 和 quiesce 各用一份：虽然按现在的写法递归时外层只读 moves 不读 sc，
     * 共用一份也不会错，但那是"靠调用顺序保证的正确性"，改一行就可能踩坑。
     * 分开之后就不用再想了。
     */
    private final int[] scoreBuf = new int[MoveGen.MAX_MOVES];
    private final int[] qScoreBuf = new int[MoveGen.MAX_MOVES];

    private long nodes;
    private long deadline;
    private boolean timeUp;

    public Ai() {
        this.gen = new MoveGen(MAX_PLY + 4);
    }

    /** Lichess AI 等级 1~8 对应的时间预算（毫秒）。弱机上也别让人等太久。 */
    public static long timeBudgetForLevel(int level) {
        switch (level) {
            case 1:
                return 250;
            case 2:
                return 450;
            case 3:
                return 800;
            case 4:
                return 1400;
            case 5:
                return 2200;
            case 6:
                return 3200;
            case 7:
                return 4500;
            case 8:
                return 6000;
            default:
                return 1000;
        }
    }

    /** Lichess AI 等级 1~8 对应的最大搜索深度。 */
    public static int depthForLevel(int level) {
        if (level <= 1) {
            return 2;
        }
        if (level <= 3) {
            return 3;
        }
        if (level <= 5) {
            return 4;
        }
        if (level <= 7) {
            return 5;
        }
        return 6;
    }

    /**
     * 搜索最佳走子。
     *
     * @param timeLimitMs 时间上限（毫秒）。迭代加深会一直加到深度上限或超时。
     */
    public Result search(Board b, int maxDepth, long timeLimitMs) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        deadline = t0 + Math.max(50L, timeLimitMs);
        timeUp = false;
        nodes = 0;
        clearTt();
        for (int i = 0; i < MAX_PLY; i++) {
            killer1[i] = Move.NONE;
            killer2[i] = Move.NONE;
        }

        int[] moves = gen.bufferAt(b.ply());
        int n = gen.legal(b, moves);
        if (n == 0) {
            r.move = Move.NONE;
            r.score = b.inCheck(b.side) ? -MATE : 0;
            r.millis = System.currentTimeMillis() - t0;
            return r;
        }
        if (n == 1) {
            r.move = moves[0];
            r.depth = 1;
            r.score = evaluate(b);
            r.millis = System.currentTimeMillis() - t0;
            return r;
        }

        int bestMove = moves[0];
        int bestScore = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            int alpha = -INF;
            int beta = INF;
            int iterBest = Move.NONE;
            int iterScore = -INF;

            // 上一轮的最佳着法优先
            orderMoves(b, moves, n, 0, bestMove, 0);

            for (int i = 0; i < n; i++) {
                int mv = moves[i];
                b.make(mv);
                int score = -negamax(b, depth - 1, -beta, -alpha, 1);
                b.unmake();

                if (timeUp) {
                    break;
                }
                if (score > iterScore) {
                    iterScore = score;
                    iterBest = mv;
                }
                if (score > alpha) {
                    alpha = score;
                }
            }

            if (iterBest != Move.NONE && (!timeUp || depth == 1)) {
                bestMove = iterBest;
                bestScore = iterScore;
                r.depth = depth;
            }
            if (timeUp) {
                break;
            }
            // 已经找到必杀，不用再深了
            if (bestScore > MATE - 100) {
                break;
            }
        }

        r.move = bestMove;
        r.score = bestScore;
        r.nodes = nodes;
        r.millis = System.currentTimeMillis() - t0;
        r.mate = bestScore > MATE - 1000 || bestScore < -(MATE - 1000);
        if (r.mate) {
            int plies = MATE - Math.abs(bestScore);
            r.mateIn = (plies + 1) / 2 * (bestScore > 0 ? 1 : -1);
        }
        return r;
    }

    // -------------------------------------------------------------- 搜索主体

    private int negamax(Board b, int depth, int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0 && System.currentTimeMillis() > deadline) {
            timeUp = true;
            return 0;
        }
        if (ply >= MAX_PLY - 1) {
            return evaluate(b);
        }

        // 50 回合规则
        if (b.halfmove >= 100) {
            return 0;
        }

        long key = b.positionKey();
        int slot = (int) (key & TT_MASK);
        int moveFromTt = Move.NONE;
        if (ttKey[slot] == key) {
            int info = ttInfo[slot];
            int ttDepth = (info >>> 24) & 0xFF;
            int flag = (info >>> 22) & 0x3;
            int ttScore = (short) (info & 0xFFFF);
            moveFromTt = ttMove[slot];
            if (ttDepth >= depth) {
                if (flag == TT_EXACT) {
                    return ttScore;
                }
                if (flag == TT_LOWER && ttScore >= beta) {
                    return ttScore;
                }
                if (flag == TT_UPPER && ttScore <= alpha) {
                    return ttScore;
                }
            }
        }

        boolean inCheck = b.inCheck(b.side);
        if (inCheck) {
            depth++;   // 被将军时多搜一层，避免漏掉应将
        }
        if (depth <= 0) {
            return quiesce(b, alpha, beta, ply);
        }

        int[] moves = gen.bufferAt(b.ply());
        int n = gen.legal(b, moves);
        if (n == 0) {
            return inCheck ? -(MATE - ply) : 0;
        }

        orderMoves(b, moves, n, 0, moveFromTt, ply);

        int best = -INF;
        int bestMove = Move.NONE;
        int originalAlpha = alpha;

        for (int i = 0; i < n; i++) {
            int mv = moves[i];
            b.make(mv);
            int score;
            if (i == 0) {
                score = -negamax(b, depth - 1, -beta, -alpha, ply + 1);
            } else {
                // PVS：先窄窗口试探，失败再全窗口重搜
                score = -negamax(b, depth - 1, -alpha - 1, -alpha, ply + 1);
                if (score > alpha && score < beta) {
                    score = -negamax(b, depth - 1, -beta, -alpha, ply + 1);
                }
            }
            b.unmake();

            if (timeUp) {
                return 0;
            }

            if (score > best) {
                best = score;
                bestMove = mv;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                // 杀手着法：非吃子导致的剪枝，记下来下次优先试
                if (!Move.has(mv, Move.FLAG_CAPTURE)) {
                    if (killer1[ply] != mv) {
                        killer2[ply] = killer1[ply];
                        killer1[ply] = mv;
                    }
                }
                break;
            }
        }

        // 写置换表
        int flag = best <= originalAlpha ? TT_UPPER : (best >= beta ? TT_LOWER : TT_EXACT);
        int clamped = best;
        if (clamped > 32000) {
            clamped = 32000;
        } else if (clamped < -32000) {
            clamped = -32000;
        }
        ttKey[slot] = key;
        ttMove[slot] = bestMove;
        ttInfo[slot] = (Math.min(depth, 255) << 24) | (flag << 22) | (clamped & 0xFFFF);

        return best;
    }

    /** 静态搜索：只搜吃子，直到局面"安静"下来为止。 */
    private int quiesce(Board b, int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0 && System.currentTimeMillis() > deadline) {
            timeUp = true;
            return 0;
        }
        if (ply >= MAX_PLY - 1) {
            return evaluate(b);
        }

        int standPat = evaluate(b);
        if (standPat >= beta) {
            return beta;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        int[] moves = gen.bufferAt(b.ply());
        int n = gen.generate(b, moves);

        // 只留吃子与升变，并按 MVV-LVA 排序
        int count = 0;
        int[] sc = qScoreBuf;
        for (int i = 0; i < n; i++) {
            int mv = moves[i];
            boolean interesting = Move.has(mv, Move.FLAG_CAPTURE)
                    || Move.has(mv, Move.FLAG_PROMOTION);
            if (!interesting) {
                continue;
            }
            moves[count] = mv;
            sc[count] = mvvLva(b, mv);
            count++;
        }
        sortDesc(moves, sc, count);

        int us = b.side;
        for (int i = 0; i < count; i++) {
            int mv = moves[i];
            b.make(mv);
            if (b.inCheck(us)) {
                b.unmake();
                continue;
            }
            int score = -quiesce(b, -beta, -alpha, ply + 1);
            b.unmake();
            if (timeUp) {
                return 0;
            }
            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    // -------------------------------------------------------------- 排序

    private void orderMoves(Board b, int[] moves, int n, int from, int ttMove, int ply) {
        int[] sc = scoreBuf;
        for (int i = 0; i < n; i++) {
            int mv = moves[i];
            int s;
            if (mv == ttMove) {
                s = 1 << 24;
            } else if (Move.has(mv, Move.FLAG_CAPTURE)) {
                s = (1 << 20) + mvvLva(b, mv);
            } else if (mv == killer1[ply]) {
                s = (1 << 18) + 100;
            } else if (mv == killer2[ply]) {
                s = (1 << 18) + 50;
            } else if (Move.has(mv, Move.FLAG_PROMOTION)) {
                s = (1 << 19) + Move.promo(mv) * 10;
            } else {
                s = 0;
            }
            sc[i] = s;
        }
        sortDesc(moves, sc, n);
    }

    /** 被吃子价值高、吃子棋子价值低 → 优先。 */
    private static int mvvLva(Board b, int mv) {
        int victim = b.sq[Move.to(mv)];
        int v = victim == 0 ? 100 : PIECE_VALUE[Chess.type(victim)];
        int attacker = PIECE_VALUE[Chess.type(b.sq[Move.from(mv)])];
        return v * 16 - attacker;
    }

    private static void sortDesc(int[] moves, int[] scores, int n) {
        // 插入排序：n 很小（通常 < 40），比快排更省开销且无递归
        for (int i = 1; i < n; i++) {
            int mv = moves[i];
            int sc = scores[i];
            int j = i - 1;
            while (j >= 0 && scores[j] < sc) {
                moves[j + 1] = moves[j];
                scores[j + 1] = scores[j];
                j--;
            }
            moves[j + 1] = mv;
            scores[j + 1] = sc;
        }
    }

    private void clearTt() {
        // 只需要清 key，其它字段靠 key 校验
        for (int i = 0; i < TT_SIZE; i++) {
            ttKey[i] = 0;
        }
    }

    // -------------------------------------------------------------- 评估

    /** 静态局面评估，从当前行棋方视角返回分数（正数 = 好）。 */
    public static int evaluate(Board b) {
        int score = 0;
        int whiteMaterial = 0;
        int blackMaterial = 0;
        int whiteBishops = 0;
        int blackBishops = 0;

        for (int sq = 0; sq < 128; sq++) {
            if ((sq & 0x88) != 0) {
                continue;
            }
            int p = b.sq[sq];
            if (p == 0) {
                continue;
            }
            int type = Chess.type(p);
            int value = PIECE_VALUE[type];
            if (type == Chess.BISHOP) {
                if (p > 0) {
                    whiteBishops++;
                } else {
                    blackBishops++;
                }
            }
            if (type == Chess.KING) {
                if (p > 0) {
                    whiteMaterial += value;
                } else {
                    blackMaterial += value;
                }
                continue;
            }
            int pstIndex = pstIndex(sq, p > 0);
            int bonus = PST[type][pstIndex];
            if (p > 0) {
                whiteMaterial += value;
                score += value + bonus;
            } else {
                blackMaterial += value;
                score -= value + bonus;
            }
        }

        // 双象加分
        if (whiteBishops >= 2) {
            score += 30;
        }
        if (blackBishops >= 2) {
            score -= 30;
        }

        // 残局时用王表（把王往中心赶）
        boolean endgame = (whiteMaterial + blackMaterial) < 2600;
        if (endgame) {
            score += kingEndgameBonus(b.kingW, true);
            score -= kingEndgameBonus(b.kingB, false);
        } else {
            score += kingMiddlegameBonus(b.kingW, true);
            score -= kingMiddlegameBonus(b.kingB, false);
        }

        return b.side == Chess.WHITE ? score : -score;
    }

    private static int kingEndgameBonus(int sq, boolean white) {
        if (!Chess.onBoard(sq)) {
            return 0;
        }
        return KING_END[sqIndex(sq, white)];
    }

    private static int kingMiddlegameBonus(int sq, boolean white) {
        if (!Chess.onBoard(sq)) {
            return 0;
        }
        return KING_MID[sqIndex(sq, white)];
    }

    /** 0x88 索引 -> 0..63 表索引（黑方上下镜像）。 */
    private static int pstIndex(int sq, boolean white) {
        return sqIndex(sq, white);
    }

    private static int sqIndex(int sq, boolean white) {
        int f = sq & 7;
        int r = sq >> 4;
        if (!white) {
            r = 7 - r;
        }
        return r * 8 + f;
    }

    // -------------------------------------------------- 棋子位置价值表
    // 索引顺序：0 = a1，7 = h1，56 = a8，63 = h8（白方视角）

    private static final int[][] PST = new int[7][64];

    private static final int[] KING_MID = {
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
             20,  20,   0,   0,   0,   0,  20,  20,
             20,  30,  10,   0,   0,  10,  30,  20
    };

    private static final int[] KING_END = {
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10,   0,   0, -10, -20, -30,
            -30, -10,  20,  30,  30,  20, -10, -30,
            -30, -10,  30,  40,  40,  30, -10, -30,
            -30, -10,  30,  40,  40,  30, -10, -30,
            -30, -10,  20,  30,  30,  20, -10, -30,
            -30, -30,   0,   0,   0,   0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50
    };

    static {
        int[] pawn = {
                 0,   0,   0,   0,   0,   0,   0,   0,
                50,  50,  50,  50,  50,  50,  50,  50,
                10,  10,  20,  30,  30,  20,  10,  10,
                 5,   5,  10,  25,  25,  10,   5,   5,
                 0,   0,   0,  20,  20,   0,   0,   0,
                 5,  -5, -10,   0,   0, -10,  -5,   5,
                 5,  10,  10, -20, -20,  10,  10,   5,
                 0,   0,   0,   0,   0,   0,   0,   0
        };
        int[] knight = {
                -50, -40, -30, -30, -30, -30, -40, -50,
                -40, -20,   0,   0,   0,   0, -20, -40,
                -30,   0,  10,  15,  15,  10,   0, -30,
                -30,   5,  15,  20,  20,  15,   5, -30,
                -30,   0,  15,  20,  20,  15,   0, -30,
                -30,   5,  10,  15,  15,  10,   5, -30,
                -40, -20,   0,   5,   5,   0, -20, -40,
                -50, -40, -30, -30, -30, -30, -40, -50
        };
        int[] bishop = {
                -20, -10, -10, -10, -10, -10, -10, -20,
                -10,   0,   0,   0,   0,   0,   0, -10,
                -10,   0,   5,  10,  10,   5,   0, -10,
                -10,   5,   5,  10,  10,   5,   5, -10,
                -10,   0,  10,  10,  10,  10,   0, -10,
                -10,  10,  10,  10,  10,  10,  10, -10,
                -10,   5,   0,   0,   0,   0,   5, -10,
                -20, -10, -10, -10, -10, -10, -10, -20
        };
        int[] rook = {
                  0,   0,   0,   0,   0,   0,   0,   0,
                  5,  10,  10,  10,  10,  10,  10,   5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                 -5,   0,   0,   0,   0,   0,   0,  -5,
                  0,   0,   0,   5,   5,   0,   0,   0
        };
        int[] queen = {
                -20, -10, -10,  -5,  -5, -10, -10, -20,
                -10,   0,   0,   0,   0,   0,   0, -10,
                -10,   0,   5,   5,   5,   5,   0, -10,
                 -5,   0,   5,   5,   5,   5,   0,  -5,
                  0,   0,   5,   5,   5,   5,   0,  -5,
                -10,   5,   5,   5,   5,   5,   0, -10,
                -10,   0,   5,   0,   0,   0,   0, -10,
                -20, -10, -10,  -5,  -5, -10, -10, -20
        };
        PST[Chess.PAWN] = pawn;
        PST[Chess.KNIGHT] = knight;
        PST[Chess.BISHOP] = bishop;
        PST[Chess.ROOK] = rook;
        PST[Chess.QUEEN] = queen;
        PST[Chess.KING] = KING_MID;
    }
}
