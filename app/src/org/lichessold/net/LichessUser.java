package org.lichessold.net;

import java.util.LinkedHashMap;

import org.lichessold.json.Json;

/**
 * Lichess 用户资料。字段按 2026-09 实测的 /api/user/{name} 响应核对过。
 *
 * 顶层：id, username, title?, patron?, verified?, flair?, streamer?, url,
 *       createdAt, seenAt, perfs, count, playTime, profile
 * perf 每项：{games, rating, rd, prog, prov?}
 * count：{all, rated, draw, loss, win, bookmark, playing, import, me}
 * playTime：{total, tv}（秒）
 */
public final class LichessUser {

    /** 单个分项的排位信息。 */
    public static final class Perf {
        public final String key;
        public int games;
        public int rating;
        public int rd;
        public int prog;
        public boolean provisional;

        Perf(String key) {
            this.key = key;
        }

        /** 下过棋才有分数可谈。 */
        public boolean hasRating() {
            return games > 0 && rating > 0;
        }

        /** 临时分（RD 高说明分数还不准）。 */
        public boolean isProvisional() {
            return provisional || rd > 110;
        }
    }

    public String id = "";
    public String username = "";
    public String title = "";
    public String flair = "";
    public String url = "";
    public boolean patron;
    public boolean verified;
    public boolean streamer;

    public long createdAt;
    public long seenAt;

    public String realName = "";
    public String bio = "";
    public String country = "";

    public long playTimeTotal;
    public long playTimeTv;

    public int countAll;
    public int countRated;
    public int countWin;
    public int countLoss;
    public int countDraw;
    public int countPlaying;

    /** key -> Perf，保持 JSON 里的顺序。 */
    public final LinkedHashMap<String, Perf> perfs = new LinkedHashMap<String, Perf>();

    // -------------------------------------------------------------- 解析

    public static LichessUser parse(Json j) {
        LichessUser u = new LichessUser();
        if (j == null) {
            return u;
        }
        u.id = j.str("id", "");
        u.username = j.str("username", u.id);
        u.title = j.str("title", "");
        u.flair = j.str("flair", "");
        u.url = j.str("url", "");
        u.patron = j.b("patron", false);
        u.verified = j.b("verified", false);
        u.streamer = j.obj("streamer").size() > 0;
        u.createdAt = j.l("createdAt", 0);
        u.seenAt = j.l("seenAt", 0);

        Json profile = j.obj("profile");
        u.realName = profile.str("realName", "");
        u.bio = profile.str("bio", "");
        u.country = profile.str("country", "");

        Json pt = j.obj("playTime");
        u.playTimeTotal = pt.l("total", 0);
        u.playTimeTv = pt.l("tv", 0);

        Json c = j.obj("count");
        u.countAll = c.i("all", 0);
        u.countRated = c.i("rated", 0);
        u.countWin = c.i("win", 0);
        u.countLoss = c.i("loss", 0);
        u.countDraw = c.i("draw", 0);
        u.countPlaying = c.i("playing", 0);

        Json p = j.obj("perfs");
        String[] keys = p.keys();
        for (int i = 0; i < keys.length; i++) {
            Json one = p.obj(keys[i]);
            if (one.size() == 0) {
                continue;
            }
            Perf perf = new Perf(keys[i]);
            perf.games = one.i("games", 0);
            perf.rating = one.i("rating", 0);
            perf.rd = one.i("rd", 0);
            perf.prog = one.i("prog", 0);
            perf.provisional = one.b("prov", false);
            u.perfs.put(keys[i], perf);
        }
        return u;
    }

    public Perf perf(String key) {
        return perfs.get(key);
    }

    public int ratingOf(String key) {
        Perf p = perfs.get(key);
        return p == null ? 0 : p.rating;
    }

    /** 主力的三个分项里的最高分，用于菜单上显示"我的分数"。 */
    public String bestRatingLabel() {
        String[] order = { "blitz", "rapid", "bullet", "classical", "correspondence" };
        String bestKey = null;
        int best = 0;
        for (int i = 0; i < order.length; i++) {
            Perf p = perfs.get(order[i]);
            if (p != null && p.hasRating() && p.rating > best) {
                best = p.rating;
                bestKey = order[i];
            }
        }
        if (bestKey == null) {
            return "";
        }
        return displayName(bestKey) + " " + best;
    }

    /** 总胜率（0~100），没下过棋返回 -1。 */
    public int winRate() {
        int total = countWin + countLoss + countDraw;
        if (total <= 0) {
            return -1;
        }
        return (int) Math.round(countWin * 100.0 / total);
    }

    // -------------------------------------------------------------- 显示名

    /** 分项 key -> 中文名。 */
    public static String displayName(String key) {
        if (key == null) {
            return "";
        }
        if (key.equals("ultraBullet")) {
            return "超快棋";
        }
        if (key.equals("bullet")) {
            return "子弹棋";
        }
        if (key.equals("blitz")) {
            return "闪电棋";
        }
        if (key.equals("rapid")) {
            return "快速棋";
        }
        if (key.equals("classical")) {
            return "经典棋";
        }
        if (key.equals("correspondence")) {
            return "通信棋";
        }
        if (key.equals("chess960")) {
            return "Chess960";
        }
        if (key.equals("crazyhouse")) {
            return "疯狂屋";
        }
        if (key.equals("antichess")) {
            return "反棋";
        }
        if (key.equals("atomic")) {
            return "原子棋";
        }
        if (key.equals("horde")) {
            return "虫群";
        }
        if (key.equals("kingOfTheHill")) {
            return "山丘之王";
        }
        if (key.equals("racingKings")) {
            return "竞速王";
        }
        if (key.equals("threeCheck")) {
            return "三次将军";
        }
        if (key.equals("puzzle")) {
            return "谜题";
        }
        if (key.equals("storm")) {
            return "谜题风暴";
        }
        if (key.equals("racer")) {
            return "谜题竞速";
        }
        if (key.equals("streak")) {
            return "连胜";
        }
        return key;
    }

    /**
     * 主页上要显示的分项，按重要性排序。
     * 不在这里的前面先列常用项目，冷门变体放后面。
     */
    public static final String[] PROFILE_ORDER = {
            "bullet", "blitz", "rapid", "classical", "correspondence",
            "ultraBullet", "chess960", "crazyhouse", "threeCheck",
            "kingOfTheHill", "antichess", "atomic", "horde", "racingKings",
            "puzzle", "storm", "racer",
    };

    /** 把时间戳（毫秒）格式化成 yyyy-MM-dd。 */
    public static String formatDate(long millis) {
        if (millis <= 0) {
            return "?";
        }
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return f.format(new java.util.Date(millis));
    }

    /** 秒 -> "1234 小时" / "12.3 天"。 */
    public static String formatPlayTime(long seconds) {
        if (seconds <= 0) {
            return "0 小时";
        }
        long hours = seconds / 3600;
        if (hours >= 24) {
            long days = hours / 24;
            long rest = hours % 24;
            return days + " 天 " + rest + " 小时";
        }
        return hours + " 小时";
    }
}
