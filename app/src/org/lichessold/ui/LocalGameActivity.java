package org.lichessold.ui;

import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import org.lichessold.chess.Move;
import org.lichessold.store.Prefs;

/**
 * 离线双人对战。同一台手机上两个人轮流走。
 * 也是不联网时验证棋盘交互是否正常的最方便入口。
 */
public class LocalGameActivity extends BoardGameActivity {

    @Override
    protected String headerTitle() {
        return "双人对战";
    }

    @Override
    protected void onBoardReady() {
        flipped = Prefs.get(this).isFlipBoard();
        boardView.setFlipped(flipped);
    }

    @Override
    protected void buildActions() {
        action("悔棋", new Runnable() {
            public void run() {
                undoOne();
            }
        });

        action("翻转", new Runnable() {
            public void run() {
                flipped = !flipped;
                boardView.setFlipped(flipped);
                Prefs.get(LocalGameActivity.this).setFlipBoard(flipped);
                updateStatus();
            }
        });

        action("新局", new Runnable() {
            public void run() {
                Ui.confirm(LocalGameActivity.this, "重新开始", "当前对局会丢失，确定吗？",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                resetGame();
                            }
                        });
            }
        });
    }

    @Override
    protected boolean canMoveNow() {
        return !gameOver;
    }

    @Override
    protected void afterMoveApplied(int move) {
    }

    @Override
    protected String topText() {
        return flipped ? "白方" : "黑方";
    }

    @Override
    protected String bottomText() {
        return flipped ? "黑方" : "白方";
    }

    @Override
    protected boolean topActive() {
        return flipped ? board.side == org.lichessold.chess.Chess.WHITE
                : board.side == org.lichessold.chess.Chess.BLACK;
    }

    @Override
    protected boolean bottomActive() {
        return !topActive();
    }

    @Override
    protected String turnText() {
        return board.side == org.lichessold.chess.Chess.WHITE ? "轮到白方" : "轮到黑方";
    }

    private void undoOne() {
        if (board.ply() <= 0) {
            Ui.toastShort(this, "没有可撤销的走子");
            return;
        }
        board.unmake();
        if (sanHistory.size() > 0) {
            sanHistory.remove(sanHistory.size() - 1);
        }
        if (positionKeys.size() > 0) {
            positionKeys.remove(positionKeys.size() - 1);
        }
        lastMove = board.historySize > 0 ? board.history[board.historySize - 1] : Move.NONE;
        gameOver = false;
        resultMessage = "";
        boardView.setLastMove(lastMove);
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
    }
}
