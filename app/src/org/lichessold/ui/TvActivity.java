package org.lichessold.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.util.Log;

/**
 * 观战：Lichess 首页焦点对局（/api/tv/feed）。
 *
 * 这个接口是公开的，不需要令牌，所以没登录也能用。
 * 只读：不能走子，只跟着服务端推的 fen 更新棋盘。
 */
public class TvActivity extends BoardGameActivity {

    private LichessApi api;
    private final LichessApi.Flag cancelFlag = new LichessApi.Flag();

    private String white = "?";
    private String black = "?";
    private String gameId = "";
    private boolean connected;
    private int updates;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            api = Net.api(this);
        } catch (NetException e) {
            Ui.toast(this, e.getMessage());
            finish();
            return;
        }
        boardView.setInteractive(false);
        startFeed();
    }

    @Override
    protected void onDestroy() {
        cancelFlag.cancel();
        super.onDestroy();
    }

    @Override
    protected String headerTitle() {
        return "观战";
    }

    @Override
    protected void buildActions() {
        action("翻转", new Runnable() {
            public void run() {
                flipped = !flipped;
                boardView.setFlipped(flipped);
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
        Thread t = new Thread(new Runnable() {
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
                        Async.post(new Runnable() {
                            public void run() {
                                if (!isFinishing()) {
                                    statusLine.setText("观战流断开：" + e.getMessage());
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    Log.e("TV", "观战流异常", t);
                }
            }
        }, "lichessold-tv");
        t.setDaemon(true);
        t.start();
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
                boolean isWhite = i == 0;
                if (isWhite) {
                    w = name;
                } else {
                    b = name;
                }
            }
            final String fw = w;
            final String fb = b;
            Async.post(new Runnable() {
                public void run() {
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
            Async.post(new Runnable() {
                public void run() {
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
