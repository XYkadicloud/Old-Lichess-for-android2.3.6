package org.lichessold.net;

import java.util.LinkedHashMap;
import java.util.Map;

import org.lichessold.json.Json;
import org.lichessold.json.JsonException;
import org.lichessold.util.Log;

/**
 * Lichess API 封装（纯 Java）。
 *
 * 端点依据官方 openapi.yaml（2026-09 拉取，版本 2.0.174）核对过。
 * 所有方法要么返回解析好的 Json，要么抛 NetException —— 不返回半截数据。
 *
 * 速率限制：Lichess 对同一令牌的请求有配额，遇到 429 必须等至少 60 秒。
 * 这里统一在 call() 里处理。
 */
public final class LichessApi {

    public static final String HOST = "lichess.org";
    public static final int PORT = 443;

    /** 取消标志。UI 退出时把它翻成 true，流就会停下来。 */
    public interface Cancel {
        boolean cancelled();
    }

    /** 简单的取消标志实现，给 UI 用。 */
    public static final class Flag implements Cancel {
        private volatile boolean value;

        public void cancel() {
            value = true;
        }

        public boolean cancelled() {
            return value;
        }
    }

    /** NDJSON 每行回调。 */
    public interface JsonSink {
        void onJson(Json line);
    }

    private final Http http;
    private volatile String token = "";

    public LichessApi(TrustAnchors anchors) {
        this.http = new Http(HOST, PORT, anchors);
        this.http.setReadTimeout(25000);
    }

    public Http http() {
        return http;
    }

    public void setToken(String t) {
        this.token = t == null ? "" : t.trim();
    }

    public boolean hasToken() {
        return token.length() > 0;
    }

    public void close() {
        http.close();
    }

    public long lastHandshakeMillis() {
        return http.lastHandshakeMillis();
    }

    // ---------------------------------------------------------------- 基础

    private Map<String, String> jsonHeaders() {
        return Http.headers("application/json", token);
    }

    private Map<String, String> formHeaders() {
        return Http.formHeaders(token);
    }

    /** 发请求，处理 429。 */
    private HttpResponse call(String method, String path,
                              Map<String, String> headers, String body)
            throws NetException {
        HttpResponse r = http.request(method, path, headers, body);
        if (r.status == 429) {
            // Lichess 明确要求至少等 60 秒
            throw new NetException(NetException.STAGE_HTTP,
                    "请求太频繁被限流（HTTP 429）。请等 60 秒后再试。");
        }
        return r;
    }

    private Json json(HttpResponse r) throws NetException {
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
        try {
            return Json.parse(r.text());
        } catch (JsonException e) {
            throw new NetException(NetException.STAGE_HTTP,
                    "服务端返回的不是合法 JSON: " + e.getMessage());
        }
    }

    private static String explain(HttpResponse r) {
        String t = r.text();
        if (t.length() > 240) {
            t = t.substring(0, 240) + "...";
        }
        switch (r.status) {
            case 400:
                // 服务端的参数校验错误。把原文带出来，否则完全没法排查。
                return "请求参数被拒绝（HTTP 400）。" + (t.length() == 0 ? "" : "\n" + t);
            case 401:
                return "令牌无效或已过期（HTTP 401）。请在设置里重新填令牌。";
            case 403:
                return "没有权限（HTTP 403）。检查令牌是否勾选了 board:play。";
            case 404:
                return "找不到资源（HTTP 404）。";
            case 429:
                return "请求太频繁被限流（HTTP 429）。请等 60 秒后再试。";
            default:
                return "HTTP " + r.status + " " + r.reason + (t.length() == 0 ? "" : "\n" + t);
        }
    }

    // ---------------------------------------------------------------- 账号

    /** GET /api/account */
    public Json account() throws NetException {
        return json(call("GET", "/api/account", jsonHeaders(), null));
    }

    /** 取用户名；失败返回 null。 */
    public String accountUsername() throws NetException {
        Json j = account();
        String name = j.str("username", "");
        return name.length() == 0 ? null : name;
    }

    // ------------------------------------------------------------ 进行中对局

    /** GET /api/account/playing */
    public Json playing() throws NetException {
        return json(call("GET", "/api/account/playing", jsonHeaders(), null));
    }

