package org.lichessold.net;

import org.lichessold.json.Json;

/**
 * 对局数据模型。把 Lichess 的 gameFull / gameState JSON 解析成界面能直接用的字段。
 *
 * gameFull：完整对局数据，首行必发。
 * gameState：只含变化的部分，之后每次走子/提议和棋/结束都会发。
 */
public final class LichessGame {

    public String id = "";
    public String initialFen = "startpos";
    public String variant = "standard";
    public boolean rated;
    public String speed = "";
    public int clockInitial;
    public int clockIncrement;

    public String myColor = "";          // "white" / "black"
    public String whiteName = "";
    public String blackName = "";
    public String whiteId = "";
    public String blackId = "";
    public int whiteRating;
    public int blackRating;
    public boolean whiteIsAi;
    public boolean blackIsAi;
    public int aiLevel;

    public String moves = "";            // 空格分隔的 UCI
    public int wtime;
    public int btime;
    public int winc;
    public int binc;
    public String status = "started";
    public String winner = "";
    public boolean whiteOfferingDraw;
    public boolean blackOfferingDraw;
    public boolean whiteOfferingTakeback;
    public boolean blackOfferingTakeback;

    public boolean opponentGone;
    public long opponentGoneSeconds;

    public String lastChat = "";
    public String lastChatFrom = "";

    public boolean fullReceived;
    public long lastUpdateMillis;

    /** 用 gameFull 覆盖全部字段。 */
    public void applyFull(Json j) {
        id = j.str("id", id);
        initialFen = j.str("initialFen", "startpos");
        Json variantObj = j.obj("variant");
        variant = variantObj.str("key", j.str("variant", "standard"));
        rated = j.b("rated", false);
        Json speedObj = j.obj("speed");
        speed = speedObj.isString() ? speedObj.asString("") : speedObj.str("key", "");
        if (speed.length() == 0) {
            speed = j.str("speed", "");
        }
        Json clock = j.obj("clock");
        clockInitial = clock.i("initial", 0);
        clockIncrement = clock.i("increment", 0);

        applyPlayer(j.obj("white"), true);
        applyPlayer(j.obj("black"), false);

        Json state = j.obj("state");
        if (state.size() > 0) {
            applyState(state);
        }
        fullReceived = true;
        lastUpdateMillis = System.currentTimeMillis();
    }

    private void applyPlayer(Json p, boolean white) {
        if (p.size() == 0) {
            return;
        }
        String name = p.str("name", "");
        String id = p.str("id", name);
        int rating = p.i("rating", 0);
        int ai = p.i("aiLevel", 0);
        boolean isAi = ai > 0 || p.b("ai", false);
        if (white) {
            whiteName = name;
            whiteId = id;
            whiteRating = rating;
            whiteIsAi = isAi;
        } else {
            blackName = name;
            blackId = id;
            blackRating = rating;
            blackIsAi = isAi;
        }
        if (ai > 0) {
            aiLevel = ai;
        }
    }

    /**
     * 判断我执哪一方。
     *
     * gameFull 的 JSON 里**没有** myColor 字段 —— 颜色来自事件流的 gameStart
     * 或 /api/account/playing。所以当调用方没给颜色时，只能拿 white.id / black.id
     * 和自己的用户名比。这个判断错了会导致棋盘方向反、而且根本走不了子，
     * 所以必须留一个可靠的兜底。
     *
     * @param myUsername 当前登录的用户名，可为 null
     * @return "white" / "black"；无法判断时返回 ""
     */
    public String detectMyColor(String myUsername) {
        if (myColor != null && myColor.length() > 0) {
            return myColor;
        }
        if (myUsername != null && myUsername.length() > 0) {
            String me = myUsername.toLowerCase(java.util.Locale.US);
            if (whiteId != null && whiteId.toLowerCase(java.util.Locale.US).equals(me)) {
                return "white";
            }
            if (blackId != null && blackId.toLowerCase(java.util.Locale.US).equals(me)) {
                return "black";
            }
            if (whiteName != null && whiteName.toLowerCase(java.util.Locale.US).equals(me)) {
                return "white";
            }
            if (blackName != null && blackName.toLowerCase(java.util.Locale.US).equals(me)) {
                return "black";
            }
        }
        return "";
    }

