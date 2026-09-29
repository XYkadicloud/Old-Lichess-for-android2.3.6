package org.lichessold.desktop;

import java.awt.Color;
import java.util.Timer;
import java.util.TimerTask;

import org.lichessold.chess.Move;
import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.LichessGame;
import org.lichessold.net.NetException;
import org.lichessold.util.Log;

/**
 * 在线对局（Lichess Board API）。对应 Android 版的
 * {@code org.lichessold.ui.GameActivity}。
 *
 * 数据流：
 *   /api/board/game/stream/{id}  →  gameFull（首行）→ gameState（每次变化）
 *   本地走子  → POST /api/board/game/{id}/move/{uci}  → 服务端随后推 gameState
 *
 * 断线重连：流断了就指数退避重连，重连后重新收到 gameFull，
 * 用 gameFull.state.moves 整体重放来同步局面 —— 最简单也最不容易出错。
 */
public final class OnlineGamePanel extends GamePanel {

    private static final long serialVersionUID = 1L;
    private static final long CLOCK_TICK_MS = 500L;

    private LichessApi api;
    /** 对局流用的取消标志。重新同步时要能换一个，所以不是 final。 */
    private volatile LichessApi.Flag streamFlag = new LichessApi.Flag();
    private final LichessGame game = new LichessGame();

    private String gameId = "";
    /** 打开界面时带过来的颜色（事件流的 gameStart 会给，最可靠）。 */
    private String intentColor = "";
    private boolean usernameFetching;
    private boolean pendingMove;
    private boolean streamAlive;
    private int retryCount;
    private String transientMessage = "";

    private Timer clockTimer;
    private long lastClockSync;
    /** 服务端给的权威时间，绝不被本地倒计时改写。 */
    private int baseWtime;
    private int baseBtime;
    /** 界面上显示的时间 = 权威时间 - 本地已过时间。 */
    private int dispWtime;
    private int dispBtime;
    private volatile boolean destroyed;

    public OnlineGamePanel(String gameId, String myColor) {
        super();
        this.gameId = gameId == null ? "" : gameId;
        if (myColor != null && myColor.length() > 0) {
            this.intentColor = myColor;
            game.myColor = myColor;
        } else {
            // 颜色未知。默认成白方是错的：执黑时棋盘方向反，
            // 而且 isMyTurn() 永远为假，根本走不了子。
            // 这里留空，等 gameFull 到了再用用户名/AI 归属去推断。
            game.myColor = "";
        }
        Log.i("Game", "进入在线对局界面 gameId=" + gameId);

        try {
            api = DesktopNet.api();
        } catch (NetException e) {
            DesktopApp.toast(this, e.getMessage());
            return;
        }
        // 子类构造函数最后一步必须调 start()：字段初始化完才能摆局面刷界面
        start();
        startStream();
        startClockTimer();
    }

    @Override
    protected String headerTitle() {
        return "在线对局";
    }

    /** 在线对局：局面状态一律以服务端为准，本地不擅自宣布结束。 */
    @Override
    protected boolean serverAuthoritative() {
        return true;
    }

    @Override
    protected void onBoardReady() {
        // 颜色未知时先按白方显示，收到 gameFull 后会自动纠正
    }

    /** 页面被换掉时调用：停掉流和定时器。 */
    public void dispose() {
        destroyed = true;
        LichessApi.Flag f = streamFlag;
        if (f != null) {
            f.cancel();
        }
        if (clockTimer != null) {
            clockTimer.cancel();
            clockTimer = null;
        }
    }

    @Override
    protected void goBack() {
        dispose();
        super.goBack();
    }

    // -------------------------------------------------------------- 界面

    @Override
    protected void buildActions() {
        action("和棋", new Runnable() {
            public void run() {
                onDrawButton();
            }
        });

        action("认输", new Runnable() {
            public void run() {
                if (DesktopApp.confirm(OnlineGamePanel.this, "认输", "确定认输吗？")) {
                    sendResign();
                }
            }
        });

        action("聊天", new Runnable() {
            public void run() {
                openChat();
            }
        });

        action("重新同步", new Runnable() {
            public void run() {
                resync();
            }
        });

        action("对手主页", new Runnable() {
            public void run() {
                openOpponentProfile();
            }
        });
    }

