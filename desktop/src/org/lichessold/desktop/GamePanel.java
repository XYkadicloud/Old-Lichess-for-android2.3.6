package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.GameStatus;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;
import org.lichessold.chess.San;
import org.lichessold.util.Log;

/**
 * 所有"有棋盘的对局"界面的公共基类。对应 Android 版的
 * {@code org.lichessold.ui.BoardGameActivity}。
 *
 * 布局（桌面版比手机版宽松，右侧多了一列走子记录）：
 *
 *   ┌──────────────────────────────────────────┬────────────┐
 *   │ 对手名 ······································ [12:34]  │            │
 *   ├──────────────────────────────────────────┤  走子记录   │
 *   │                                          │  1. e4 e5  │
 *   │                棋盘（正方形）                │  2. Nf3 …  │
 *   │                                          │            │
 *   ├──────────────────────────────────────────┤            │
 *   │ 我方名 ······································ [08:21]  │            │
 *   ├──────────────────────────────────────────┤            │
 *   │ ● 轮到你走                                 │            │
 *   ├──────────────────────────────────────────┤            │
 *   │ [ 和棋 ][ 认输 ][ 聊天 ][ 重新同步 ]        │            │
 *   └──────────────────────────────────────────┴────────────┘
 *
 * 子类用 {@link #action} / {@link #actionPrimary} 声明底部按钮，
 * 「返回」由基类自动补上 —— 与 Android 版一致。
 */
public abstract class GamePanel extends JPanel implements BoardPanel.Listener {

    private static final long serialVersionUID = 1L;

    protected final Board board = new Board();
    protected final MoveGen gen = new MoveGen(300);

    protected BoardPanel boardView;
    protected JLabel topName;
    protected JLabel topClock;
    protected JLabel bottomName;
    protected JLabel bottomClock;
    protected JLabel statusLine;
    protected JLabel titleChip;
    protected JPanel topBar;
    protected JPanel bottomBar;
    protected JTextArea moveListArea;

    protected boolean flipped;
    protected int lastMove = Move.NONE;
    protected boolean gameOver;
    protected String resultMessage = "";

    protected final ArrayList<Long> positionKeys = new ArrayList<Long>();
    protected final ArrayList<String> sanHistory = new ArrayList<String>();

    /** 初始局面。在线对局可能是自定义 FEN。 */
    protected String initialFen = "startpos";

    /** 由 DesktopApp 注入的导航回调（点「返回」时用）。 */
    private Runnable onBack;

    private final ArrayList<Action> actions = new ArrayList<Action>();
    private boolean lastTopActive = true;
    private boolean lastBottomActive = true;

