package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.LichessGame;
import org.lichessold.net.NetException;
import org.lichessold.net.SeekOptions;
import org.lichessold.util.Log;

/**
 * 对局大厅：进行中的对局 + 收到的挑战 + 开新局。
 * 对应 Android 版的 {@code org.lichessold.ui.GamesActivity}。
 *
 * 同时挂着 /api/stream/event 事件流，收到 gameStart 自动进入对局界面，
 * 收到 challenge 就在列表里显示接受/拒绝按钮。
 *
 * 关于「找真人对手」的时间档（Android 版 0.5.0 在这里踩了坑）：
 *   1) /api/board/seek 的 time 单位是**分钟**，不是秒。
 *   2) 用个人令牌时 Board API 只允许 **Rapid 及更慢**的棋：
 *      Speed 按 limit + 40 * increment 分档，Rapid 下限是 480 秒。
 *      下面的表由 SeekOptions 提供，只列 480 秒以上的档位。
 */
public final class LobbyPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final LichessApi api;
    private final LichessApi.Flag cancelFlag = new LichessApi.Flag();

    private final JPanel list = new JPanel();
    private final JLabel statusText;
    private final JButton seekButton;

    private boolean loading;
    private boolean started;
    private volatile LichessApi.Flag currentSeek;
    private volatile boolean destroyed;

    public LobbyPanel() {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);

        LichessApi a;
        try {
            a = DesktopNet.api();
        } catch (NetException e) {
            a = null;
            DesktopApp.toast(this, e.getMessage());
        }
        api = a;

        // ---- 顶部操作区
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(DesktopTheme.BG);
        statusText = DesktopTheme.label("准备中…", 13, DesktopTheme.GREEN_LIGHT);
        statusText.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        statusText.setAlignmentX(LEFT_ALIGNMENT);
        top.add(statusText);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row1.setBackground(DesktopTheme.BG);
        JButton challengeAi = DesktopTheme.smallPrimary("挑战电脑");
        challengeAi.setPreferredSize(new Dimension(110, 30));
        challengeAi.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                pickAiLevel();
            }
        });
        JButton challengeUser = DesktopTheme.smallButton("挑战玩家");
        challengeUser.setPreferredSize(new Dimension(110, 30));
        challengeUser.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                ChallengeFlow.challengeUser(LobbyPanel.this, null);
            }
        });
        seekButton = DesktopTheme.smallButton("找真人对手");
        seekButton.setPreferredSize(new Dimension(120, 30));
        seekButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                pickSeek();
            }
        });
        JButton refresh = DesktopTheme.smallButton("刷新");
        refresh.setPreferredSize(new Dimension(80, 30));
        refresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                loadOngoing();
            }
        });
        JButton profile = DesktopTheme.smallButton("我的主页");
        profile.setPreferredSize(new Dimension(96, 30));
        profile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.openProfile("");
            }
        });
        JButton back = DesktopTheme.smallButton("返回");
        back.setPreferredSize(new Dimension(80, 30));
        back.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.back();
            }
        });

        row1.add(challengeAi);
        row1.add(challengeUser);
        row1.add(seekButton);
        row1.add(refresh);
        row1.add(profile);
        row1.add(back);
        row1.setAlignmentX(LEFT_ALIGNMENT);
        top.add(row1);
        top.add(Box.createVerticalStrut(4));

        // ---- 列表（比页面底色再深一点，看起来像独立的滚动区）
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(LIST_BG);
        JScrollPane scroll = DesktopTheme.scroll(list);
        scroll.getViewport().setBackground(LIST_BG);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, DesktopTheme.BORDER));

        // 标题栏 + 操作区钉在顶部，列表吃掉剩余空间
        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(DesktopTheme.BG);
        north.add(DesktopTheme.header("在线对局"), BorderLayout.NORTH);
        north.add(top, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        if (api == null) {
            statusText.setText("网络层初始化失败");
            return;
        }
        if (!DesktopPrefs.get().hasToken()) {
            statusText.setText("还没有设置令牌，请先到「设置」里填");
            addNote("点「返回」→「设置」，填入 Lichess API 令牌。");
            return;
        }
        startWorking();
    }

    /** 列表底色：比页面底色再深一点，看起来像独立的滚动区。 */
    private static final java.awt.Color LIST_BG = new java.awt.Color(0x0A0A0A);

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

    public void dispose() {
        destroyed = true;
        // 关掉页面就把寻找停掉 —— 服务端会在连接断开时自动撤销 seek
        LichessApi.Flag f = currentSeek;
        if (f != null) {
            f.cancel();
        }
        cancelFlag.cancel();
    }

    // -------------------------------------------------------------- 列表

    private void clearList() {
        list.removeAll();
        list.revalidate();
        list.repaint();
    }

    private void addNote(String text) {
        JLabel t = DesktopTheme.label(text, 12, DesktopTheme.TEXT_DIM);
        t.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        t.setAlignmentX(LEFT_ALIGNMENT);
        list.add(t);
        refreshList();
    }

    private void addHeader(String text) {
        JLabel t = DesktopTheme.label(text, 12, DesktopTheme.GREEN_LIGHT);
        t.setBorder(BorderFactory.createEmptyBorder(8, 10, 3, 10));
        t.setAlignmentX(LEFT_ALIGNMENT);
        list.add(t);
        refreshList();
    }

    private void refreshList() {
        list.revalidate();
        list.repaint();
    }

    private void addGameRow(final String gameId, String text) {
        JButton b = DesktopTheme.rowButton(text);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        b.setPreferredSize(new Dimension(400, 32));
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.openOnlineGame(gameId, "");
            }
        });
        list.add(b);
        refreshList();
    }

    private void addChallengeRow(final Json ch) {
        final String id = ch.str("id", "");
        final String name = ch.obj("challenger").str("name", "?");
        final int rating = ch.obj("challenger").i("rating", 0);
        final String variant = ch.obj("variant").str("key", "standard");
        final boolean rated = ch.b("rated", false);
        String time = describeTimeControl(ch);
        String label = name + (rating > 0 ? " (" + rating + ")" : "")
                + "   " + time + (rated ? " · 排位赛（计分）" : " · 休闲赛")
                + (("standard".equals(variant)) ? "" : " · " + variant);

        JPanel rowBox = new JPanel(new BorderLayout(6, 0));
        rowBox.setBackground(DesktopTheme.PANEL);
        rowBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(3, 10, 3, 10),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        rowBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        rowBox.setAlignmentX(LEFT_ALIGNMENT);

        rowBox.add(DesktopTheme.label(label, 13, DesktopTheme.TEXT), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);
        JButton accept = DesktopTheme.smallButton("接受");
        accept.setPreferredSize(new Dimension(64, 26));
        accept.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                respondChallenge(id, true);
            }
        });
        JButton decline = DesktopTheme.smallButton("拒绝");
        decline.setPreferredSize(new Dimension(64, 26));
        decline.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                respondChallenge(id, false);
            }
        });
        btns.add(accept);
        btns.add(decline);
        rowBox.add(btns, BorderLayout.EAST);

        list.add(rowBox);
        refreshList();
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
        if (loading || api == null) {
            return;
        }
        loading = true;
        statusText.setText("正在获取进行中的对局…");
        DesktopAsync.run("playing", new DesktopAsync.Job<Json>() {
            public Json run() throws Throwable {
                return api.playing();
            }
        }, new DesktopAsync.Done<Json>() {
            public void done(Json j, Throwable error) {
                loading = false;
                if (destroyed) {
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
                        addGameRow(id, "vs " + opp + "   执"
                                + ("white".equals(color) ? "白" : "黑")
                                + "   " + LichessGame.statusText(status));
                    }
                }
                if (list.getComponentCount() == 0) {
                    addNote("点上面的「挑战电脑」开一局试试。");
                }
            }
        });
    }

    private void startEventStream() {
        DesktopAsync.daemon("events", new Runnable() {
            public void run() {
                int backoff = 2000;
                while (!cancelFlag.cancelled() && !destroyed) {
                    try {
                        api.streamEvents(new LichessApi.JsonSink() {
                            public void onJson(Json line) {
                                handleEvent(line);
                            }
                        }, cancelFlag);
                        break;
                    } catch (final NetException e) {
                        if (cancelFlag.cancelled() || destroyed) {
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
        });
    }

    private void handleEvent(Json j) {
        final String type = j.str("type", "");
        Log.i("Lobby", "事件: " + type);
        if ("gameStart".equals(type)) {
            final String id = j.obj("game").str("id", "");
            final String color = j.obj("game").str("color", "");
            // 配到对手了，寻找自然结束
            stopSeekQuietly();
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (!destroyed && id.length() > 0) {
                        DesktopApp.toast(LobbyPanel.this, "对局开始了");
                        DesktopApp.openOnlineGame(id, color);
                    }
                }
            });
        } else if ("challenge".equals(type)) {
            final Json ch = j.obj("challenge");
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (!destroyed) {
                        addHeader("收到挑战");
                        addChallengeRow(ch);
                        statusText.setText("有人向你发起挑战");
                    }
                }
            });
        } else if ("gameFinish".equals(type)) {
            final String id = j.obj("game").str("id", "");
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (!destroyed) {
                        statusText.setText("有对局结束了");
                        Log.i("Lobby", "对局结束 id=" + id);
                    }
                }
            });
        }
    }

    private void respondChallenge(final String id, final boolean accept) {
        DesktopAsync.run("challenge", new DesktopAsync.Job<Json>() {
            public Json run() throws Throwable {
                if (accept) {
                    return api.acceptChallenge(id);
                }
                return api.declineChallenge(id, "later");
            }
        }, new DesktopAsync.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    DesktopApp.toast(LobbyPanel.this, "操作失败：" + error.getMessage());
                } else {
                    DesktopApp.toast(LobbyPanel.this, accept ? "已接受" : "已拒绝");
                    if (accept) {
                        loadOngoing();
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------- 开新局

    private void pickAiLevel() {
        String[] levels = new String[8];
        for (int i = 0; i < 8; i++) {
            levels[i] = "电脑等级 " + (i + 1);
        }
        final int current = DesktopPrefs.get().getAiLevel();
        Object pick = DesktopApp.choose(this, "挑战电脑",
                "选择电脑等级（对电脑不计分）", levels, levels[current - 1]);
        if (pick == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            if (levels[i].equals(pick)) {
                final int level = i + 1;
                DesktopPrefs.get().setAiLevel(level);
                ChallengeFlow.challengeAi(this, level);
                return;
            }
        }
    }

    // -------------------------------------------------------- 找真人对手

    private void pickSeek() {
        if (currentSeek != null) {
            stopSeek();
            return;
        }
        String[] modes = { "排位赛（计分，会加减等级分）", "休闲赛（不计分）" };
        Object pick = DesktopApp.choose(this, "找对手",
                "排位还是休闲？", modes,
                modes[DesktopPrefs.get().isRatedDefault() ? 0 : 1]);
        if (pick == null) {
            return;
        }
        final boolean rated = modes[0].equals(pick);
        DesktopPrefs.get().setRatedDefault(rated);
        pickSeekTime(rated);
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
        Object pick = DesktopApp.choose(this, (rated ? "排位赛" : "休闲赛") + " · 选时间",
                "选择时间控制", SeekOptions.LABELS, SeekOptions.LABELS[def]);
        if (pick == null) {
            return;
        }
        for (int i = 0; i < SeekOptions.LABELS.length; i++) {
            if (SeekOptions.LABELS[i].equals(pick)) {
                startSeek(SeekOptions.MINUTES[i], SeekOptions.INCREMENTS[i], rated);
                return;
            }
        }
    }

    private void startSeek(final double minutes, final int inc, final boolean rated) {
        if (currentSeek != null) {
            return;
        }
        final LichessApi.Flag flag = new LichessApi.Flag();
        currentSeek = flag;
        updateSeekButton();

        final String human = LichessApi.formatMinutes(minutes) + "+" + inc;
        statusText.setText("正在寻找对手（" + (rated ? "排位" : "休闲") + " " + human
                + "）… 配到会自动开局");

        DesktopAsync.daemon("seek", new Runnable() {
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
        });
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
        DesktopAsync.fire("cancelSeek", new DesktopAsync.Job<Void>() {
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
            DesktopAsync.post(new Runnable() {
                public void run() {
                    updateSeekButton();
                }
            });
        }
    }

    private void finishSeek(final LichessApi.Flag flag, final NetException error) {
        DesktopAsync.post(new Runnable() {
            public void run() {
                if (currentSeek != flag) {
                    return;
                }
                currentSeek = null;
                updateSeekButton();
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    statusText.setText("寻找失败");
                    DesktopApp.toast(LobbyPanel.this, "寻找对手失败：\n" + error.getMessage());
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
}