    @Override
    protected boolean canMoveNow() {
        return !gameOver && !pendingMove && game.fullReceived
                && game.myColor.length() > 0 && game.isMyTurn();
    }

    @Override
    protected void onCantMoveNow() {
        if (game.myColor.length() == 0) {
            DesktopApp.toast(this, "还在确认你的账号，稍等…");
        } else if (!game.fullReceived) {
            DesktopApp.toast(this, "正在同步对局…");
        } else if (pendingMove) {
            DesktopApp.toast(this, "上一步还在发送中");
        } else if (game.isFinished()) {
            DesktopApp.toast(this, "对局已结束");
        } else {
            DesktopApp.toast(this, "还没轮到你走");
        }
    }

    @Override
    protected void onPlayerBarTapped(boolean top) {
        if (top) {
            openOpponentProfile();
        }
    }

    private void openOpponentProfile() {
        if (!game.fullReceived) {
            DesktopApp.toast(this, "还没同步到对手信息");
            return;
        }
        boolean topIsWhite = !"white".equals(game.myColor);
        String id = topIsWhite ? game.whiteId : game.blackId;
        boolean isAi = topIsWhite ? game.whiteIsAi : game.blackIsAi;
        if (isAi) {
            DesktopApp.toast(this, "这是电脑对手，没有主页");
            return;
        }
        if (id == null || id.length() == 0) {
            DesktopApp.toast(this, "拿不到对方账号名");
            return;
        }
        DesktopApp.openProfile(id);
    }

    /**
     * 重新拉一次对局流。
     *
     * 对局流的第一行永远是 gameFull，而且带完整走子列表，
     * 所以"重连"就等于"按服务端把局面整体重放一遍"。
     */
    private void resync() {
        LichessApi.Flag old = streamFlag;
        if (old != null) {
            old.cancel();
        }
        pendingMove = false;
        setTransient("正在重新同步…");
        startStream();
    }

    @Override
    protected void afterMoveApplied(int move) {
        sendMove(Move.toUci(move));
    }

    @Override
    protected String topText() {
        if (!game.fullReceived) {
            return "正在同步…";
        }
        boolean topIsWhite = !"white".equals(game.myColor);
        String name = topIsWhite ? game.whiteName : game.blackName;
        int rating = topIsWhite ? game.whiteRating : game.blackRating;
        return (topIsWhite ? "白 " : "黑 ") + safe(name)
                + (rating > 0 ? " (" + rating + ")" : "");
    }

    @Override
    protected String bottomText() {
        if (!game.fullReceived) {
            return "";
        }
        boolean bottomIsWhite = "white".equals(game.myColor);
        String name = bottomIsWhite ? game.whiteName : game.blackName;
        int rating = bottomIsWhite ? game.whiteRating : game.blackRating;
        return (bottomIsWhite ? "白 " : "黑 ") + safe(name)
                + (rating > 0 ? " (" + rating + ")" : "") + " · 你";
    }

    @Override
    protected String topClockText() {
        if (!game.fullReceived || game.clockInitial <= 0) {
            return "";
        }
        return formatClock("white".equals(game.myColor) ? dispBtime : dispWtime);
    }

    @Override
    protected String bottomClockText() {
        if (!game.fullReceived || game.clockInitial <= 0) {
            return "";
        }
        return formatClock("white".equals(game.myColor) ? dispWtime : dispBtime);
    }

    @Override
    protected boolean topActive() {
        if (!game.fullReceived) {
            return false;
        }
        return !game.sideToMove().equals(game.myColor);
    }

    @Override
    protected boolean bottomActive() {
        if (!game.fullReceived) {
            return false;
        }
        return game.sideToMove().equals(game.myColor);
    }

