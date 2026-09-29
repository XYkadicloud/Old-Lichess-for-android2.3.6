package org.lichessold.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;
import org.lichessold.chess.Pgn;
import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 谜题（Lichess Puzzle API）。
 *
 * 数据来源：
 *   /api/puzzle/daily  → 带 fen，直接摆盘
 *   /api/puzzle/next   → 不带 fen，只有 game.pgn + puzzle.initialPly，
 *                        必须自己把 PGN 走出 initialPly 步来重建局面
 *   /api/puzzle/batch/{angle} → 一批题，用来做连续练习
 *
 * 判定方式：solution 是一串 UCI。玩家走 solution[0]，对手自动走 solution[1]，
 * 玩家再走 solution[2]……走错就算失败。这是 Lichess 官方前端的做法。
 */
public class PuzzleActivity extends BoardGameActivity {

    private static final String[] ANGLES = {
            "mix", "short", "long", "veryLong", "mate", "mateIn1", "mateIn2", "mateIn3",
            "advantage", "crushing", "equality", "endgame", "middlegame", "opening",
            "rookEndgame", "pawnEndgame", "queenEndgame", "knightEndgame", "bishopEndgame",
    };
    private static final String[] ANGLE_LABELS = {
            "混合", "短题", "长题", "超长题", "将杀", "1步杀", "2步杀", "3步杀",
            "占优", "大优", "均势", "残局", "中局", "开局",
            "车残局", "兵残局", "后残局", "马残局", "象残局",
    };

    private LichessApi api;

