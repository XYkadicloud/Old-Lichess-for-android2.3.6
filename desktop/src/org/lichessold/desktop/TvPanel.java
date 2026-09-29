package org.lichessold.desktop;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.util.Log;

/**
 * 观战：Lichess 首页焦点对局（/api/tv/feed）。对应 Android 版的
 * {@code org.lichessold.ui.TvActivity}。
 *
 * 这个接口是公开的，不需要令牌，所以没登录也能用。
 * 只读：不能走子，只跟着服务端推的 fen 更新棋盘。
 */
public final class TvPanel extends GamePanel {

    private static final long serialVersionUID = 1L;

    private LichessApi api;
    private final LichessApi.Flag cancelFlag = new LichessApi.Flag();

    private String white = "?";
    private String black = "?";
    private String gameId = "";
    private boolean connected;
    private int updates;
    private volatile boolean destroyed;

    public TvPanel() {
        super();
        try {
            api = DesktopNet.api();
        } catch (NetException e) {
            DesktopApp.toast(this, e.getMessage());
            return;
        }
        boardView.setInteractive(false);
        // 子类构造函数最后一步必须调 start()：字段初始化完才能摆局面刷界面
        start();
        startFeed();
    }

    @Override
    protected String headerTitle() {
        return "观战";
    }

    public void dispose() {
        destroyed = true;
        cancelFlag.cancel();
    }

    @Override
    protected void goBack() {
        dispose();
        super.goBack();
    }

    @Override
    protected void buildActions() {
        action("翻转", new Runnable() {
            public void run() {
                flipped = !flipped;
                boardView.setFlipped(flipped);
                updateStatus();
            }
        });

        action("重连", new Runnable() {
            public void run() {
                cancelFlag.cancel();
                updates = 0;
                startFeed();
            }
        });
    }

    @Override
    protected boolean canMoveNow() {
        return false;
    }

    @Override
    protected void afterMoveApplied(int move) {
    }

    @Override
    protected String topText() {
        return (flipped ? "黑 " + black : "白 " + white) + "  ·  观战";
    }

    @Override
    protected String bottomText() {
        return (flipped ? "白 " + white : "黑 " + black)
                + (gameId.length() > 0 ? "  ·  " + gameId : "");
    }

    @Override
    protected String baseStatusText() {
        if (!connected) {
            return "正在连接观战流…";
        }
        if (updates == 0) {
            return "已连接，等待棋局更新…";
        }
        return "已收到 " + updates + " 次更新";
    }

    @Override
    protected String turnText() {
        return board.side == Chess.WHITE ? "白方走子" : "黑方走子";
    }

    // -------------------------------------------------------------- 数据流

    private void startFeed() {
        connected = false;
        updateStatus();
        DesktopAsync.daemon("tv", new Runnable() {
            public void run() {
                try {
                    api.streamTvFeed(new LichessApi.JsonSink() {
                        public void onJson(Json line) {
                            handle(line);
                        }
                    }, cancelFlag);
                } catch (final NetException e) {
                    if (!cancelFlag.cancelled()) {
                        Log.w("TV", "观战流断开: " + e.getMessage());
                        DesktopAsync.post(new Runnable() {
                            public void run() {
                                if (!destroyed) {
                                    setStatusText("观战流断开：" + e.getMessage(),
                                            DesktopTheme.WARN);
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    Log.e("TV", "观战流异常", t);
                }
            }
        });
    }

    private void handle(Json j) {
        final String t = j.str("t", "");
        final Json d = j.obj("d");
        if ("featured".equals(t)) {
            final String fen = d.str("fen", "");
            final String id = d.str("gameId", "");
            final Json players = d.arr("players");
            String w = "?";
            String b = "?";
            for (int i = 0; i < players.size(); i++) {
                Json p = players.at(i);
                if (p == null) {
                    continue;
                }
                Json user = p.obj("user");
                String name = user.str("name", p.str("name", "?"));
                if (i == 0) {
                    w = name;
                } else {
                    b = name;
                }
            }
            final String fw = w;
            final String fb = b;
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (destroyed) {
                        return;
                    }
                    white = fw;
                    black = fb;
                    gameId = id;
                    applyFen(fen, "");
                    connected = true;
                    updates++;
                    updateStatus();
                }
            });
        } else if ("fen".equals(t)) {
            final String fen = d.str("fen", "");
            final String lm = d.str("lm", "");
            DesktopAsync.post(new Runnable() {
                public void run() {
                    if (destroyed) {
                        return;
                    }
                    applyFen(fen, lm);
                    connected = true;
                    updates++;
                    updateStatus();
                }
            });
        }
    }

    private void applyFen(String fen, String lastMoveUci) {
        if (fen == null || fen.length() == 0) {
            return;
        }
        Board b = boardView.board();
        if (!b.setFen(fen)) {
            Log.w("TV", "fen 解析失败: " + fen);
            return;
        }
        if (lastMoveUci != null && lastMoveUci.length() >= 4) {
            lastMove = Move.fromUci(lastMoveUci);
        }
        boardView.setLastMove(lastMove);
        boardView.refresh();
    }
}