    @Override
    protected String baseStatusText() {
        if (transientMessage.length() > 0) {
            return transientMessage;
        }
        if (gameOver) {
            return resultMessage.length() > 0 ? resultMessage
                    : LichessGame.statusText(game.status);
        }
        if (!game.fullReceived) {
            return streamAlive ? "正在同步对局…" : "连接中…";
        }
        if (game.myColor.length() == 0) {
            return "正在确认你的账号…（拿不到用户名就分不清执哪边）";
        }
        if (game.isFinished()) {
            return game.resultText();
        }
        if (game.opponentOfferingDraw()) {
            return "对方提议和棋，点「和棋」接受";
        }
        if (game.iAmOfferingDraw()) {
            return "已向对方提议和棋…";
        }
        if (game.opponentGone) {
            return "对方掉线了（" + game.opponentGoneSeconds + " 秒）";
        }
        if (pendingMove) {
            return "走子发送中…";
        }
        return game.isMyTurn() ? "轮到你走" : "等待对方走子…";
    }

    private static String safe(String s) {
        return s == null || s.length() == 0 ? "?" : s;
    }

    // -------------------------------------------------------------- 网络

    private void startStream() {
        final LichessApi.Flag flag = new LichessApi.Flag();
        streamFlag = flag;
        DesktopAsync.daemon("game", new Runnable() {
            public void run() {
                streamLoop(flag);
            }
        });
    }

