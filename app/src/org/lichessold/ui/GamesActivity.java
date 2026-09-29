package org.lichessold.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.net.SeekOptions;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 对局大厅：进行中的对局 + 收到的挑战 + 开新局。
 *
 * 同时挂着 /api/stream/event 事件流，收到 gameStart 自动进入对局界面，
 * 收到 challenge 就在列表里显示接受/拒绝按钮。
 *
 * 关于「找真人对手」的时间档（0.5.0 在这里踩了坑，记下来别再犯）：
 *
 *   1) /api/board/seek 的 time 单位是**分钟**，不是秒。
 *      传秒的话 300/600/900/1800 全部超过上限 180，服务端回
 *      {"time": ["无效值"]} —— 这就是那个 HTTP 400。
 *
 *   2) 用个人令牌时 Board API 只允许 **Rapid 及更慢**的棋：
 *      lila 里 isBoardCompatible(clock) = Speed(clock) >= Speed.Rapid，
 *      而 Speed 按 estimateTotalSeconds = limit + 40 * increment 分档，
 *      Rapid 的下限是 480 秒。所以 1+0 / 3+0 / 5+3 这类一律会被拒
 *      （错误是 "Invalid time control"，不是字段级错误）。
 *      下面 SEEK_* 这套表只列 480 秒以上的档位。
 *
 *   想下更快的棋只有两条路：去 lichess 官网/官方 App，或者用挑战玩家
 *   直接点名对手（/api/challenge/{username} 没有这个限制，clock.limit 用秒）。
 */
public class GamesActivity extends Activity {

    /**
     * 找对手的时间档放在 {@link org.lichessold.net.SeekOptions} 里
     * —— 那是协议知识，放纯 Java 层才能在桌面测试里把每个档位都验一遍。
     * 这里只负责把标签显示出来。
     */
    private static final String[] SEEK_LABELS = org.lichessold.net.SeekOptions.LABELS;

    private Prefs prefs;
    private LichessApi api;
    private final LichessApi.Flag cancelFlag = new LichessApi.Flag();

    private LinearLayout list;
    private TextView statusText;
    private Button seekButton;
    private boolean loading;
    /** 是否已经挂上事件流并拉过一次列表。 */
    private boolean started;

