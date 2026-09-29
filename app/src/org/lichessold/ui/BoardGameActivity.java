package org.lichessold.ui;

import java.util.ArrayList;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.GameStatus;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;
import org.lichessold.chess.San;
import org.lichessold.util.Log;

/**
 * 所有"有棋盘的对局"界面的公共基类。
 *
 * 布局原则：**屏幕上除了棋盘，其余一律压到最扁**。
 * 机器只有 320×240，棋盘是唯一值得占地方的东西，所以：
 *
 *   第 1 行  标题小胶囊 + 对手名 + 对手时钟      （约 21dp）
 *   棋盘      吃掉全部剩余空间，按屏宽铺满
 *   第 2 行  我方名 + 我方时钟                  （约 21dp）
 *   第 3 行  状态（颜色随状态变）                （约 17dp）
 *   第 4 行  按钮，一行，最多 4 个 +「≡」更多      （约 32dp）
 *
 * 整屏是全屏的（隐藏系统状态栏）。
 *
 * 按钮规则：子类用 {@link #action} 声明动作，基类统一排版 ——
 * 不超过 4 个就平铺，超过就把前 3 个平铺、其余收进「≡」弹出菜单，
 * 免得挤成两行把棋盘压小。「返回」由基类自动补上。
 */
public abstract class BoardGameActivity extends Activity implements BoardView.Listener {

    /** 按钮行最多平铺几个。 */
    private static final int MAX_INLINE_BUTTONS = 5;

    protected final Board board = new Board();
    protected final MoveGen gen = new MoveGen(300);

    protected BoardView boardView;
    protected TextView topName;
    protected TextView topClock;
    protected TextView bottomName;
    protected TextView bottomClock;
    protected TextView statusLine;
    protected LinearLayout topBar;
    protected LinearLayout bottomBar;
    protected TextView titleChip;

    protected boolean flipped;
    protected int lastMove = Move.NONE;
    protected boolean gameOver;
    protected String resultMessage = "";

    /** 上一次的时钟高亮状态，避免每 500ms 重建 Drawable。 */
    private boolean lastTopActive = true;
    private boolean lastBottomActive = true;

    protected final ArrayList<Long> positionKeys = new ArrayList<Long>();
    protected final ArrayList<String> sanHistory = new ArrayList<String>();

    /** 初始局面。在线对局可能是自定义 FEN。 */
    protected String initialFen = "startpos";

    /** 子类声明的底部动作，按声明顺序排版。 */
    private final ArrayList<Action> actions = new ArrayList<Action>();