    // ---------------------------------------------------------------- 事件流

    /**
     * GET /api/stream/event —— 长连接 NDJSON。
     * 事件类型：challenge / challengeCanceled / challengeDeclined / gameStart / gameFinish。
     * 空行是心跳。
     */
    public void streamEvents(final JsonSink sink, final Cancel cancel) throws NetException {
        http.setReadTimeout(60000);
        http.stream("GET", "/api/stream/event",
                Http.headers("application/x-ndjson", token), null,
                new Http.LineHandler() {
                    public boolean onLine(String line) {
                        if (cancel != null && cancel.cancelled()) {
                            return false;
                        }
                        Json j = Json.parseQuietly(line, null);
                        if (j == null) {
                            return true;
                        }
                        sink.onJson(j);
                        return cancel == null || !cancel.cancelled();
                    }
                });
    }

    /**
     * GET /api/board/game/stream/{gameId}
     * 首行必定是 gameFull，之后是 gameState / chatLine / opponentGone。
     * 对局结束时服务端会关闭连接。
     */
    public void streamGame(String gameId, final JsonSink sink, final Cancel cancel)
            throws NetException {
        http.setReadTimeout(60000);
        http.stream("GET", "/api/board/game/stream/" + gameId,
                Http.headers("application/x-ndjson", token), null,
                new Http.LineHandler() {
                    public boolean onLine(String line) {
                        if (cancel != null && cancel.cancelled()) {
                            return false;
                        }
                        Json j = Json.parseQuietly(line, null);
                        if (j == null) {
                            return true;
                        }
                        sink.onJson(j);
                        return cancel == null || !cancel.cancelled();
                    }
                });
    }

    // ---------------------------------------------------------------- 走子

    /** POST /api/board/game/{gameId}/move/{uci} */
    public void move(String gameId, String uci) throws NetException {
        HttpResponse r = call("POST", "/api/board/game/" + gameId + "/move/" + uci,
                Http.headers("application/json", token), "");
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
    }

    /** POST /api/board/game/{gameId}/resign */
    public void resign(String gameId) throws NetException {
        HttpResponse r = call("POST", "/api/board/game/" + gameId + "/resign",
                Http.headers("application/json", token), "");
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
    }

    /** POST /api/board/game/{gameId}/draw/{yes|no} */
    public void draw(String gameId, boolean accept) throws NetException {
        HttpResponse r = call("POST", "/api/board/game/" + gameId + "/draw/"
                + (accept ? "yes" : "no"),
                Http.headers("application/json", token), "");
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
    }

    /** POST /api/board/game/{gameId}/abort */
    public void abort(String gameId) throws NetException {
        HttpResponse r = call("POST", "/api/board/game/" + gameId + "/abort",
                Http.headers("application/json", token), "");
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
    }

    /** POST /api/board/game/{gameId}/takeback/{yes|no} */
    public void takeback(String gameId, boolean accept) throws NetException {
        HttpResponse r = call("POST", "/api/board/game/" + gameId + "/takeback/"
                + (accept ? "yes" : "no"),
                Http.headers("application/json", token), "");
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
    }

