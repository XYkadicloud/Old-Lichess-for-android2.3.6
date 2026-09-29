package org.lichessold.desktop;

import java.awt.Color;

import org.lichessold.chess.Ai;
import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.util.Log;

/**
 * 离线人机对战。对应 Android 版的 {@code org.lichessold.ui.AiGameActivity}。
 *
 * 引擎跑在后台线程，不阻塞界面。强度按 Lichess AI 等级 1~8 映射到时间预算
 * （见 {@link Ai#timeBudgetForLevel}）。
 *
 * 桌面 CPU 比 832MHz 的 ARM11 快得多，同样的时间预算下实际深度会高不少，
 * 所以等级 8 在这里会明显更强 —— 这是引擎本身的特性，不是 bug。
 */
public final class AiGamePanel extends GamePanel {

    private static final long serialVersionUID = 1L;

    private Ai ai;
    private int level;
    private boolean aiIsWhite;       // 人类默认执白
    private boolean thinking;
    private volatile boolean destroyed;

    public AiGamePanel() {
        super();
        // 子类构造函数最后一步必须调 start()：字段初始化完才能摆局面刷界面
        start();
    }

    @Override
    protected String headerTitle() {
        return "人机对战";
    }

    @Override
    protected void onBoardReady() {
        level = DesktopPrefs.get().getAiLevel();
        ai = new Ai();
        // 人类一方永远在下方：AI 执白时棋盘要翻转，AI 执黑时不用。
        flipped = aiIsWhite;
        boardView.setFlipped(flipped);
        // 如果 AI 执白，开局就得让它先走
        maybeStartThinking();
    }

    /** 页面被换掉时调用，避免后台引擎回来时改到已经不显示的界面。 */
    public void dispose() {
        destroyed = true;
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
                DesktopApp.toast(AiGamePanel.this,
                        aiIsWhite ? "你执黑，AI 先走" : "你执白，你先走");
            }
        });

        action("等级 " + level, new Runnable() {
            public void run() {
                pickLevel();
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

        DesktopAsync.run("ai", new DesktopAsync.Job<Ai.Result>() {
            public Ai.Result run() {
                return ai.search(snapshot, depth, budget);
            }
        }, new DesktopAsync.Done<Ai.Result>() {
            public void done(Ai.Result r, Throwable error) {
                thinking = false;
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    Log.e("AI", "引擎出错", error);
                    DesktopApp.toast(AiGamePanel.this, "引擎出错: " + error);
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
            DesktopApp.toast(this, "现在不用提示");
            return;
        }
        final Board snapshot = board.copy();
        final long budget = Math.max(600L, Ai.timeBudgetForLevel(level) / 2);
        setStatusText("正在计算提示…", DesktopTheme.BLUE);
        DesktopAsync.run("hint", new DesktopAsync.Job<Ai.Result>() {
            public Ai.Result run() {
                Ai hintAi = new Ai();
                return hintAi.search(snapshot, Ai.depthForLevel(level) + 1, budget);
            }
        }, new DesktopAsync.Done<Ai.Result>() {
            public void done(Ai.Result r, Throwable error) {
                if (destroyed) {
                    return;
                }
                if (error != null || r == null || r.move == Move.NONE) {
                    DesktopApp.toast(AiGamePanel.this, "算不出提示");
                    updateStatus();
                    return;
                }
                boardView.setHint(Move.from(r.move), Move.to(r.move));
                DesktopApp.toast(AiGamePanel.this,
                        "建议 " + Move.toUci(r.move) + "（评分 " + r.score + "）");
                refreshStatusText();
            }
        });
    }

    private void undoFullMove() {
        if (thinking) {
            DesktopApp.toast(this, "电脑正在思考，稍等");
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
            DesktopApp.toast(this, "没有可撤销的走子");
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

    private void pickLevel() {
        Object[] options = new Object[8];
        for (int i = 0; i < 8; i++) {
            options[i] = "等级 " + (i + 1) + "（思考约 "
                    + (Ai.timeBudgetForLevel(i + 1) / 1000.0) + " 秒）";
        }
        Object pick = javax.swing.JOptionPane.showInputDialog(this,
                "离线 AI 强度", "选择等级", javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, options, options[level - 1]);
        if (pick == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            if (options[i].equals(pick)) {
                level = i + 1;
                break;
            }
        }
        DesktopPrefs.get().setAiLevel(level);
        DesktopApp.toast(this, "AI 强度已设为 " + level + "（新局生效于下一次走子）");
        updateStatus();
    }

    /** 让标题胶囊也能反映当前等级。 */
    @Override
    protected void onGameFinished(int status) {
        setStatusText(baseStatusText(), new Color(0xA03030));
    }
}