    private void streamLoop(final LichessApi.Flag flag) {
        int backoff = 1000;
        while (!flag.cancelled() && !destroyed) {
            try {
                streamAlive = true;
                api.streamGame(gameId, new LichessApi.JsonSink() {
                    public void onJson(Json line) {
                        handleLine(line);
                    }
                }, flag);
                if (!flag.cancelled() && !destroyed) {
                    postTransient("对局流已结束");
                }
                break;
            } catch (final NetException e) {
                streamAlive = false;
                if (flag.cancelled() || destroyed) {
                    break;
                }
                retryCount++;
                final int delay = backoff;
                Log.w("Game", "对局流断开（第 " + retryCount + " 次）: " + e.getMessage()
                        + "，" + delay + "ms 后重连");
                postTransient("连接断开，正在重连…");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    break;
                }
                backoff = Math.min(backoff * 2, 30000);
            } catch (Throwable t) {
                Log.e("Game", "对局流异常", t);
                break;
            }
        }
    }

    private void handleLine(Json j) {
        final String type = j.str("type", "");
        if ("gameFull".equals(type)) {
            final LichessGame g = new LichessGame();
            g.applyFull(j);
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (!destroyed) {
                        applyFull(g);
                    }
                }
            });
        } else if ("gameState".equals(type)) {
            final Json state = j;
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (!destroyed) {
                        applyState(state);
                    }
                }
            });
        } else if ("chatLine".equals(type)) {
            final String who = j.str("username", "?");
            final String text = j.str("text", "");
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (destroyed) {
                        return;
                    }
                    Log.i("Game", "聊天 " + who + ": " + text);
                    setTransient(who + ": " + text);
                }
            });
        } else if ("opponentGone".equals(type)) {
            final boolean gone = j.b("gone", false);
            final long wait = j.l("claimWinInSeconds", 0);
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (destroyed) {
                        return;
                    }
                    game.opponentGone = gone;
                    game.opponentGoneSeconds = wait;
                    updateStatus();
                }
            });
        }
    }

    private void applyFull(LichessGame g) {
        game.id = g.id;
        game.initialFen = g.initialFen;
        game.rated = g.rated;
        game.speed = g.speed;
        game.clockInitial = g.clockInitial;
        game.clockIncrement = g.clockIncrement;
        game.whiteName = g.whiteName;
        game.blackName = g.blackName;
        game.whiteId = g.whiteId;
        game.blackId = g.blackId;
        game.whiteRating = g.whiteRating;
        game.blackRating = g.blackRating;
        game.whiteIsAi = g.whiteIsAi;
        game.blackIsAi = g.blackIsAi;
        game.aiLevel = g.aiLevel;
        game.moves = g.moves;
        game.wtime = g.wtime;
        game.btime = g.btime;
        game.winc = g.winc;
        game.binc = g.binc;
        game.status = g.status;
        game.winner = g.winner;
        game.fullReceived = true;

        game.myColor = resolveMyColor();
        if (game.myColor.length() == 0) {
            Log.w("Game", "判断不出我方颜色。white=" + game.whiteName + "/" + game.whiteId
                    + " black=" + game.blackName + "/" + game.blackId
                    + " 本地用户名=" + DesktopPrefs.get().getUsername());
            fetchUsernameThenResolve();
        }

        initialFen = game.initialFen;
        flipped = !"white".equals(game.myColor);
        boardView.setFlipped(flipped);

        syncClocks();
        setMovesFromUci(game.moves);
        setTransient("");
        Log.i("Game", "gameFull: 我执 " + (game.myColor.length() == 0 ? "?" : game.myColor)
                + "，对手 " + game.opponentName()
                + "，计分=" + game.rated + "，速度=" + game.speed
                + "，状态 " + game.status + "，已走 " + game.moveCount() + " 步"
                + "，翻转=" + flipped);
    }

    /**
     * 推断"我执哪一边"。顺序：打开时带的颜色 → 用户名比对 → AI 归属反推。
     *
     * gameFull 的 JSON 里没有 myColor 字段（颜色只在事件流 gameStart
     * 和 /api/account/playing 里），所以必须自己推。
     */
    private String resolveMyColor() {
        if ("white".equals(intentColor) || "black".equals(intentColor)) {
            return intentColor;
        }
        // detectMyColor 会先看 myColor 字段，先清掉免得拿到脏值
        game.myColor = "";
        String byName = game.detectMyColor(DesktopPrefs.get().getUsername());
        if (byName.length() > 0) {
            return byName;
        }
        // 和电脑下：我们一定不是 AI 那一边
        if (game.whiteIsAi && !game.blackIsAi) {
            return "black";
        }
        if (game.blackIsAi && !game.whiteIsAi) {
            return "white";
        }
        return "";
    }

    /**
     * 本地没存用户名时，后台补一次 /api/account，拿到后自动纠正颜色。
     *
     * 不这么做的话：用户名缺失 → 颜色判不出来 → isMyTurn() 永远为假 →
     * 界面就是"对战时无法下子"。
     */
    private void fetchUsernameThenResolve() {
        final DesktopPrefs p = DesktopPrefs.get();
        if (usernameFetching || !p.hasToken() || p.getUsername().length() > 0) {
            return;
        }
        usernameFetching = true;
        DesktopAsync.run("whoami", new DesktopAsync.Job<String>() {
            public String run() throws Throwable {
                return api.accountUsername();
            }
        }, new DesktopAsync.Done<String>() {
            public void done(String name, Throwable error) {
                usernameFetching = false;
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    Log.w("Game", "补拉用户名失败: " + error.getMessage());
                    return;
                }
                if (name == null || name.length() == 0) {
                    return;
                }
                p.setUsername(name);
                Log.i("Game", "补到用户名 " + name + "，重新判定颜色");
                String c = resolveMyColor();
                if (c.length() == 0) {
                    return;
                }
                game.myColor = c;
                flipped = !"white".equals(c);
                boardView.setFlipped(flipped);
                updateStatus();
            }
        });
    }

    private void applyState(Json state) {
        game.applyState(state);
        syncClocks();
        setMovesFromUci(game.moves);
        if (game.isFinished()) {
            gameOver = true;
            resultMessage = game.resultText();
            DesktopApp.toast(this, resultMessage);
        }
        updateStatus();
    }

    private void syncClocks() {
        baseWtime = game.wtime;
        baseBtime = game.btime;
        dispWtime = baseWtime;
        dispBtime = baseBtime;
        lastClockSync = System.currentTimeMillis();
    }

    private void startClockTimer() {
        clockTimer = new Timer("lichessold-clock", true);
        clockTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                DesktopAsync.post(new Runnable() {
                    public void run() {
                        if (!destroyed) {
                            tickClocks();
                        }
                    }
                });
            }
        }, CLOCK_TICK_MS, CLOCK_TICK_MS);
    }

    /**
     * 每 500ms 更新时钟显示。
     *
     * 注意：**只能拿 baseWtime/baseBtime 去减**，不能把结果写回 base。
     * 写回的话下一次 tick 会在已经扣过的值上再扣一遍，时钟会以两倍速度掉。
     */
    private void tickClocks() {
        if (!game.fullReceived || game.clockInitial <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - lastClockSync;
        if (game.isFinished()) {
            dispWtime = baseWtime;
            dispBtime = baseBtime;
        } else if ("white".equals(game.sideToMove())) {
            dispWtime = (int) Math.max(0, baseWtime - elapsed);
            dispBtime = baseBtime;
        } else {
            dispWtime = baseWtime;
            dispBtime = (int) Math.max(0, baseBtime - elapsed);
        }
        refreshClocks();
    }

    private void setTransient(final String msg) {
        transientMessage = msg == null ? "" : msg;
        refreshStatusText();
    }

    private void postTransient(final String msg) {
        DesktopAsync.post(new Runnable() {
            public void run() {
                if (!destroyed) {
                    setTransient(msg);
                }
            }
        });
    }

    // -------------------------------------------------------------- 动作

    private void sendMove(final String uci) {
        pendingMove = true;
        DesktopAsync.run("move", new DesktopAsync.Job<Void>() {
            public Void run() throws Throwable {
                api.move(gameId, uci);
                return null;
            }
        }, new DesktopAsync.Done<Void>() {
            public void done(Void v, Throwable error) {
                pendingMove = false;
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    Log.e("Game", "走子失败", error);
                    DesktopApp.toast(OnlineGamePanel.this, "走子失败：" + error.getMessage());
                    setTransient("走子失败，正在重新同步…");
                } else {
                    Log.i("Game", "走子已发送 " + uci);
                }
                updateStatus();
            }
        });
    }

    private void sendResign() {
        DesktopAsync.run("resign", new DesktopAsync.Job<Void>() {
            public Void run() throws Throwable {
                api.resign(gameId);
                return null;
            }
        }, new DesktopAsync.Done<Void>() {
            public void done(Void v, Throwable error) {
                if (error != null) {
                    DesktopApp.toast(OnlineGamePanel.this, "认输失败：" + error.getMessage());
                }
            }
        });
    }

    private void onDrawButton() {
        if (game.opponentOfferingDraw()) {
            sendDraw(true);
        } else if (game.iAmOfferingDraw()) {
            DesktopApp.toast(this, "你已经提议过了");
        } else if (DesktopApp.confirm(this, "提议和棋", "向对方提议和棋？")) {
            sendDraw(true);
        }
    }

    private void sendDraw(final boolean accept) {
        DesktopAsync.run("draw", new DesktopAsync.Job<Void>() {
            public Void run() throws Throwable {
                api.draw(gameId, accept);
                return null;
            }
        }, new DesktopAsync.Done<Void>() {
            public void done(Void v, Throwable error) {
                if (error != null) {
                    DesktopApp.toast(OnlineGamePanel.this, "和棋操作失败：" + error.getMessage());
                } else {
                    DesktopApp.toast(OnlineGamePanel.this, accept ? "已接受/提议和棋" : "已拒绝");
                }
            }
        });
    }

    private void openChat() {
        final String text = DesktopApp.prompt(this, "发送聊天", "输入消息", "");
        if (text == null || text.trim().length() == 0) {
            return;
        }
        final String msg = text.trim();
        DesktopAsync.fire("chat", new DesktopAsync.Job<Void>() {
            public Void run() throws Throwable {
                api.chat(gameId, msg, "player");
                return null;
            }
        });
    }

    @Override
    protected void onGameFinished(int status) {
        Log.i("Game", "本地判定对局结束: " + resultMessage);
        setStatusText(baseStatusText(), new Color(0xA03030));
    }
}