    /** POST /api/board/game/{gameId}/chat */
    public void chat(String gameId, String text, String room) throws NetException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("room", room == null ? "player" : room);
        form.put("text", text);
        HttpResponse r = call("POST", "/api/board/game/" + gameId + "/chat",
                formHeaders(), Http.formEncode(form));
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
    }

    // ---------------------------------------------------------------- 挑战

    /**
     * POST /api/challenge/ai
     *
     * @param level    AI 强度 1~8
     * @param limitSec 初始时间（秒）；<=0 表示不限制（通信棋）
     * @param incSec   每步加秒
     * @param color    "white" / "black" / "random"
     */
    public Json challengeAi(int level, int limitSec, int incSec, String color)
            throws NetException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("level", String.valueOf(level));
        form.put("color", color == null ? "random" : color);
        if (limitSec > 0) {
            form.put("clock.limit", String.valueOf(limitSec));
            form.put("clock.increment", String.valueOf(Math.max(0, incSec)));
        } else {
            form.put("days", "1");
        }
        return json(call("POST", "/api/challenge/ai", formHeaders(), Http.formEncode(form)));
    }

    /** POST /api/challenge/{id}/accept */
    public Json acceptChallenge(String challengeId) throws NetException {
        return json(call("POST", "/api/challenge/" + challengeId + "/accept",
                formHeaders(), ""));
    }

    /**
     * POST /api/challenge/{username} —— 向指定玩家发起挑战。
     *
     * @param rated true = 排位赛（影响双方分数），false = 休闲
     * @param limitSec 初始时间（秒）；<=0 表示通信棋
     * @param incSec   每步加秒
     * @param color    "white" / "black" / "random"
     */
    public Json challengeUser(String username, boolean rated, int limitSec, int incSec,
                              String color) throws NetException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("rated", rated ? "true" : "false");
        form.put("color", color == null ? "random" : color);
        if (limitSec > 0) {
            form.put("clock.limit", String.valueOf(limitSec));
            form.put("clock.increment", String.valueOf(Math.max(0, incSec)));
        } else {
            form.put("days", "1");
        }
        return json(call("POST", "/api/challenge/" + username, formHeaders(),
                Http.formEncode(form)));
    }

    /** POST /api/challenge/{id}/decline */
    public Json declineChallenge(String challengeId, String reason) throws NetException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        if (reason != null && reason.length() > 0) {
            form.put("reason", reason);
        }
        return json(call("POST", "/api/challenge/" + challengeId + "/decline",
                formHeaders(), Http.formEncode(form)));
    }

    // ---------------------------------------------------------------- 匹配

    /**
     * POST /api/board/seek —— 流式：连接保持期间一直在找对手，
     * 找到后服务端会在事件流里发 gameStart。
     * 调用方需要在事件流同时在线时使用。
     *
     * ⚠️ 单位陷阱（0.5.0 的 HTTP 400 就是踩了这个）：
     *   这个端点的 time 是**分钟**（Double，0~180，且只接受整数或 0.25/0.5/0.75/1.5），
     *   increment 是**秒**。
     *   它和 /api/challenge/* 的 clock.limit（秒）**不是一回事**。
     *   0.5.0 把秒当成分钟传，300/600/900/1800 全部超过上限 180，
     *   服务端直接回 {"time":["无效值"]}。
     *   依据：lila modules/setup/src/main/Config.scala
     *         validateTime: t >= 0 && t <= 180 && (t.isWhole || 0.25/0.5/0.75/1.5)
     *
     * ⚠️ 另一个坑：Board API（用个人令牌时）只允许 Rapid 及更慢的棋，
     *   见 lila `isBoardCompatible(clock) = Speed(clock) >= Speed.Rapid`，
     *   即 limit + 40 * increment >= 480 秒。否则报 "Invalid time control"。
     *   所以调用方必须先用 {@link #boardSeekCompatible} 过滤时间档。
     *
     * @param timeMinutes 初始时间（**分钟**）
     * @param incSec      每步加秒（**秒**）
     */
    public void seek(double timeMinutes, int incSec, boolean rated, final JsonSink sink,
                     final Cancel cancel) throws NetException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("rated", rated ? "true" : "false");
        form.put("time", formatMinutes(timeMinutes));
        form.put("increment", String.valueOf(Math.max(0, incSec)));
        http.setReadTimeout(60000);
        http.stream("POST", "/api/board/seek", formHeaders(), Http.formEncode(form),
                new Http.LineHandler() {
                    public boolean onLine(String line) {
                        if (cancel != null && cancel.cancelled()) {
                            return false;
                        }
                        Json j = Json.parseQuietly(line, null);
                        if (j != null && sink != null) {
                            sink.onJson(j);
                        }
                        return cancel == null || !cancel.cancelled();
                    }
                });
    }

    /**
     * DELETE /api/board/seek —— 主动撤销正在进行的寻找。
     *
     * 其实关掉连接服务端也会撤销，但那是"被动"的：我们的读循环要等到
     * 下一个空行心跳才可能退出，最长可能拖十几秒。显式 DELETE 是立即生效。
     * 失败也无所谓，不影响主流程。
     */
    public void cancelSeek() {
        try {
            call("DELETE", "/api/board/seek", formHeaders(), "");
        } catch (NetException e) {
            Log.w("Api", "撤销 seek 失败（忽略）: " + e.getMessage());
        }
    }

    /**
     * 分钟数 -> 表单字符串。
     * Play 的 Double 绑定 "10" 和 "10.0" 都收，但整数不带小数点更干净。
     */
    public static String formatMinutes(double minutes) {
        if (Double.isNaN(minutes) || Double.isInfinite(minutes)) {
            return "0";
        }
        double v = Math.max(0, minutes);
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * 这个时间控制能不能用于 Board API 的寻找对手。
     *
     * lila: isBoardCompatible(clock) = Speed(clock) >= Speed.Rapid
     * Speed 按 estimateTotalSeconds = limit + 40 * increment 分档，
     * Rapid 的区间是 480 ~ 1499 秒。所以下限就是 480。
     */
    public static boolean boardSeekCompatible(int limitSec, int incSec) {
        return limitSec + 40 * Math.max(0, incSec) >= 480;
    }

    /**
     * 从 /api/challenge/ai 的响应里取出"我方执什么颜色"。
     *
     * 注意字段名是 **player** 不是 color —— 0.5.0 读的是 color，
     * 结果永远拿不到值，棋盘方向和走子权限全错
     * （这正是"对战时无法下子"的一半原因）。
     * 依据 openapi：/api/challenge/ai 的 201 响应里是
     *   player: GameColor(white|black)   // "Which color you get to play"
     * 同时也兼容一下 color，免得以后服务端改名。
     */
    public static String myColorFromChallenge(Json j) {
        if (j == null) {
            return "";
        }
        String c = j.str("player", "");
        if (!isColor(c)) {
            c = j.str("color", "");
        }
        return isColor(c) ? c : "";
    }

    private static boolean isColor(String s) {
        return "white".equals(s) || "black".equals(s);
    }

    // ---------------------------------------------------------------- 谜题

    /** GET /api/puzzle/daily —— 每日一题，带 fen，可直接摆盘。 */
    public Json puzzleDaily() throws NetException {
        return json(call("GET", "/api/puzzle/daily", jsonHeaders(), null));
    }

    /** GET /api/puzzle/next —— 随机一题，**不带 fen**，要用 PGN 重建局面。 */
    public Json puzzleNext() throws NetException {
        return json(call("GET", "/api/puzzle/next", jsonHeaders(), null));
    }

    /** GET /api/puzzle/{id} */
    public Json puzzle(String id) throws NetException {
        return json(call("GET", "/api/puzzle/" + id, jsonHeaders(), null));
    }

    /**
     * GET /api/puzzle/batch/{angle}
     * angle: mix / short / long / veryLong / mate / mateIn1 / mateIn2 / mateIn3 /
     *        advantage / crushing / equality / endgame / middlegame / opening / rookEndgame 等
     */
    public Json puzzleBatch(String angle, int count) throws NetException {
        return json(call("GET", "/api/puzzle/batch/" + angle + "?nb=" + count,
                jsonHeaders(), null));
    }

    // ---------------------------------------------------------------- 用户

    /** GET /api/user/{name} */
    public Json user(String name) throws NetException {
        return json(call("GET", "/api/user/" + name, jsonHeaders(), null));
    }

    /** GET /api/user/{name}，解析成结构化对象。 */
    public LichessUser userProfile(String name) throws NetException {
        return LichessUser.parse(user(name));
    }

    /**
     * 当前登录用户的资料。
     * 先用 /api/account 拿用户名，再用 /api/user/{name} 拿完整资料
     * —— 因为 /api/account 里没有战绩统计（count）。
     */
    public LichessUser myProfile() throws NetException {
        String name = accountUsername();
        if (name == null) {
            throw new NetException(NetException.STAGE_HTTP, "拿不到当前账号的用户名");
        }
        return userProfile(name);
    }

    /**
     * GET /api/user/{name}/current-game —— 拿某人当前（或最近一局）的完整 PGN。
     *
     * 历史坑：原先这里打的是 {@code /api/games/user/{name}?max=n}，
     * 那个端点在 2026 年实测已经返回 404，整条导出链是断的。
     * 现在改用依然有效的 current-game，并且只要一局（免登录，Anonymous 也能读）。
     *
     * 注意：正在进行的对局服务器会**延迟 3 步**才吐出来，防作弊用的，这是设计如此。
     *
     * @return PGN 文本；对方没有对局时返回空串（服务端 404，不是错误）
     */
    public String lastGamePgn(String name) throws NetException {
        HttpResponse r = call("GET",
                "/api/user/" + name + "/current-game?moves=true&tags=true&clocks=false",
                Http.headers("application/x-chess-pgn", null), null);
        if (r.status == 404) {
            return "";      // 没有对局，不算错误
        }
        if (!r.isOk()) {
            throw new NetException(NetException.STAGE_HTTP, explain(r));
        }
        return r.text();
    }

    /**
     * 兼容旧调用名。语义已从「导出最近 N 局」收窄成「取最近一局」。
     * @param max 忽略，仅为兼容旧签名保留
     */
    public String gamesPgn(String name, int max) throws NetException {
        return lastGamePgn(name);
    }

    /**
     * 取单局 PGN。
     *
     * 历史坑：原先打 {@code /game/export/{id}.pgn}，同样实测 404。
     * 现在退化成「取该 ID 对应玩家的最近一局」，拿不到就返回空串。
     * 如果要精确按 ID 取，需要 /api/games/export/_ids，但那个端点同样已 404，
     * 所以这里不再假装能做到。
     */
    public String gamePgn(String gameId) throws NetException {
        return "";
    }

    // -------------------------------------------------------------- 锦标赛

    /**
     * GET /api/tournament —— 锦标赛日程（最近 / 正在进行 / 已结束）。
     * 免登录。返回 {created:[], started:[], finished:[]}。
     */
    public Json tournaments() throws NetException {
        return json(call("GET", "/api/tournament",
                Http.headers("application/json", null), null));
    }

    /**
     * GET /api/tournament/{id} —— 单个锦标赛详情，含排行榜与焦点对局。
     * 免登录。page 是排行榜翻页（1~200）。
     */
    public Json tournament(String id, int page) throws NetException {
        return json(call("GET",
                "/api/tournament/" + id + "?page=" + Math.max(1, Math.min(200, page)),
                Http.headers("application/json", null), null));
    }

    // -------------------------------------------------------------- 战绩

    /**
     * GET /api/user/{name}/activity —— 按天分桶的战绩，含锦标赛最佳名次。
     * 免登录。返回的是一个**数组**（不是对象），每天一项。
     */
    public Json userActivity(String name) throws NetException {
        return json(call("GET", "/api/user/" + name + "/activity",
                Http.headers("application/json", null), null));
    }

    // ---------------------------------------------------------------- 观战

    /** GET /api/tv/feed —— 公开的焦点对局流，不需要令牌。 */
    public void streamTvFeed(final JsonSink sink, final Cancel cancel) throws NetException {
        http.setReadTimeout(60000);
        http.stream("GET", "/api/tv/feed",
                Http.headers("application/x-ndjson", null), null,
                new Http.LineHandler() {
                    public boolean onLine(String line) {
                        if (cancel != null && cancel.cancelled()) {
                            return false;
                        }
                        Json j = Json.parseQuietly(line, null);
                        if (j != null && sink != null) {
                            sink.onJson(j);
                        }
                        return cancel == null || !cancel.cancelled();
                    }
                });
    }

    /** GET /api/tv/{channel} —— 某个频道的当前对局（一次性）。 */
    public Json tvChannel(String channel) throws NetException {
        return json(call("GET", "/api/tv/" + channel,
                Http.headers("application/json", null), null));
    }

    // ---------------------------------------------------------------- 云分析

    /**
     * GET /api/cloud-eval —— Lichess 现成的云分析结果（不是每个局面都有）。
     * 有的话能白拿 Stockfish 的评分，比在手机上跑引擎划算得多。
     */
    public Json cloudEval(String fen, int multiPv) throws NetException {
        HttpResponse r = call("GET",
                "/api/cloud-eval?" + Http.formEncode(single("fen", fen))
                        + "&multiPv=" + Math.max(1, Math.min(5, multiPv)),
                Http.headers("application/json", null), null);
        if (r.status == 404) {
            return null;   // 这个局面没有云分析结果，不算错误
        }
        return json(r);
    }

    private static Map<String, String> single(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }
}