    /** 用 gameState 增量更新。 */
    public void applyState(Json s) {
        moves = s.str("moves", moves);
        wtime = s.i("wtime", wtime);
        btime = s.i("btime", btime);
        winc = s.i("winc", winc);
        binc = s.i("binc", binc);
        status = s.str("status", status);
        winner = s.str("winner", winner);
        whiteOfferingDraw = s.b("wdraw", whiteOfferingDraw);
        blackOfferingDraw = s.b("bdraw", blackOfferingDraw);
        whiteOfferingTakeback = s.b("wtakeback", whiteOfferingTakeback);
        blackOfferingTakeback = s.b("btakeback", blackOfferingTakeback);
        lastUpdateMillis = System.currentTimeMillis();
    }

    // -------------------------------------------------------------- 便捷判断

    public boolean isMyTurn() {
        return myColor.length() > 0 && myColor.equals(sideToMove());
    }

    /** 走子串里最后一个走子的颜色决定了下一步该谁走。 */
    public String sideToMove() {
        int n = moveCount();
        boolean whiteToMove = (n % 2) == 0;
        // 如果初始局面是自定义 FEN，行棋方由 FEN 决定
        if (!"startpos".equals(initialFen) && initialFen != null
                && initialFen.length() > 0 && initialFen.indexOf(' ') > 0) {
            String[] parts = initialFen.split("\\s+");
            if (parts.length > 1) {
                whiteToMove = !"b".equals(parts[1]);
                if (n % 2 == 1) {
                    whiteToMove = !whiteToMove;
                }
            }
        }
        return whiteToMove ? "white" : "black";
    }

    public int moveCount() {
        if (moves == null || moves.length() == 0) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < moves.length(); i++) {
            if (moves.charAt(i) == ' ') {
                count++;
            }
        }
        return count;
    }

    public String[] moveList() {
        if (moves == null || moves.length() == 0) {
            return new String[0];
        }
        return moves.split(" ");
    }

    public boolean isFinished() {
        return !"started".equals(status) && !"created".equals(status);
    }

    public String myName() {
        return "white".equals(myColor) ? whiteName : blackName;
    }

    public String opponentName() {
        return "white".equals(myColor) ? blackName : whiteName;
    }

    public int myRating() {
        return "white".equals(myColor) ? whiteRating : blackRating;
    }

    public int opponentRating() {
        return "white".equals(myColor) ? blackRating : whiteRating;
    }

    public boolean opponentIsAi() {
        return "white".equals(myColor) ? blackIsAi : whiteIsAi;
    }

    public int myTime() {
        return "white".equals(myColor) ? wtime : btime;
    }

    public int opponentTime() {
        return "white".equals(myColor) ? btime : wtime;
    }

    public boolean opponentOfferingDraw() {
        return "white".equals(myColor) ? blackOfferingDraw : whiteOfferingDraw;
    }

    public boolean iAmOfferingDraw() {
        return "white".equals(myColor) ? whiteOfferingDraw : blackOfferingDraw;
    }

    /** 结果文案。 */
    public String resultText() {
        if (!isFinished()) {
            return "";
        }
        if ("draw".equals(status)) {
            return "和棋";
        }
        if ("stalemate".equals(status)) {
            return "逼和，和棋";
        }
        if (winner == null || winner.length() == 0) {
            return statusText(status);
        }
        boolean iWon = winner.equals(myColor);
        return (iWon ? "你赢了" : "你输了") + "（" + statusText(status) + "）";
    }

    public static String statusText(String status) {
        if (status == null) {
            return "";
        }
        if (status.equals("created")) {
            return "等待开始";
        }
        if (status.equals("started")) {
            return "进行中";
        }
        if (status.equals("aborted")) {
            return "已中止";
        }
        if (status.equals("mate")) {
            return "将死";
        }
        if (status.equals("resign")) {
            return "认输";
        }
        if (status.equals("stalemate")) {
            return "逼和";
        }
        if (status.equals("timeout")) {
            return "超时";
        }
        if (status.equals("draw")) {
            return "和棋";
        }
        if (status.equals("outoftime")) {
            return "对方/己方超时";
        }
        if (status.equals("cheat")) {
            return "作弊判负";
        }
        if (status.equals("noStart")) {
            return "未开始";
        }
        if (status.equals("unknownFinish")) {
            return "未知结束方式";
        }
        if (status.equals("variantEnd")) {
            return "变体结束";
        }
        return status;
    }
}
