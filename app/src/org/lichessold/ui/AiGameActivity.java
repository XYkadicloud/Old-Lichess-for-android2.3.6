package org.lichessold.ui;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import org.lichessold.chess.Ai;
import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.platform.Async;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 离线人机对战。引擎跑在后台线程，不阻塞界面。
 *
 * 引擎强度按 Lichess AI 等级 1~8 映射到时间预算（见 Ai.timeBudgetForLevel）。
 * GT-S5360 是 832MHz 单核 ARM11，等级 8 会思考好几秒 —— 所以界面上显示
 * "AI 思考中"，而不是让界面卡死。
 */
public class AiGameActivity extends BoardGameActivity {

    private Ai ai;
    private int level;
    private boolean aiIsWhite;       // 人类默认执白
    private boolean thinking;
    private volatile boolean destroyed;

    @Override
    protected String headerTitle() {
        return "人机对战";
    }

    @Override
    protected void onBoardReady() {
        level = Prefs.get(this).getAiLevel();
        ai = new Ai();
        // 人类一方永远在下方：AI 执白时棋盘要翻转，AI 执黑时不用。
        // （这里曾经写反过，导致人执白时自己的棋子在屏幕上方。）
        flipped = aiIsWhite;
        boardView.setFlipped(flipped);
        // 如果 AI 执白，开局就得让它先走
        maybeStartThinking();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    protected void buildActions() {
        action("提示", new Runnable() {
            public void run() {
                requestHint();
            }
        });

        action("悔棋", new Runnable() {
            public void run() {
                undoFullMove();
            }
        });

        action("新局", new Runnable() {
            public void run() {
                resetGame();
            }
        });

        action("换边重开", new Runnable() {
            public void run() {
                aiIsWhite = !aiIsWhite;
                flipped = aiIsWhite;   // 同上：人类永远在下方
                boardView.setFlipped(flipped);
                resetGame();
                Ui.toastShort(AiGameActivity.this,
                        aiIsWhite ? "你执黑，AI 先走" : "你执白，你先走");
            }
        });
    }

    private boolean isAiTurn() {
        return (board.side == Chess.WHITE) == aiIsWhite;
    }

    @Override
    protected boolean canMoveNow() {
        return !gameOver && !thinking && !isAiTurn();
    }

    @Override
    protected void afterMoveApplied(int move) {
        maybeStartThinking();
    }

    @Override
    protected String topText() {
        return "电脑 · 等级 " + level;
    }

    @Override
    protected String bottomText() {
        return "你";
    }

    @Override
    protected boolean topActive() {
        return isAiTurn();
    }

    @Override
    protected boolean bottomActive() {
        return !isAiTurn();
    }

    @Override
    protected String turnText() {
        return isAiTurn() ? "轮到电脑" : "轮到你走";
    }

    @Override
    protected String baseStatusText() {
        if (thinking) {
            return "电脑思考中…";
        }
        return super.baseStatusText();
    }

    // -------------------------------------------------------------- 引擎

    private void maybeStartThinking() {
        if (destroyed || gameOver || thinking || !isAiTurn()) {
            return;
        }
        thinking = true;
        updateStatus();

        final Board snapshot = board.copy();
        final int depth = Ai.depthForLevel(level);
        final long budget = Ai.timeBudgetForLevel(level);

        Async.run("ai", new Async.Job<Ai.Result>() {
            public Ai.Result run() {
                return ai.search(snapshot, depth, budget);
            }
        }, new Async.Done<Ai.Result>() {
            public void done(Ai.Result r, Throwable error) {
                thinking = false;
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    Log.e("AI", "引擎出错", error);
                    Ui.toast(AiGameActivity.this, "引擎出错: " + error);
                    updateStatus();
                    return;
                }
                if (r == null || r.move == Move.NONE) {
                    Log.w("AI", "引擎没有返回走子");
                    updateStatus();
                    return;
                }
                Log.i("AI", "引擎走子 " + Move.toUci(r.move) + "，" + r.describe());
                applyExternalUci(Move.toUci(r.move));
                updateStatus();
            }
        });
    }

    private void requestHint() {
        if (gameOver || thinking || isAiTurn()) {
            Ui.toastShort(this, "现在不用提示");
            return;
        }
        final Board snapshot = board.copy();
        final long budget = Math.max(600L, Ai.timeBudgetForLevel(level) / 2);
        statusLine.setTextColor(Theme.BLUE);
        statusLine.setText("正在计算提示…");
        Async.run("hint", new Async.Job<Ai.Result>() {
            public Ai.Result run() {
                Ai hintAi = new Ai();
                return hintAi.search(snapshot, Ai.depthForLevel(level) + 1, budget);
            }
        }, new Async.Done<Ai.Result>() {
            public void done(Ai.Result r, Throwable error) {
                if (destroyed) {
                    return;
                }
                if (error != null || r == null || r.move == Move.NONE) {
                    Ui.toastShort(AiGameActivity.this, "算不出提示");
                    updateStatus();
                    return;
                }
                boardView.setHint(Move.from(r.move), Move.to(r.move));
                Ui.toastShort(AiGameActivity.this,
                        "建议 " + Move.toUci(r.move) + "（评分 " + r.score + "）");
                refreshStatusText();
            }
        });
    }

    private void undoFullMove() {
        if (thinking) {
            Ui.toastShort(this, "电脑正在思考，稍等");
            return;
        }
        int steps = 0;
        while (board.ply() > 0 && steps < 2) {
            board.unmake();
            if (sanHistory.size() > 0) {
                sanHistory.remove(sanHistory.size() - 1);
            }
            if (positionKeys.size() > 0) {
                positionKeys.remove(positionKeys.size() - 1);
            }
            steps++;
            if (!isAiTurn()) {
                break;
            }
        }
        if (steps == 0) {
            Ui.toastShort(this, "没有可撤销的走子");
            return;
        }
        gameOver = false;
        resultMessage = "";
        lastMove = board.historySize > 0 ? board.history[board.historySize - 1] : Move.NONE;
        boardView.setLastMove(lastMove);
        boardView.clearHint();
        boardView.clearSelection();
        boardView.refresh();
        updateStatus();
    }

    private void resetGame() {
        board.setStart();
        sanHistory.clear();
        positionKeys.clear();
        positionKeys.add(Long.valueOf(board.positionKey()));
        lastMove = Move.NONE;
        gameOver = false;
        resultMessage = "";
        boardView.setLastMove(Move.NONE);
        boardView.clearHint();
        boardView.clearSelection();
        boardView.refresh();
        updateStatus();
        maybeStartThinking();
    }
}
