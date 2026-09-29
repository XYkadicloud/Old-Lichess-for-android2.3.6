package org.lichessold.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import javax.swing.JPanel;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;

/**
 * 棋盘控件（Java2D 手绘）。对应 Android 版的 {@code org.lichessold.ui.BoardView}。
 *
 * 交互：点选己方棋子 → 高亮可落子格 → 点目标格落子。与手机版完全一致。
 *
 * 视觉细节对齐 lichess：
 *  - 空格用实心圆点，有子的格用圆环（不遮挡棋子）
 *  - 最近一步黄绿高亮，选中格深绿高亮
 *  - 被将军的王用红色径向渐变
 *  - 边线上有坐标字母数字
 */
public final class BoardPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 玩家尝试走子。返回 true 表示已接受。 */
    public interface Listener {
        boolean onMoveAttempt(int from, int to);

        /** 选中格变化，-1 表示取消选中。 */
        void onSelectionChanged(int square);
    }

    private final Font coordFont = DesktopTheme.uiBold(12);

    private Board board;
    private MoveGen gen = MoveGen.SHARED;
    private Listener listener;

    private boolean flipped;
    private boolean interactive = true;
    private int selected = -1;
    private int lastMove = Move.NONE;
    private int checkSquare = -1;
    private int hintFrom = -1;
    private int hintTo = -1;
    private final int[] targets = new int[MoveGen.MAX_MOVES];
    private int targetCount;

    private float originX;
    private float originY;
    private float squareSize;
    private float coordTextSize = 12f;

    public BoardPanel() {
        setBackground(DesktopTheme.BG);
        setOpaque(true);
        setFocusable(true);
        setPreferredSize(new Dimension(520, 520));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleClick(e.getX(), e.getY());
            }
        });
    }

    // ------------------------------------------------------------- 外部接口

    public void setBoard(Board b) {
        this.board = b;
        repaint();
    }

    public Board board() {
        return board;
    }

    public void setMoveGen(MoveGen g) {
        this.gen = g == null ? MoveGen.SHARED : g;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setFlipped(boolean f) {
        if (this.flipped != f) {
            this.flipped = f;
            repaint();
        }
    }

    public boolean isFlipped() {
        return flipped;
    }

    public void setInteractive(boolean i) {
        if (this.interactive != i) {
            this.interactive = i;
            if (!i) {
                clearSelection();
            }
        }
    }

    public boolean isInteractive() {
        return interactive;
    }

    public void setLastMove(int move) {
        this.lastMove = move;
        repaint();
    }

    public int lastMove() {
        return lastMove;
    }

    public void setCheckSquare(int sq) {
        this.checkSquare = sq;
        repaint();
    }

    public void setHint(int from, int to) {
        this.hintFrom = from;
        this.hintTo = to;
        repaint();
    }

    public void clearHint() {
        this.hintFrom = -1;
        this.hintTo = -1;
        repaint();
    }

    public void clearSelection() {
        if (selected != -1 || targetCount > 0) {
            selected = -1;
            targetCount = 0;
            repaint();
        }
    }

    public int selectedSquare() {
        return selected;
    }

    /** 外部（收到对手走子后）刷新。 */
    public void refresh() {
        computeCheckSquare();
        repaint();
    }

    // --------------------------------------------------------------- 几何

    /**
     * 棋盘永远是正方形，边长取可用空间的最小值。
     * 容器变大变小都自动跟随，不需要外部重新设尺寸。
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = getParent() == null ? null : getParent().getSize();
        int side = 520;
        if (d != null && d.width > 0 && d.height > 0) {
            side = Math.min(d.width, d.height);
        }
        side = Math.max(240, side);
        return new Dimension(side, side);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private void computeGeometry(int w, int h) {
        float pad = Math.min(w, h) * 0.015f;
        float size = Math.min(w, h) - pad * 2f;
        squareSize = size / 8f;
        originX = (w - size) / 2f;
        originY = (h - size) / 2f;
        coordTextSize = Math.max(9f, squareSize * 0.26f);
    }

    private int viewCol(int file) {
        return flipped ? 7 - file : file;
    }

    private int viewRow(int rank) {
        return flipped ? rank : 7 - rank;
    }

    // --------------------------------------------------------------- 绘制

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (board == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        computeGeometry(w, h);
        if (squareSize <= 0) {
            return;
        }

        Graphics2D g = DesktopTheme.quality(g0);
        float boardSize = squareSize * 8f;

        // 棋盘外框
        g.setColor(DesktopTheme.BOARD_BORDER);
        g.fill(new Rectangle2D.Float(
                originX - squareSize * 0.06f, originY - squareSize * 0.06f,
                boardSize + squareSize * 0.12f, boardSize + squareSize * 0.12f));

        // 底格
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                float x = originX + viewCol(f) * squareSize;
                float y = originY + viewRow(r) * squareSize;
                g.setColor(((f + r) & 1) == 1 ? DesktopTheme.BOARD_LIGHT
                        : DesktopTheme.BOARD_DARK);
                g.fill(new Rectangle2D.Float(x, y, squareSize, squareSize));
            }
        }

        // 高亮：最近一步
        if (lastMove != Move.NONE) {
            fillSquare(g, Move.from(lastMove), DesktopTheme.HILITE_LAST);
            fillSquare(g, Move.to(lastMove), DesktopTheme.HILITE_LAST);
        }
        // 高亮：引擎建议
        if (hintFrom >= 0) {
            fillSquare(g, hintFrom, DesktopTheme.HILITE_HINT);
            fillSquare(g, hintTo, DesktopTheme.HILITE_HINT);
        }
        // 高亮：选中格
        if (selected >= 0) {
            fillSquare(g, selected, DesktopTheme.HILITE_SEL);
        }
        // 高亮：被将军的王
        if (checkSquare >= 0) {
            drawCheckGlow(g, checkSquare);
        }

        // 可落子提示：空格画圆点，有子画圆环
        for (int i = 0; i < targetCount; i++) {
            int sq = targets[i];
            float cx = originX + (viewCol(Chess.file(sq)) + 0.5f) * squareSize;
            float cy = originY + (viewRow(Chess.rank(sq)) + 0.5f) * squareSize;
            if (board.sq[sq] != 0) {
                g.setColor(DesktopTheme.HILITE_TARGET);
                g.setStroke(new java.awt.BasicStroke(
                        Math.max(2f, squareSize * 0.10f)));
                float r = squareSize * 0.42f;
                g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));
            } else {
                g.setColor(DesktopTheme.HILITE_TARGET);
                float r = squareSize * 0.16f;
                g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));
            }
        }

        // 棋子
        for (int sq = 0; sq < 128; sq++) {
            if ((sq & 0x88) != 0) {
                continue;
            }
            int piece = board.sq[sq];
            if (piece == 0) {
                continue;
            }
            float x = originX + viewCol(Chess.file(sq)) * squareSize;
            float y = originY + viewRow(Chess.rank(sq)) * squareSize;
            Pieces2D.draw(g, piece, x, y, squareSize);
        }

        // 坐标
        drawCoordinates(g);
        g.dispose();
    }

    private void drawCoordinates(Graphics2D g) {
        float boardSize = squareSize * 8f;
        g.setFont(coordFont.deriveFont(coordTextSize));
        java.awt.FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < 8; i++) {
            // 底边：列字母 a-h
            int file = flipped ? 7 - i : i;
            int bottomRank = flipped ? 7 : 0;
            boolean lightSquare = ((file + bottomRank) & 1) == 1;
            g.setColor(lightSquare ? DesktopTheme.BOARD_DARK : DesktopTheme.BOARD_LIGHT);
            String letter = String.valueOf((char) ('a' + file));
            float x = originX + i * squareSize + squareSize * 0.06f;
            float y = originY + boardSize - squareSize * 0.08f;
            g.drawString(letter, x, y);

            // 左边：行号 1-8
            int rank = flipped ? 7 - i : i;
            int leftFile = flipped ? 7 : 0;
            boolean lightRank = ((leftFile + rank) & 1) == 1;
            g.setColor(lightRank ? DesktopTheme.BOARD_DARK : DesktopTheme.BOARD_LIGHT);
            String num = String.valueOf(rank + 1);
            float x2 = originX + squareSize * 0.06f;
            float y2 = originY + (7 - i) * squareSize + fm.getAscent() + squareSize * 0.06f;
            g.drawString(num, x2, y2);
        }
    }

    private void drawCheckGlow(Graphics2D g, int sq) {
        float cx = originX + (viewCol(Chess.file(sq)) + 0.5f) * squareSize;
        float cy = originY + (viewRow(Chess.rank(sq)) + 0.5f) * squareSize;
        float radius = squareSize * 0.75f;
        RadialGradientPaint paint = new RadialGradientPaint(
                new Point2D.Float(cx, cy), Math.max(1f, radius),
                new float[] { 0f, 0.55f, 1f },
                new Color[] { new Color(0xFF, 0x22, 0x22, 0xFF),
                        new Color(0xFF, 0x00, 0x00, 0xCC),
                        new Color(0xFF, 0x00, 0x00, 0x00) });
        g.setPaint(paint);
        g.fill(new Rectangle2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f));
        g.setPaint(null);
    }

    private void fillSquare(Graphics2D g, int sq, Color color) {
        if (!Chess.onBoard(sq)) {
            return;
        }
        float x = originX + viewCol(Chess.file(sq)) * squareSize;
        float y = originY + viewRow(Chess.rank(sq)) * squareSize;
        g.setColor(color);
        g.fill(new Rectangle2D.Float(x, y, squareSize, squareSize));
    }

    // --------------------------------------------------------------- 交互

    private void handleClick(int px, int py) {
        if (!interactive || board == null) {
            return;
        }
        int sq = squareAt(px, py);
        if (sq < 0) {
            return;
        }
        int piece = board.sq[sq];
        boolean ownPiece = piece != 0 && Chess.colorOf(piece) == board.side;

        if (selected >= 0) {
            // 再点一次同一个子 → 取消选中
            if (sq == selected) {
                clearSelection();
                notifySelection();
                return;
            }
            // 点的是可落子格 → 尝试走子
            if (isTarget(sq) && listener != null) {
                int from = selected;
                clearSelection();
                notifySelection();
                if (listener.onMoveAttempt(from, sq)) {
                    return;
                }
                // 被拒绝：如果点的是自己的子，就改成选中它（方便改主意）
                if (ownPiece) {
                    select(sq);
                }
                return;
            }
            // 点的是自己的另一个子 → 换选中
            if (ownPiece) {
                select(sq);
                return;
            }
            // 点了非法目标 → 取消选中并给一次尝试（让上层决定是否提示）
            if (listener != null) {
                listener.onMoveAttempt(selected, sq);
            }
            clearSelection();
            notifySelection();
            return;
        }

        if (ownPiece) {
            select(sq);
        }
    }

    private void select(int sq) {
        selected = sq;
        targetCount = 0;
        int[] moves = gen.bufferAt(board.ply() + 3);
        int n = gen.legal(board, moves);
        for (int i = 0; i < n && targetCount < targets.length; i++) {
            if (Move.from(moves[i]) == sq) {
                int to = Move.to(moves[i]);
                boolean dup = false;
                for (int k = 0; k < targetCount; k++) {
                    if (targets[k] == to) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    targets[targetCount++] = to;
                }
            }
        }
        notifySelection();
        repaint();
    }

    private void notifySelection() {
        if (listener != null) {
            listener.onSelectionChanged(selected);
        }
    }

    private boolean isTarget(int sq) {
        for (int i = 0; i < targetCount; i++) {
            if (targets[i] == sq) {
                return true;
            }
        }
        return false;
    }

    /** 屏幕坐标 -> 0x88 格子；不在棋盘上返回 -1。 */
    private int squareAt(float px, float py) {
        if (squareSize <= 0) {
            return -1;
        }
        int col = (int) ((px - originX) / squareSize);
        int row = (int) ((py - originY) / squareSize);
        if (col < 0 || col > 7 || row < 0 || row > 7) {
            return -1;
        }
        int file = flipped ? 7 - col : col;
        int rank = flipped ? row : 7 - row;
        return Chess.square(file, rank);
    }

    // ---------------------------------------------------------- 将军高亮

    public void computeCheckSquare() {
        if (board == null) {
            checkSquare = -1;
            return;
        }
        checkSquare = board.inCheck(board.side) ? board.kingSquare(board.side) : -1;
    }
}