    // -------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.fullscreen(this);
        positionKeys.add(Long.valueOf(board.positionKey()));
        Pieces.warmUp();
        setContentView(buildLayout());
        boardView.setBoard(board);
        boardView.setMoveGen(gen);
        boardView.setListener(this);
        boardView.setFlipped(flipped);
        onBoardReady();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (boardView != null) {
            boardView.setListener(null);
        }
    }

    // ------------------------------------------------------------------ 布局

    private View buildLayout() {
        LinearLayout root = Ui.column(this);
        root.setPadding(Theme.dp(this, 1), 0, Theme.dp(this, 1), 0);

        // 第 1 行：标题 + 对手
        topBar = buildInfoBar(true);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 棋盘：吃掉全部剩余空间
        boardView = new BoardView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1f);
        bp.gravity = Gravity.CENTER;
        bp.topMargin = Theme.dp(this, 1);
        bp.bottomMargin = Theme.dp(this, 1);
        root.addView(boardView, bp);

        // 第 2 行：我方
        bottomBar = buildInfoBar(false);
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第 3 行：状态
        statusLine = new TextView(this);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLine.setTextColor(Theme.TEXT);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(TextUtils.TruncateAt.END);
        statusLine.setPadding(Theme.dp(this, 3), Theme.dp(this, 1),
                Theme.dp(this, 3), Theme.dp(this, 1));
        root.addView(statusLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第 4 行：按钮（一行，超出的进「≡」）
        buildActions();
        root.addView(buildButtonRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /**
     * 玩家信息条：一行里塞下 [标题] [名字] [时钟]。
     *
     * 为什么压到一行：原来标题栏 + 上下两条玩家条 + 状态条一共吃掉约 140dp，
     * 棋盘只剩不到 200dp 高，8 个格子每个才 18px 左右，根本看不清。
     */
    private LinearLayout buildInfoBar(final boolean top) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundDrawable(Theme.shape(Theme.PANEL, 0, Theme.dp(this, 3)));
        bar.setPadding(Theme.dp(this, 4), Theme.dp(this, 2),
                Theme.dp(this, 3), Theme.dp(this, 2));
        bar.setClickable(true);
        bar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onPlayerBarTapped(top);
            }
        });

        if (top) {
            titleChip = new TextView(this);
            titleChip.setText(headerTitle());
            titleChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            titleChip.setTextColor(Theme.BG);
            titleChip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleChip.setBackgroundDrawable(Theme.shape(Theme.GREEN, 0, Theme.dp(this, 2)));
            titleChip.setPadding(Theme.dp(this, 4), Theme.dp(this, 1),
                    Theme.dp(this, 4), Theme.dp(this, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = Theme.dp(this, 5);
            bar.addView(titleChip, cp);
        }

        TextView name = new TextView(this);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTextColor(Theme.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clock = new TextView(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clock.setTypeface(android.graphics.Typeface.MONOSPACE);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(Theme.dp(this, 5), 0, Theme.dp(this, 5), 0);
        bar.addView(clock);

        if (top) {
            topName = name;
            topClock = clock;
        } else {
            bottomName = name;
            bottomClock = clock;
        }
        return bar;
    }

    // -------------------------------------------------------------- 底部动作

    /** 一个底部动作。 */
    private static final class Action {
        final String label;
        final Runnable run;
        final boolean primary;

        Action(String label, Runnable run, boolean primary) {
            this.label = label;
            this.run = run;
            this.primary = primary;
        }
    }

    /** 声明一个普通动作按钮。 */
    protected final void action(String label, Runnable run) {
        actions.add(new Action(label, run, false));
    }

    /** 声明一个主色（绿底）动作按钮。 */
    protected final void actionPrimary(String label, Runnable run) {
        actions.add(new Action(label, run, true));
    }

    private View buildButtonRow() {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Theme.dp(this, 2), 0, 0);

        final ArrayList<Action> all = new ArrayList<Action>(actions);
        // 「返回」由基类统一补，子类不用重复写
        all.add(new Action("返回", new Runnable() {
            public void run() {
                finish();
            }
        }, false));

        if (all.size() <= MAX_INLINE_BUTTONS) {
            for (int i = 0; i < all.size(); i++) {
                addActionButton(row, all.get(i));
            }
        } else {
            final int inline = MAX_INLINE_BUTTONS - 1;
            for (int i = 0; i < inline; i++) {
                addActionButton(row, all.get(i));
            }
            Button more = Theme.compactButton(this, "≡");
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showMoreMenu(all, inline);
                }
            });
            addButtonView(row, more);
        }
        return row;
    }

    private void addActionButton(LinearLayout row, final Action a) {
        Button b = a.primary ? Theme.compactPrimary(this, a.label)
                : Theme.compactButton(this, a.label);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.run.run();
            }
        });
        addButtonView(row, b);
    }

    private void addButtonView(LinearLayout row, Button b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            p.leftMargin = Theme.dp(this, 2);
        }
        row.addView(b, p);
    }

    /** 把放不下的动作收进一个弹出菜单。 */
    private void showMoreMenu(final ArrayList<Action> all, final int from) {
        final String[] labels = new String[all.size() - from];
        for (int i = from; i < all.size(); i++) {
            labels[i - from] = all.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("更多操作")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        all.get(from + which).run.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // -------------------------------------------------------------- 子类接口

    /** 标题（显示在左上角的小胶囊里）。 */
    protected String headerTitle() {
        return "对局";
    }

    /** 棋盘就绪（摆初始局面、设翻转等）。 */
    protected void onBoardReady() {
    }

    /**
     * 子类在这里用 {@link #action} / {@link #actionPrimary} 声明底部按钮。
     * 「返回」由基类自动补上，不用自己写。
     */
    protected abstract void buildActions();

    /** 现在轮不轮得到本地玩家走子。 */
    protected abstract boolean canMoveNow();

    /** 本地走子已经落到棋盘上之后调用。 */
    protected abstract void afterMoveApplied(int move);

    protected void onIllegalMove(int from, int to) {
        Ui.toastShort(this, "这一步不合法");
    }

    protected void onCantMoveNow() {
        Ui.toastShort(this, "现在还不能走子");
    }

    /** 点了上方/下方的玩家条（在线对局用它打开对手主页）。 */
    protected void onPlayerBarTapped(boolean top) {
    }

    /**
     * 局面状态是不是以服务端为准。
     *
     * 在线对局必须返回 true：本地的"将死/逼和/三次重复"判定只是估算，
     * 一旦误判就会把 gameOver 置真，界面直接不让走子，而且用户完全不知道为什么。
     * 权威状态在服务端的 gameState.status 里，由子类自己处理。
     */
    protected boolean serverAuthoritative() {
        return false;
    }

    /** 上方玩家条的文字（名字 + 分数）。 */
    protected String topText() {
        return "";
    }

    /** 下方玩家条的文字。 */
    protected String bottomText() {
        return "";
    }

    /** 上方时钟文字，返回空串则不显示时钟。 */
    protected String topClockText() {
        return "";
    }

    protected String bottomClockText() {
        return "";
    }

    /** 上方/下方是否轮到走子（用来高亮玩家条）。 */
    protected boolean topActive() {
        return board.side == Chess.WHITE;
    }

    protected boolean bottomActive() {
        return board.side == Chess.BLACK;
    }

    // -------------------------------------------------------------- 走子流程

    @Override
    public boolean onMoveAttempt(int from, int to) {
        if (gameOver) {
            Ui.toastShort(this, "对局已结束");
            return false;
        }
        if (!canMoveNow()) {
            onCantMoveNow();
            return false;
        }

        int[] moves = gen.bufferAt(board.ply() + 3);
        int n = gen.legal(board, moves);
        int plain = Move.NONE;
        boolean needsPromotion = false;
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (Move.from(m) != from || Move.to(m) != to) {
                continue;
            }
            if (Move.has(m, Move.FLAG_PROMOTION)) {
                needsPromotion = true;
            } else {
                plain = m;
            }
        }

        if (needsPromotion) {
            askPromotion(from, to);
            return false;
        }
        if (plain == Move.NONE) {
            onIllegalMove(from, to);
            return false;
        }
        playLocalMove(plain);
        return true;
    }

    @Override
    public void onSelectionChanged(int square) {
        if (square < 0) {
            refreshStatusText();
        } else {
            statusLine.setTextColor(Theme.GREEN_LIGHT);
            statusLine.setText("已选中 " + Chess.squareName(square) + "，点绿点落子");
        }
    }

    private void askPromotion(final int from, final int to) {
        final int[] promos = Chess.PROMOTIONS;
        final String[] names = { "升变为皇后", "升变为车", "升变为象", "升变为马" };
        new AlertDialog.Builder(this)
                .setTitle("选择升变")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int mv = Move.of(from, to, promos[which]) | Move.FLAG_PROMOTION;
                        // 吃子标志不能漏，否则 unmake 还原不回来
                        if (board.sq[to] != 0) {
                            mv |= Move.FLAG_CAPTURE;
                        }
                        if (gen.findByUci(board, Move.toUci(mv)) == Move.NONE) {
                            onIllegalMove(from, to);
                            return;
                        }
                        playLocalMove(mv);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把本地走子落到棋盘上并刷新界面。 */
    protected void playLocalMove(int move) {
        String san = San.toSan(board, move);
        board.make(move);
        sanHistory.add(san);
        positionKeys.add(Long.valueOf(board.positionKey()));
        lastMove = move;
        boardView.setLastMove(move);
        boardView.clearHint();
        boardView.clearSelection();
        boardView.refresh();
        updateStatus();
        Log.i("Game", "本地走子 " + Move.toUci(move) + " (" + san + ")");
        afterMoveApplied(move);
    }

    /** 外部（网络/引擎）走子后调用，参数是 UCI。返回是否成功。 */
    protected boolean applyExternalUci(String uci) {
        int mv = gen.findByUci(board, uci);
        if (mv == Move.NONE) {
            Log.w("Game", "收到无法识别的走子: " + uci);
            return false;
        }
        sanHistory.add(San.toSan(board, mv));
        board.make(mv);
        positionKeys.add(Long.valueOf(board.positionKey()));
        lastMove = mv;
        boardView.setLastMove(mv);
        boardView.clearSelection();
        boardView.refresh();
        updateStatus();
        return true;
    }

    /** 重放整个走子列表（收到 gameFull / gameState 时用）。 */
    protected void setMovesFromUci(String movesSpaceSeparated) {
        if ("startpos".equals(initialFen) || initialFen == null || initialFen.length() == 0) {
            board.setStart();
        } else if (!board.setFen(initialFen)) {
            Log.w("Game", "初始 FEN 解析失败，退回标准开局: " + initialFen);
            board.setStart();
        }
        sanHistory.clear();
        positionKeys.clear();
        positionKeys.add(Long.valueOf(board.positionKey()));
        lastMove = Move.NONE;
        gameOver = false;
        resultMessage = "";
        if (movesSpaceSeparated != null && movesSpaceSeparated.length() > 0) {
            String[] list = movesSpaceSeparated.split(" ");
            for (int i = 0; i < list.length; i++) {
                int mv = gen.findByUci(board, list[i]);
                if (mv == Move.NONE) {
                    Log.w("Game", "重放中断，无法识别: " + list[i]);
                    break;
                }
                sanHistory.add(San.toSan(board, mv));
                board.make(mv);
                positionKeys.add(Long.valueOf(board.positionKey()));
                lastMove = mv;
            }
        }
        // 局面整体换掉了，之前选中的格子可能已经没有子，必须清掉
        boardView.clearSelection();
        boardView.clearHint();
        boardView.setLastMove(lastMove);
        boardView.refresh();
        updateStatus();
    }

    // -------------------------------------------------------------- 状态

    protected String baseStatusText() {
        if (gameOver) {
            return resultMessage;
        }
        if (isRepetition()) {
            return "三次重复局面（可判和）";
        }
        return turnText();
    }

    protected String turnText() {
        return board.side == Chess.WHITE ? "轮到白方走子" : "轮到黑方走子";
    }

    protected boolean isRepetition() {
        long key = board.positionKey();
        int count = 0;
        for (int i = 0; i < positionKeys.size(); i++) {
            Long k = positionKeys.get(i);
            if (k != null && k.longValue() == key) {
                count++;
            }
        }
        return count >= 3;
    }

    /** 重新判定局面状态并刷新界面。 */
    protected void updateStatus() {
        boardView.computeCheckSquare();

        topName.setText(topText());
        bottomName.setText(bottomText());
        refreshClocks();

        // 在线对局以服务端为准：本地不擅自宣布对局结束（见 serverAuthoritative）
        if (!gameOver && !serverAuthoritative()) {
            int status = GameStatus.of(board, gen);
            if (status != GameStatus.ONGOING) {
                gameOver = true;
                resultMessage = GameStatus.describe(status, board.side);
                Log.i("Game", "对局结束: " + resultMessage);
                onGameFinished(status);
            } else if (isRepetition()) {
                gameOver = true;
                resultMessage = "三次重复局面，和棋";
                onGameFinished(GameStatus.DRAW_REPETITION);
            }
        }
        refreshStatusText();
        boardView.invalidate();
    }

    /**
     * 切换时钟胶囊的"轮到谁"高亮。
     *
     * 在线对局每 500ms 调一次这里，如果每次都 new 一个 GradientDrawable，
     * 一秒就产生 2 个对象 —— 在 832MHz 的 ARM11 上是没必要的 GC 压力。
     * 所以只在状态真的变化时才换背景。
     */
    private void styleClock(TextView v, boolean active, boolean isTop) {
        if (v.getVisibility() == View.GONE) {
            return;
        }
        boolean last = isTop ? lastTopActive : lastBottomActive;
        if (last == active && v.getBackground() != null) {
            return;
        }
        if (isTop) {
            lastTopActive = active;
        } else {
            lastBottomActive = active;
        }
        v.setTextColor(active ? Theme.BG : Theme.TEXT_BRIGHT);
        v.setBackgroundDrawable(Theme.shape(active ? Theme.GREEN_LIGHT : Theme.PANEL_ALT,
                0, Theme.dp(this, 3)));
    }

    /**
     * 只刷新两个时钟显示（在线对局的倒计时每秒调这个）。
     * 不重算局面，开销极小。
     */
    protected void refreshClocks() {
        String tc = topClockText();
        topClock.setText(tc);
        topClock.setVisibility(tc.length() == 0 ? View.GONE : View.VISIBLE);
        String bc = bottomClockText();
        bottomClock.setText(bc);
        bottomClock.setVisibility(bc.length() == 0 ? View.GONE : View.VISIBLE);
        styleClock(topClock, topActive() && !gameOver, true);
        styleClock(bottomClock, bottomActive() && !gameOver, false);
    }

    /** 只刷状态文字与颜色，不重算局面（走子高亮时用）。 */
    protected void refreshStatusText() {
        String text = baseStatusText();
        int color = Theme.TEXT;
        if (gameOver) {
            color = Theme.RED;
            text = "■ " + text;
        } else if (board.inCheck(board.side)) {
            color = 0xFFE57373;
            text = "▲ 将军！" + (turnText().length() > 0 ? "  " + turnText() : "");
        } else if (isRepetition()) {
            color = Theme.GREEN_LIGHT;
            text = "≡ " + text;
        } else if (canMoveNow()) {
            color = Theme.GREEN_LIGHT;
            text = "● " + text;
        } else {
            color = Theme.TEXT_DIM;
            text = "○ " + text;
        }
        statusLine.setTextColor(color);
        statusLine.setText(text);
    }

    protected void onGameFinished(int status) {
    }

    // -------------------------------------------------------------- 工具

    protected static String formatClock(int millis) {
        if (millis < 0) {
            millis = 0;
        }
        int total = millis / 1000;
        int m = total / 60;
        int s = total % 60;
        StringBuilder sb = new StringBuilder(6);
        if (m >= 10) {
            sb.append(m);
        } else {
            sb.append('0').append(m);
        }
        sb.append(':');
        if (s >= 10) {
            sb.append(s);
        } else {
            sb.append('0').append(s);
        }
        return sb.toString();
    }
}
