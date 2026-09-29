package org.lichessold.chess;

/**
 * 局面结果判定。离线对局、本地双人、谜题都需要它。
 */
public final class GameStatus {

    public static final int ONGOING = 0;
    public static final int CHECKMATE = 1;
    public static final int STALEMATE = 2;
    public static final int DRAW_FIFTY = 3;
    public static final int DRAW_MATERIAL = 4;
    public static final int DRAW_REPETITION = 5;

    private GameStatus() {
    }

    public static int of(Board b, MoveGen gen) {
        boolean hasMove = gen.hasLegalMove(b);
        if (!hasMove) {
            return b.inCheck(b.side) ? CHECKMATE : STALEMATE;
        }
        if (b.halfmove >= 100) {
            return DRAW_FIFTY;
        }
        if (isInsufficientMaterial(b)) {
            return DRAW_MATERIAL;
        }
        return ONGOING;
    }

    public static String describe(int status, int sideToMove) {
        switch (status) {
            case CHECKMATE:
                return (sideToMove == Chess.WHITE ? "黑方" : "白方") + "将死获胜";
            case STALEMATE:
                return "逼和（和棋）";
            case DRAW_FIFTY:
                return "50 回合规则和棋";
            case DRAW_MATERIAL:
                return "子力不足，和棋";
            case DRAW_REPETITION:
                return "三次重复局面，和棋";
            default:
                return "对局进行中";
        }
    }

    /** 是否"子力不足"（双方都无法将死对方）。 */
    public static boolean isInsufficientMaterial(Board b) {
        int whiteMinor = 0;
        int blackMinor = 0;
        int whiteOther = 0;
        int blackOther = 0;
        for (int sq = 0; sq < 128; sq++) {
            if ((sq & 0x88) != 0) {
                continue;
            }
            int p = b.sq[sq];
            if (p == 0) {
                continue;
            }
            int type = Chess.type(p);
            if (type == Chess.KING) {
                continue;
            }
            boolean white = p > 0;
            if (type == Chess.BISHOP || type == Chess.KNIGHT) {
                if (white) {
                    whiteMinor++;
                } else {
                    blackMinor++;
                }
            } else {
                if (white) {
                    whiteOther++;
                } else {
                    blackOther++;
                }
            }
        }
        // 有兵/车/后就还有将死可能
        if (whiteOther > 0 || blackOther > 0) {
            return false;
        }
        // 王 vs 王；王+单轻子 vs 王；王+单轻子 vs 王+单轻子
        return whiteMinor <= 1 && blackMinor <= 1;
    }

    /**
     * 三次重复局面判定。
     *
     * 由调用方（对局控制器）维护一个"已出现过的局面键"列表，把当前局面键传进来。
     * 不放在 Board 里做，是因为 positionKey() 要遍历整个棋盘，
     * 而 make/unmake 在搜索里每秒调用几十万次 —— 那样会把搜索拖死。
     *
     * @param previousKeys 之前出现过的局面键（按时间顺序）
     * @param currentKey   当前局面键
     * @return 同一局面是否已出现 3 次（含当前）
     */
    public static boolean isThreefold(java.util.List<Long> previousKeys, long currentKey) {
        if (previousKeys == null) {
            return false;
        }
        int count = 1;   // 当前这一次
        for (int i = previousKeys.size() - 1; i >= 0; i--) {
            Long k = previousKeys.get(i);
            if (k != null && k.longValue() == currentKey) {
                count++;
                if (count >= 3) {
                    return true;
                }
            }
        }
        return false;
    }
}