    private String puzzleId = "";
    private int puzzleRating;
    private String[] solution = new String[0];
    private String themesText = "";
    private int solutionIndex;
    private boolean solved;
    private boolean failed;
    private String angle = "mix";

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
        angle = Prefs.get(this).getPuzzleAngle();
        loadPuzzle();
    }

    @Override
    protected String headerTitle() {
        return "谜题训练";
    }

    @Override
    protected void buildActions() {
        action("提示", new Runnable() {
            public void run() {
                showHint();
            }
        });

        action("重来", new Runnable() {
            public void run() {
                if (puzzleId.length() == 0) {
                    return;
                }
                loadById(puzzleId);
            }
        });

        action("下一题", new Runnable() {
            public void run() {
                loadPuzzle();
            }
        });

        action("类型", new Runnable() {
            public void run() {
                pickAngle();
            }
        });
    }

    @Override
    protected boolean canMoveNow() {
        return !gameOver && !solved && !failed && solutionIndex < solution.length
                && (solutionIndex % 2) == 0;   // 偶数下标才是玩家走
    }

    @Override
    protected void onCantMoveNow() {
        if (solved) {
            Ui.toastShort(this, "已经解出来了，点「下一题」");
        } else if (failed) {
            Ui.toastShort(this, "这题已经错了，点「重来」");
        } else {
            Ui.toastShort(this, "等待对方应招…");
        }
    }

    @Override
    protected void afterMoveApplied(int move) {
        checkAgainstSolution(Move.toUci(move));
    }

    @Override
    protected String topText() {
        if (puzzleId.length() == 0) {
            return "正在加载谜题…";
        }
        return "谜题 " + puzzleId + (puzzleRating > 0 ? "  ·  难度 " + puzzleRating : "");
    }

    @Override
    protected String bottomText() {
        return themesText.length() == 0 ? "" : themesText;
    }

    @Override
    protected String baseStatusText() {
        if (puzzleId.length() == 0) {
            return "加载中…";
        }
        if (solved) {
            return "解出来了！点「下一题」继续";
        }
        if (failed) {
            return "走错了，点「重来」重试本题";
        }
        if (solutionIndex == 0) {
            return "轮到你了，找出最佳着法";
        }
        return "第 " + (solutionIndex / 2 + 1) + " 步";
    }

    // -------------------------------------------------------------- 逻辑

    private void checkAgainstSolution(String uci) {
        if (solutionIndex >= solution.length) {
            return;
        }
        String expected = solution[solutionIndex];
        if (!expected.equalsIgnoreCase(uci)) {
            failed = true;
            gameOver = true;
            Log.i("Puzzle", "走错: 走了 " + uci + "，正确是 " + expected);
            Ui.toast(this, "走错了。正确着法是 " + expected);
            updateStatus();
            return;
        }
        solutionIndex++;
        if (solutionIndex >= solution.length) {
            solved = true;
            gameOver = true;
            Log.i("Puzzle", "解出谜题 " + puzzleId);
            Ui.toast(this, "正确！");
            updateStatus();
            return;
        }
        // 对手应招
        final String reply = solution[solutionIndex];
        solutionIndex++;
        boardView.setInteractive(false);
        boardView.postDelayed(new Runnable() {
            public void run() {
                if (isFinishing() || boardView == null) {
                    return;
                }
                applyExternalUci(reply);
                boardView.setInteractive(true);
                if (solutionIndex >= solution.length) {
                    solved = true;
                    gameOver = true;
                    Ui.toast(PuzzleActivity.this, "正确！");
                }
                updateStatus();
            }
        }, 350L);
    }

    private void showHint() {
        if (solutionIndex >= solution.length) {
            Ui.toastShort(this, "没有更多提示");
            return;
        }
        String uci = solution[solutionIndex];
        int mv = MoveGen.SHARED.findByUci(board, uci);
        if (mv == Move.NONE) {
            Ui.toastShort(this, "提示不可用");
            return;
        }
        boardView.setHint(Move.from(mv), Move.to(mv));
        Ui.toastShort(this, "看看高亮的两个格子");
    }

    private void pickAngle() {
        Ui.choose(this, "选择谜题类型", ANGLE_LABELS, indexOfAngle(angle),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        angle = ANGLES[which];
                        Prefs.get(PuzzleActivity.this).setPuzzleAngle(angle);
                        d.dismiss();
                        loadPuzzle();
                    }
                });
    }

    private static int indexOfAngle(String a) {
        for (int i = 0; i < ANGLES.length; i++) {
            if (ANGLES[i].equals(a)) {
                return i;
            }
        }
        return 0;
    }

    // -------------------------------------------------------------- 网络

    private void loadPuzzle() {
        resetState();
        statusLine.setText("正在加载谜题…");
        Async.run("puzzle", new Async.Job<Json>() {
            public Json run() throws Throwable {
                if ("mix".equals(angle)) {
                    return api.puzzleNext();
                }
                Json batch = api.puzzleBatch(angle, 1);
                Json puzzles = batch.arr("puzzles");
                if (puzzles.size() == 0) {
                    throw new NetException(NetException.STAGE_HTTP, "这个类型暂时没有题目");
                }
                Json p = puzzles.at(0);
                // batch 返回的格式与 next 类似，补上 puzzle 字段
                return p;
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    statusLine.setText("加载失败");
                    Ui.toast(PuzzleActivity.this, "加载谜题失败：\n" + error.getMessage());
                    return;
                }
                applyPuzzle(j);
            }
        });
    }

    private void loadById(final String id) {
        resetState();
        statusLine.setText("正在加载谜题…");
        Async.run("puzzleById", new Async.Job<Json>() {
            public Json run() throws Throwable {
                return api.puzzle(id);
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    Ui.toast(PuzzleActivity.this, "重来失败：" + error.getMessage());
                    return;
                }
                applyPuzzle(j);
            }
        });
    }

    private void resetState() {
        solutionIndex = 0;
        solved = false;
        failed = false;
        gameOver = false;
        resultMessage = "";
        boardView.clearHint();
        boardView.clearSelection();
    }

    private void applyPuzzle(Json root) {
        if (root == null || root.size() == 0) {
            statusLine.setText("没有拿到题目");
            return;
        }
        Json puzzle = root.obj("puzzle");
        if (puzzle.size() == 0) {
            puzzle = root;   // batch 里的元素可能就是 puzzle 本身
        }
        puzzleId = puzzle.str("id", "");
        puzzleRating = puzzle.i("rating", 0);
        solution = puzzle.arr("solution").asStringArray();
        String[] themes = puzzle.arr("themes").asStringArray();
        StringBuilder tb = new StringBuilder(48);
        for (int i = 0; i < themes.length && i < 4; i++) {
            if (i > 0) {
                tb.append(" / ");
            }
            tb.append(themeLabel(themes[i]));
        }
        themesText = tb.toString();

        String fen = puzzle.str("fen", "");
        String lastMoveUci = puzzle.str("lastMove", "");
        int initialPly = puzzle.i("initialPly", -1);
        Json gameObj = root.obj("game");
        String pgn = gameObj.str("pgn", "");

        boolean ok;
        if (fen.length() > 0) {
            ok = board.setFen(fen);
        } else if (pgn.length() > 0 && initialPly >= 0) {
            board.setStart();
            int applied = Pgn.replay(board, pgn, initialPly);
            ok = applied > 0;
            if (!ok) {
                Log.w("Puzzle", "PGN 重放失败，applied=" + applied);
            }
        } else {
            ok = false;
        }

        if (!ok) {
            statusLine.setText("题目局面解析失败");
            Ui.toast(this, "这题的局面解析不了，换一题试试");
            return;
        }

        sanHistory.clear();
        positionKeys.clear();
        positionKeys.add(Long.valueOf(board.positionKey()));
        lastMove = Move.NONE;
        if (lastMoveUci != null && lastMoveUci.length() >= 4) {
            lastMove = Move.fromUci(lastMoveUci);
        }
        boardView.setLastMove(lastMove);
        boardView.clearHint();
        boardView.clearSelection();
        boardView.setInteractive(true);
        boardView.refresh();

        flipped = board.side == Chess.BLACK;   // 让解题方在下方
        boardView.setFlipped(flipped);

        updateStatus();
        Log.i("Puzzle", "题目 " + puzzleId + " 难度 " + puzzleRating
                + " 解答 " + solution.length + " 步 主题 " + themesText
                + (fen.length() > 0 ? " (有 fen)" : " (由 PGN 重建, ply=" + initialPly + ")"));
    }

    private static String themeLabel(String t) {
        if (t == null) {
            return "";
        }
        if (t.equals("mateIn1")) {
            return "1步杀";
        }
        if (t.equals("mateIn2")) {
            return "2步杀";
        }
        if (t.equals("mateIn3")) {
            return "3步杀";
        }
        if (t.equals("fork")) {
            return "捉双";
        }
        if (t.equals("pin")) {
            return "牵制";
        }
        if (t.equals("skewer")) {
            return "串击";
        }
        if (t.equals("sacrifice")) {
            return "弃子";
        }
        if (t.equals("endgame")) {
            return "残局";
        }
        if (t.equals("middlegame")) {
            return "中局";
        }
        if (t.equals("opening")) {
            return "开局";
        }
        if (t.equals("advantage")) {
            return "占优";
        }
        if (t.equals("crushing")) {
            return "大优";
        }
        if (t.equals("equality")) {
            return "均势";
        }
        if (t.equals("short")) {
            return "短题";
        }
        if (t.equals("long")) {
            return "长题";
        }
        return t;
    }
}
