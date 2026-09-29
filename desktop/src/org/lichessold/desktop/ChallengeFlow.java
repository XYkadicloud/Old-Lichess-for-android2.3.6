package org.lichessold.desktop;

import java.awt.Component;

import org.lichessold.json.Json;
import org.lichessold.net.ChallengeOptions;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.util.Log;

/**
 * 「发起挑战」的公共流程：选排位/休闲 → 选时间 → 发请求。
 * 对应 Android 版的 {@code org.lichessold.ui.ChallengeFlow}。
 *
 * 为什么单独抽出来：对局大厅（挑战电脑、挑战玩家）和个人主页（挑战 TA）
 * 都要用这套，复制三遍迟早会走样。
 *
 * ⚠️ 单位说明：这里的时间表是给 /api/challenge/* 用的，clock.limit 是**秒**。
 *    和 /api/board/seek 的 time（**分钟**）完全是两码事，别混。
 *
 * 关于排位与休闲（用户最关心的"下棋会不会加减分"）：
 *   - rated=true  排位赛：结果会加减双方的等级分（Glicko-2）
 *   - rated=false 休闲赛：不计分
 *   - 和电脑下永远是休闲 —— /api/challenge/ai 根本没有 rated 字段
 */
public final class ChallengeFlow {

    /** 时间档标签。表本身在纯 Java 层，这样桌面测试能把每一项都验一遍。 */
    public static final String[] LABELS = ChallengeOptions.LABELS;

    /** 对应的 {clock.limit 秒, clock.increment 秒}。 */
    public static final int[][] VALUES = ChallengeOptions.VALUES;

    /** 默认高亮的时间档（10+0 快速）。 */
    public static final int DEFAULT_INDEX = ChallengeOptions.DEFAULT_INDEX;

    private ChallengeFlow() {
    }

    // ------------------------------------------------------------ 挑战玩家

    /** 弹输入框让对方用户名，然后走两级选择。 */
    public static void challengeUser(Component parent, String presetUsername) {
        if (presetUsername != null && presetUsername.length() > 0) {
            pickMode(parent, presetUsername);
            return;
        }
        String name = DesktopApp.prompt(parent, "挑战玩家", "对方的 lichess 用户名", "");
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.length() == 0) {
            DesktopApp.toast(parent, "用户名不能为空");
            return;
        }
        pickMode(parent, name);
    }

    /** 选排位还是休闲。 */
    private static void pickMode(final Component parent, final String username) {
        final DesktopPrefs prefs = DesktopPrefs.get();
        String[] modes = { "排位赛（计分，会影响等级分）", "休闲赛（不计分）" };
        Object pick = DesktopApp.choose(parent, "挑战 " + username,
                "选择比赛模式", modes, modes[prefs.isRatedDefault() ? 0 : 1]);
        if (pick == null) {
            return;
        }
        final boolean rated = modes[0].equals(pick);
        prefs.setRatedDefault(rated);
        pickTime(parent, username, rated);
    }

    private static void pickTime(final Component parent, final String username,
                                 final boolean rated) {
        Object pick = DesktopApp.choose(parent, (rated ? "排位赛" : "休闲赛") + " · 选时间",
                "选择时间控制", LABELS, LABELS[DEFAULT_INDEX]);
        if (pick == null) {
            return;
        }
        for (int i = 0; i < LABELS.length; i++) {
            if (LABELS[i].equals(pick)) {
                send(parent, username, rated, VALUES[i][0], VALUES[i][1]);
                return;
            }
        }
    }

    private static void send(final Component parent, final String username,
                             final boolean rated, final int limit, final int inc) {
        DesktopApp.toast(parent, "正在向 " + username + " 发起挑战…");
        DesktopAsync.run("challengeUser", new DesktopAsync.Job<Json>() {
            public Json run() throws Throwable {
                LichessApi api = DesktopNet.api();
                return api.challengeUser(username, rated, limit, inc, "random");
            }
        }, new DesktopAsync.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (error != null) {
                    Log.w("Challenge", "挑战 " + username + " 失败: " + error.getMessage());
                    DesktopApp.toast(parent, "挑战失败：\n" + friendly(error));
                    return;
                }
                String id = j.str("id", "");
                Log.i("Challenge", "挑战已创建 id=" + id + " rated=" + rated
                        + " " + limit + "+" + inc);
                DesktopApp.toast(parent, "挑战已发出（" + (rated ? "排位" : "休闲") + " "
                        + (limit / 60) + "+" + inc + "）。\n"
                        + "对方接受后会开局 —— 到「在线对局」页面等，那里挂着事件流。");
            }
        });
    }

    // ------------------------------------------------------------ 挑战电脑

    /** 挑战电脑：选等级 → 选时间。电脑对局固定休闲，不计分。 */
    public static void challengeAi(final Component parent, final int level) {
        Object pick = DesktopApp.choose(parent, "挑战电脑",
                "电脑等级 " + level + "（不计分）· 选择时间", LABELS, LABELS[DEFAULT_INDEX]);
        if (pick == null) {
            return;
        }
        for (int i = 0; i < LABELS.length; i++) {
            if (LABELS[i].equals(pick)) {
                sendAi(parent, level, VALUES[i][0], VALUES[i][1]);
                return;
            }
        }
    }

    private static void sendAi(final Component parent, final int level,
                               final int limit, final int inc) {
        DesktopAsync.run("challengeAi", new DesktopAsync.Job<Json>() {
            public Json run() throws Throwable {
                LichessApi api = DesktopNet.api();
                return api.challengeAi(level, limit, inc, "random");
            }
        }, new DesktopAsync.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (error != null) {
                    Log.w("Challenge", "挑战电脑失败: " + error.getMessage());
                    DesktopApp.toast(parent, "创建对局失败：\n" + friendly(error));
                    return;
                }
                String id = j.str("id", "");
                // 颜色字段是 player，不是 color（openapi 里写得很清楚）
                String color = LichessApi.myColorFromChallenge(j);
                Log.i("Challenge", "挑战电脑成功 id=" + id + " 我执 " + color);
                if (id.length() == 0) {
                    DesktopApp.toast(parent, "服务端没有返回对局 ID");
                    return;
                }
                DesktopApp.openOnlineGame(id, color);
            }
        });
    }

    // ------------------------------------------------------------ 工具

    private static String friendly(Throwable error) {
        if (error instanceof NetException) {
            return error.getMessage();
        }
        String m = error == null ? null : error.getMessage();
        return m == null || m.length() == 0 ? String.valueOf(error) : m;
    }
}