    /** 当前正在进行的寻找。为 null 表示没在找。 */
    private volatile LichessApi.Flag currentSeek;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        LinearLayout root = Ui.column(this);
        root.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        root.addView(Theme.header(this, "在线对局"),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        statusText = Ui.centered(this, "准备中…", Ui.TEXT_SMALL, Ui.ACCENT);
        root.addView(statusText);

        Button challengeAi = Ui.smallButton(this, "挑战电脑");
        challengeAi.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickAiLevel();
            }
        });

        Button challengeUser = Ui.smallButton(this, "挑战玩家");
        challengeUser.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ChallengeFlow.challengeUser(GamesActivity.this, null);
            }
        });

        root.addView(Ui.buttonRow(this, challengeAi, challengeUser));

        seekButton = Theme.smallPrimary(this, "找真人对手");
        seekButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickSeek();
            }
        });
        root.addView(Ui.buttonRow(this, seekButton));

        Button refresh = Ui.smallButton(this, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadOngoing();
            }
        });

        Button profile = Ui.smallButton(this, "我的主页");
        profile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(ProfileActivity.intentFor(GamesActivity.this, ""));
            }
        });

        Button back = Ui.smallButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        root.addView(Ui.buttonRow(this, refresh, profile, back));

        list = Ui.column(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0A0A0A);
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));

        setContentView(root);

        try {
            api = Net.api(this);
        } catch (NetException e) {
            statusText.setText("网络层初始化失败");
            Ui.toast(this, e.getMessage());
            return;
        }

        if (!prefs.hasToken()) {
            statusText.setText("还没有设置令牌，请先到「设置」里填");
            addNote("点「返回」→「设置」，填入 Lichess API 令牌。");
            return;
        }

        startWorking();
    }

    /** 首次挂事件流 + 拉列表。 */
    private void startWorking() {
        if (started) {
            return;
        }
        started = true;
        clearList();
        loadOngoing();
        startEventStream();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 第一次进来时可能还没令牌，用户去设置里填完回来要能立刻用起来
        if (!started && prefs != null && prefs.hasToken()) {
            startWorking();
        }
    }

    @Override
    protected void onDestroy() {
        // 关掉页面就把寻找停掉 —— 服务端会在连接断开时自动撤销 seek
        LichessApi.Flag f = currentSeek;
        if (f != null) {
            f.cancel();
        }
        cancelFlag.cancel();
        super.onDestroy();
    }

    // -------------------------------------------------------------- 列表

    private void clearList() {
        list.removeAllViews();
    }

    private void addNote(String text) {
        TextView t = Ui.text(this, text, Ui.TEXT_SMALL, Ui.FG_DIM);
        t.setGravity(Gravity.CENTER);
        list.addView(t);
    }

    private void addHeader(String text) {
        TextView t = Ui.text(this, text, Ui.TEXT_SMALL, Ui.ACCENT);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 1));
        list.addView(t);
    }

    private void addGameRow(final String gameId, String text) {
        Button b = Ui.smallButton(this, text);
        b.setGravity(Gravity.LEFT);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openGame(gameId, "");
            }
        });
        list.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addChallengeRow(final Json ch) {
        final String id = ch.str("id", "");
        final String name = ch.obj("challenger").str("name", "?");
        final int rating = ch.obj("challenger").i("rating", 0);
        final String variant = ch.obj("variant").str("key", "standard");
        final boolean rated = ch.b("rated", false);
        String time = describeTimeControl(ch);
        String label = name + (rating > 0 ? " (" + rating + ")" : "")
                + "\n" + time + (rated ? " · 排位赛（计分）" : " · 休闲赛")
                + (("standard".equals(variant)) ? "" : " · " + variant);

        LinearLayout rowBox = Ui.column(this);
        TextView t = Ui.text(this, label, Ui.TEXT_SMALL, Ui.FG);
        rowBox.addView(t);

        Button accept = Ui.smallButton(this, "接受");
        accept.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                respondChallenge(id, true);
            }
        });
        Button decline = Ui.smallButton(this, "拒绝");
        decline.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                respondChallenge(id, false);
            }
        });
        rowBox.addView(Ui.buttonRow(this, accept, decline));
        list.addView(rowBox);
    }

    private static String describeTimeControl(Json ch) {
        Json tc = ch.obj("timeControl");
        int limit = tc.i("limit", 0);
        int inc = tc.i("increment", 0);
        if (limit <= 0) {
            int days = tc.i("daysPerTurn", 0);
            return days > 0 ? days + " 天/步" : "无限制";
        }
        return (limit / 60) + "+" + inc;
    }

    // -------------------------------------------------------------- 网络

    private void loadOngoing() {
        if (loading) {
            return;
        }
        loading = true;
        statusText.setText("正在获取进行中的对局…");
        Async.run("playing", new Async.Job<Json>() {
            public Json run() throws Throwable {
                return api.playing();
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                loading = false;
                if (isFinishing()) {
                    return;
                }
                clearList();
                if (error != null) {
                    statusText.setText("获取失败");
                    addNote("获取进行中对局失败：\n" + error.getMessage());
                    Log.w("Lobby", "playing 失败: " + error);
                    return;
                }
                Json nowPlaying = j.arr("nowPlaying");
                if (nowPlaying.size() == 0) {
                    statusText.setText("当前没有进行中的对局");
                } else {
                    statusText.setText("进行中：" + nowPlaying.size() + " 局");
                    addHeader("进行中的对局");
                    for (int i = 0; i < nowPlaying.size(); i++) {
                        Json g = nowPlaying.at(i);
                        if (g == null) {
                            continue;
                        }
                        String id = g.str("gameId", "");
                        String opp = g.str("opponent", "");
                        if (opp == null || opp.length() == 0) {
                            opp = g.obj("opponent").str("username", "?");
                        }
                        String color = g.str("color", "?");
                        String status = g.str("status", "");
                        addGameRow(id, "vs " + opp + "  执" + ("white".equals(color) ? "白" : "黑")
                                + "  " + LichessApiStatus(status));
                    }
                }
                if (list.getChildCount() == 0) {
                    addNote("点上面的「挑战电脑」开一局试试。");
                }
            }
        });
    }

    private static String LichessApiStatus(String s) {
        return org.lichessold.net.LichessGame.statusText(s);
    }

    private void startEventStream() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                int backoff = 2000;
                while (!cancelFlag.cancelled()) {
                    try {
                        api.streamEvents(new LichessApi.JsonSink() {
                            public void onJson(Json line) {
                                handleEvent(line);
                            }
                        }, cancelFlag);
                        break;
                    } catch (final NetException e) {
                        if (cancelFlag.cancelled()) {
                            break;
                        }
                        Log.w("Lobby", "事件流断开: " + e.getMessage()
                                + "， " + backoff + "ms 后重连");
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            break;
                        }
                        backoff = Math.min(backoff * 2, 30000);
                    } catch (Throwable t) {
                        Log.e("Lobby", "事件流异常", t);
                        break;
                    }
                }
            }
        }, "lichessold-events");
        t.setDaemon(true);
        t.start();
    }

    private void handleEvent(Json j) {
        final String type = j.str("type", "");
        Log.i("Lobby", "事件: " + type);
        if ("gameStart".equals(type)) {
            final String id = j.obj("game").str("id", "");
            final String color = j.obj("game").str("color", "");
            // 配到对手了，寻找自然结束
            stopSeekQuietly();
            Async.post(new Runnable() {
                public void run() {
                    if (!isFinishing() && id.length() > 0) {
                        Ui.toastShort(GamesActivity.this, "对局开始了");
                        openGame(id, color);
                    }
                }
            });
        } else if ("challenge".equals(type)) {
            final Json ch = j.obj("challenge");
            Async.post(new Runnable() {
                public void run() {
                    if (!isFinishing()) {
                        addHeader("收到挑战");
                        addChallengeRow(ch);
                        statusText.setText("有人向你发起挑战");
                    }
                }
            });
        } else if ("gameFinish".equals(type)) {
            final String id = j.obj("game").str("id", "");
            Async.post(new Runnable() {
                public void run() {
                    if (!isFinishing()) {
                        statusText.setText("有对局结束了");
                        Log.i("Lobby", "对局结束 id=" + id);
                    }
                }
            });
        }
    }

    void openGame(String gameId, String color) {
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_GAME_ID, gameId);
        if (color != null && color.length() > 0) {
            i.putExtra(GameActivity.EXTRA_MY_COLOR, color);
        }
        startActivity(i);
    }

    private void respondChallenge(final String id, final boolean accept) {
        Async.run("challenge", new Async.Job<Json>() {
            public Json run() throws Throwable {
                if (accept) {
                    return api.acceptChallenge(id);
                }
                return api.declineChallenge(id, "later");
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    Ui.toast(GamesActivity.this, "操作失败：" + error.getMessage());
                } else {
                    Ui.toastShort(GamesActivity.this, accept ? "已接受" : "已拒绝");
                    if (accept) {
                        loadOngoing();
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------- 开新局

    private void pickAiLevel() {
        final String[] levels = new String[8];
        for (int i = 0; i < 8; i++) {
            levels[i] = "电脑等级 " + (i + 1);
        }
        final int current = prefs.getAiLevel();
        Ui.choose(this, "选择电脑等级（对电脑不计分）", levels, current - 1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final int level = which + 1;
                        prefs.setAiLevel(level);
                        d.dismiss();
                        ChallengeFlow.challengeAi(GamesActivity.this, level);
                    }
                });
    }

    // -------------------------------------------------------- 找真人对手

    private void pickSeek() {
        if (currentSeek != null) {
            stopSeek();
            return;
        }
        Ui.choose(this, "找对手：排位还是休闲？",
                new String[] { "排位赛（计分，会加减等级分）", "休闲赛（不计分）" },
                prefs.isRatedDefault() ? 0 : 1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        final boolean rated = which == 0;
                        prefs.setRatedDefault(rated);
                        pickSeekTime(rated);
                    }
                });
    }

    private void pickSeekTime(final boolean rated) {
        int def = SeekOptions.DEFAULT_INDEX;
        int bad = SeekOptions.firstBadIndex();
        if (bad >= 0) {
            // 表里有意料之外的档位，日志里留一条，免得又变成"随便选哪个都报错"
            Log.e("Lobby", "seek 时间档第 " + bad + " 项不合法（"
                    + SeekOptions.MINUTES[bad] + "分钟 +" + SeekOptions.INCREMENTS[bad]
                    + "秒，估算 " + SeekOptions.estimateTotalSeconds(bad) + " 秒，"
                    + "Board API 要求 >= 480 秒）");
        }
        Ui.choose(this, (rated ? "排位赛" : "休闲赛") + " · 选时间", SEEK_LABELS, def,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        startSeek(SeekOptions.MINUTES[which],
                                SeekOptions.INCREMENTS[which], rated);
                    }
                });
    }

    private void startSeek(final double minutes, final int inc, final boolean rated) {
        if (currentSeek != null) {
            return;
        }
        final LichessApi.Flag flag = new LichessApi.Flag();
        currentSeek = flag;
        updateSeekButton();

        final String human = trimNum(minutes) + "+" + inc;
        statusText.setText("正在寻找对手（" + (rated ? "排位" : "休闲") + " " + human
                + "）…\n保持本页面，配到会自动开局");

        Thread t = new Thread(new Runnable() {
            public void run() {
                NetException failure = null;
                try {
                    api.seek(minutes, inc, rated, new LichessApi.JsonSink() {
                        public void onJson(Json line) {
                            Log.i("Lobby", "seek 事件: " + line.toString());
                        }
                    }, flag);
                } catch (NetException e) {
                    failure = e;
                } catch (Throwable e) {
                    Log.e("Lobby", "seek 异常", e);
                    failure = new NetException(NetException.STAGE_HTTP, String.valueOf(e));
                }
                finishSeek(flag, failure);
            }
        }, "lichessold-seek");
        t.setDaemon(true);
        t.start();
    }

    private void stopSeek() {
        final LichessApi.Flag flag = currentSeek;
        if (flag == null) {
            return;
        }
        flag.cancel();
        statusText.setText("正在停止寻找…");
        // 关连接服务端也会撤销，但那是被动的（要等下一个空行心跳）。
        // 显式 DELETE 立即生效。
        Async.fire("cancelSeek", new Async.Job<Void>() {
            public Void run() throws Throwable {
                api.cancelSeek();
                return null;
            }
        });
    }

    /** 配到对手或页面关闭时静默收尾。 */
    private void stopSeekQuietly() {
        LichessApi.Flag f = currentSeek;
        if (f != null) {
            f.cancel();
            currentSeek = null;
            Async.post(new Runnable() {
                public void run() {
                    updateSeekButton();
                }
            });
        }
    }

    private void finishSeek(final LichessApi.Flag flag, final NetException error) {
        Async.post(new Runnable() {
            public void run() {
                if (currentSeek != flag) {
                    return;
                }
                currentSeek = null;
                updateSeekButton();
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    statusText.setText("寻找失败");
                    Ui.toast(GamesActivity.this, "寻找对手失败：\n" + error.getMessage());
                    Log.w("Lobby", "seek 失败: " + error.getMessage());
                } else if (flag.cancelled()) {
                    statusText.setText("已停止寻找");
                } else {
                    statusText.setText("寻找已结束（多半是配到对手了）");
                }
            }
        });
    }

    private void updateSeekButton() {
        if (seekButton == null) {
            return;
        }
        boolean seeking = currentSeek != null;
        seekButton.setText(seeking ? "停止寻找" : "找真人对手");
    }

    private static String trimNum(double v) {
        return LichessApi.formatMinutes(v);
    }
}