    protected GamePanel() {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);
        positionKeys.add(Long.valueOf(board.positionKey()));
        buildLayout();
        boardView.setBoard(board);
        boardView.setMoveGen(gen);
        boardView.setListener(this);
        // 注意：这里**不能**调 onBoardReady()/updateStatus()。
        //
        // updateStatus() 会回调 topText()/bottomText()，而子类的字段初始化
        // （puzzleId、game、white…）要等 super() 返回之后才跑 —— 在这里调
        // 就会拿到 null。Android 版没这个问题，因为那边是 Activity 生命周期
        // 回调（onCreate）在对象完全构造好之后才触发。
        //
        // 所以子类构造函数最后必须调一次 start()。
    }

    /**
     * 子类构造函数**最后一步**必须调它：摆初始局面 + 首帧刷新。
     *
     * 对应 Android 版 BoardGameActivity.onCreate 末尾的
     * {@code onBoardReady(); updateStatus();}。
     */
    protected final void start() {
        if (titleChip != null) {
            titleChip.setText(" " + headerTitle() + " ");
        }
        onBoardReady();
        boardView.setFlipped(flipped);
        updateStatus();
    }

    // -------------------------------------------------------------- 导航

    public void setOnBack(Runnable r) {
        this.onBack = r;
    }

    /**
     * 页面被换掉时调用（停网络流、停定时器）。默认什么都不做，
     * 有后台活动的子类覆盖它 —— 不覆盖的话，切走页面后流还在跑。
     */
    public void dispose() {
    }

    /** 点「返回」时的默认行为。子类可以覆盖（比如要顺带停掉网络流）。 */
    protected void goBack() {
        if (onBack != null) {
            onBack.run();
        }
    }

    // ------------------------------------------------------------------ 布局

    private void buildLayout() {
        // 顶部：对手信息
        topBar = buildInfoBar(true);
        add(topBar, BorderLayout.NORTH);

        // 中间：棋盘（用 GridBagLayout 居中）
        boardView = new BoardPanel();
        JPanel boardHolder = new JPanel(new GridBagLayout());
        boardHolder.setBackground(DesktopTheme.BG);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1.0;
        gc.weighty = 1.0;
        gc.insets = new Insets(6, 6, 6, 6);
        boardHolder.add(boardView, gc);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(DesktopTheme.BG);
        center.add(boardHolder, BorderLayout.CENTER);

        // 右侧：走子记录
        JPanel side = buildMoveListPanel();
        side.setPreferredSize(new Dimension(220, 100));

        JPanel middle = new JPanel(new BorderLayout(0, 0));
        middle.setBackground(DesktopTheme.BG);
        middle.add(center, BorderLayout.CENTER);
        middle.add(side, BorderLayout.EAST);
        add(middle, BorderLayout.CENTER);

        // 底部：我方信息 + 状态 + 按钮
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBackground(DesktopTheme.BG);

        bottomBar = buildInfoBar(false);
        bottomBar.setAlignmentX(LEFT_ALIGNMENT);
        south.add(bottomBar);

        statusLine = new JLabel(" ");
        statusLine.setFont(DesktopTheme.ui(13));
        statusLine.setForeground(DesktopTheme.TEXT);
        statusLine.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        statusLine.setAlignmentX(LEFT_ALIGNMENT);
        south.add(statusLine);

        buildActions();
        JPanel buttons = buildButtonRow();
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        south.add(buttons);

        south.add(Box.createVerticalStrut(6));
        add(south, BorderLayout.SOUTH);
    }

    private JPanel buildMoveListPanel() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(DesktopTheme.BG);
        wrap.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, DesktopTheme.BORDER));

        JLabel title = DesktopTheme.label("走子记录", 12, DesktopTheme.TEXT_DIM);
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        wrap.add(title, BorderLayout.NORTH);

        moveListArea = DesktopTheme.output();
        moveListArea.setBackground(DesktopTheme.BG);
        moveListArea.setFont(DesktopTheme.MONO);
        moveListArea.setForeground(DesktopTheme.TEXT);
        JScrollPane sp = DesktopTheme.scroll(moveListArea);
        wrap.add(sp, BorderLayout.CENTER);
        return wrap;
    }

    /**
     * 玩家信息条：一行里塞下 [标题胶囊] [名字] [时钟]。
     * 与 Android 版一致 —— 标题只在上面那条显示。
     */
    private JPanel buildInfoBar(final boolean top) {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(DesktopTheme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        if (top) {
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.setOpaque(false);
            // 文字留到 start() 里填：构造期调子类的 headerTitle() 虽然现在
            // 都是返回常量、不会有问题，但那是"靠子类自觉"的正确性，
            // 统一挪到 start() 就不用再想这件事。
            titleChip = new JLabel(" ");
            titleChip.setFont(DesktopTheme.uiBold(11));
            titleChip.setForeground(DesktopTheme.BG);
            titleChip.setOpaque(true);
            titleChip.setBackground(DesktopTheme.GREEN);
            left.add(titleChip);
            topName = DesktopTheme.label("", 13, DesktopTheme.TEXT);
            left.add(topName);
            bar.add(left, BorderLayout.CENTER);
        } else {
            bottomName = DesktopTheme.label("", 13, DesktopTheme.TEXT);
            bottomName.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
            bar.add(bottomName, BorderLayout.CENTER);
        }

        JLabel clock = new JLabel(" ");
        clock.setFont(DesktopTheme.MONO.deriveFont(15f));
        clock.setOpaque(true);
        clock.setBackground(DesktopTheme.PANEL_ALT);
        clock.setForeground(DesktopTheme.TEXT_BRIGHT);
        clock.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
        if (top) {
            topClock = clock;
        } else {
            bottomClock = clock;
        }
        bar.add(clock, BorderLayout.EAST);

        // 点玩家条 → 子类可以打开对手主页
        bar.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        bar.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                onPlayerBarTapped(top);
            }
        });
        return bar;
    }

    // -------------------------------------------------------------- 底部动作

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

    private JPanel buildButtonRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        row.setBackground(DesktopTheme.BG);

        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            JButton b = a.primary ? DesktopTheme.smallPrimary(a.label)
                    : DesktopTheme.smallButton(a.label);
            b.setPreferredSize(new Dimension(
                    Math.max(64, b.getFontMetrics(b.getFont()).stringWidth(a.label) + 22), 30));
            b.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    try {
                        a.run.run();
                    } catch (Throwable t) {
                        Log.e("Game", "按钮动作异常: " + a.label, t);
                    }
                }
            });
            row.add(b);
        }

        // 「返回」由基类统一补，子类不用重复写
        JButton back = DesktopTheme.smallButton("返回");
        back.setPreferredSize(new Dimension(64, 30));
        back.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                goBack();
            }
        });
        row.add(back);
        return row;
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
        DesktopApp.toast(this, "这一步不合法");
    }

    protected void onCantMoveNow() {
        DesktopApp.toast(this, "现在还不能走子");
    }

    /** 点了上方/下方的玩家条（在线对局用它打开对手主页）。 */
    protected void onPlayerBarTapped(boolean top) {
    }

    /**
     * 局面状态是不是以服务端为准。
     *
     * 在线对局必须返回 true：本地的"将死/逼和/三次重复"判定只是估算，
     * 一旦误判就会把 gameOver 置真，界面直接不让走子。
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
            DesktopApp.toast(this, "对局已结束");
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
            statusLine.setForeground(DesktopTheme.GREEN_LIGHT);
            statusLine.setText("已选中 " + Chess.squareName(square) + "，点绿点落子");
        }
    }

    private void askPromotion(final int from, final int to) {
        final int[] promos = Chess.PROMOTIONS;
        Object[] options = { "升变为皇后", "升变为车", "升变为象", "升变为马" };
        int pick = JOptionPane.showOptionDialog(this, "选择升变棋子", "升变",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                options, options[0]);
        if (pick < 0) {
            return;
        }
        int mv = Move.of(from, to, promos[pick]) | Move.FLAG_PROMOTION;
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
        refreshMoveList();
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
        boardView.repaint();
    }

    /** 把 SAN 历史渲染成 "1. e4 e5 / 2. Nf3 ..." 的列表。 */
    protected void refreshMoveList() {
        if (moveListArea == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(sanHistory.size() * 8);
        for (int i = 0; i < sanHistory.size(); i++) {
            if (i % 2 == 0) {
                sb.append(i / 2 + 1).append(". ");
            }
            sb.append(sanHistory.get(i));
            if (i % 2 == 0) {
                sb.append(' ');
            } else {
                sb.append('\n');
            }
        }
        moveListArea.setText(sb.toString());
        moveListArea.setCaretPosition(moveListArea.getDocument().getLength());
    }

    private void styleClock(JLabel v, boolean active, boolean isTop) {
        boolean last = isTop ? lastTopActive : lastBottomActive;
        if (last == active) {
            return;
        }
        if (isTop) {
            lastTopActive = active;
        } else {
            lastBottomActive = active;
        }
        v.setBackground(active ? DesktopTheme.GREEN_LIGHT : DesktopTheme.PANEL_ALT);
        v.setForeground(active ? DesktopTheme.BG : DesktopTheme.TEXT_BRIGHT);
        v.repaint();
    }

    /** 只刷新两个时钟显示（在线对局的倒计时每秒调这个）。 */
    protected void refreshClocks() {
        String tc = topClockText();
        topClock.setText(tc.length() == 0 ? " " : tc);
        String bc = bottomClockText();
        bottomClock.setText(bc.length() == 0 ? " " : bc);
        styleClock(topClock, topActive() && !gameOver, true);
        styleClock(bottomClock, bottomActive() && !gameOver, false);
    }

    /** 只刷状态文字与颜色，不重算局面（走子高亮时用）。 */
    protected void refreshStatusText() {
        String text = baseStatusText();
        Color color;
        if (gameOver) {
            color = DesktopTheme.RED;
            text = "■ " + text;
        } else if (board.inCheck(board.side)) {
            color = DesktopTheme.WARN;
            text = "▲ 将军！" + (turnText().length() > 0 ? "  " + turnText() : "");
        } else if (isRepetition()) {
            color = DesktopTheme.GREEN_LIGHT;
            text = "≡ " + text;
        } else if (canMoveNow()) {
            color = DesktopTheme.GREEN_LIGHT;
            text = "● " + text;
        } else {
            color = DesktopTheme.TEXT_DIM;
            text = "○ " + text;
        }
        statusLine.setForeground(color);
        statusLine.setText(text);
    }

    protected void onGameFinished(int status) {
    }

    /** 子类要主动改状态行（在线对局的临时提示）时用它。 */
    protected void setStatusText(String text, Color color) {
        statusLine.setForeground(color);
        statusLine.setText(text);
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

    /** 让子类可以在 UI 线程上安全地改界面（网络回调会用到）。 */
    protected static void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}
