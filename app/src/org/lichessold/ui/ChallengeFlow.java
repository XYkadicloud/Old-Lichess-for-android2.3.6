package org.lichessold.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;

import org.lichessold.json.Json;
import org.lichessold.net.ChallengeOptions;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 「发起挑战」的公共流程：选排位/休闲 → 选时间 → 发请求。
 *
 * 为什么单独抽出来：对局大厅（挑战电脑、挑战玩家）和个人主页（挑战 TA）
 * 都要用这套，复制三遍迟早会走样。
 *
 * ⚠️ 单位说明：这里的时间表是给 /api/challenge/* 用的，clock.limit 是**秒**。
 *    和 /api/board/seek 的 time（**分钟**）完全是两码事，别混。
 *
 * 关于排位与休闲（用户最关心的"下棋会不会加减分"）：
 *   - rated=true  排位赛：结果会加减双方的等级分（Glicko-2），主页上能看到变化
 *   - rated=false 休闲赛：不计分
 *   - 和电脑下永远是休闲 —— /api/challenge/ai 根本没有 rated 字段，
 *     服务端固定 rated=false（openapi 示例里就是 "rated": false, "source": "ai"）
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
    public static void challengeUser(final Activity a, final String presetUsername) {
        if (presetUsername != null && presetUsername.length() > 0) {
            pickMode(a, presetUsername);
            return;
        }
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("对方的 lichess 用户名");
        new AlertDialog.Builder(a)
                .setTitle("挑战玩家")
                .setView(input)
                .setPositiveButton("下一步", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) {
                            Ui.toastShort(a, "用户名不能为空");
                            return;
                        }
                        pickMode(a, name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选排位还是休闲。 */
    private static void pickMode(final Activity a, final String username) {
        final Prefs prefs = Prefs.get(a);
        final String[] modes = { "排位赛（计分，会影响等级分）", "休闲赛（不计分）" };
        Ui.choose(a, "挑战 " + username, modes, prefs.isRatedDefault() ? 0 : 1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        final boolean rated = which == 0;
                        prefs.setRatedDefault(rated);
                        pickTime(a, username, rated);
                    }
                });
    }

    private static void pickTime(final Activity a, final String username, final boolean rated) {
        Ui.choose(a, (rated ? "排位赛" : "休闲赛") + " · 选时间", LABELS, DEFAULT_INDEX,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        send(a, username, rated, VALUES[which][0], VALUES[which][1]);
                    }
                });
    }

    private static void send(final Activity a, final String username, final boolean rated,
                             final int limit, final int inc) {
        Ui.toastShort(a, "正在向 " + username + " 发起挑战…");
        Async.run("challengeUser", new Async.Job<Json>() {
            public Json run() throws Throwable {
                LichessApi api = Net.api(a);
                return api.challengeUser(username, rated, limit, inc, "random");
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (a.isFinishing()) {
                    return;
                }
                if (error != null) {
                    Log.w("Challenge", "挑战 " + username + " 失败: " + error.getMessage());
                    Ui.toast(a, "挑战失败：\n" + friendly(error));
                    return;
                }
                String id = j.str("id", "");
                Log.i("Challenge", "挑战已创建 id=" + id + " rated=" + rated
                        + " " + limit + "+" + inc);
                Ui.toast(a, "挑战已发出（" + (rated ? "排位" : "休闲") + " "
                        + (limit / 60) + "+" + inc + "）。\n"
                        + "对方接受后会开局 —— 到「在线对局」页面等，"
                        + "那里挂着事件流。");
            }
        });
    }

    // ------------------------------------------------------------ 挑战电脑

    /** 挑战电脑：选等级 → 选时间。电脑对局固定休闲，不计分。 */
    public static void challengeAi(final Activity a, final int level) {
        Ui.choose(a, "时间（电脑等级 " + level + "，不计分）", LABELS, DEFAULT_INDEX,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        sendAi(a, level, VALUES[which][0], VALUES[which][1]);
                    }
                });
    }

    private static void sendAi(final Activity a, final int level,
                               final int limit, final int inc) {
        Async.run("challengeAi", new Async.Job<Json>() {
            public Json run() throws Throwable {
                LichessApi api = Net.api(a);
                return api.challengeAi(level, limit, inc, "random");
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (a.isFinishing()) {
                    return;
                }
                if (error != null) {
                    Log.w("Challenge", "挑战电脑失败: " + error.getMessage());
                    Ui.toast(a, "创建对局失败：\n" + friendly(error));
                    return;
                }
                String id = j.str("id", "");
                // 颜色字段是 player，不是 color（openapi 里写得很清楚）
                String color = LichessApi.myColorFromChallenge(j);
                Log.i("Challenge", "挑战电脑成功 id=" + id + " 我执 " + color);
                if (id.length() == 0) {
                    Ui.toast(a, "服务端没有返回对局 ID");
                    return;
                }
                if (a instanceof GamesActivity) {
                    ((GamesActivity) a).openGame(id, color);
                }
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
