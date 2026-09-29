package org.lichessold.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import org.lichessold.chess.Board;
import org.lichessold.chess.Chess;
import org.lichessold.chess.Move;
import org.lichessold.chess.MoveGen;

/**
 * 棋盘视图。Canvas 手绘，不依赖任何图片资源，配色对齐 lichess。
 *
 * 交互：点选己方棋子 → 高亮可落子格 → 点目标格落子。
 *
 * 视觉细节对齐 lichess：
 *  - 空格用实心圆点，有子的格用圆环（不遮挡棋子）
 *  - 最近一步黄绿高亮，选中格深绿高亮
 *  - 被将军的王用红色径向渐变
 *  - 边线上有坐标字母数字
 */
public class BoardView extends View {

    private static final int LIGHT = Theme.BOARD_LIGHT;
    private static final int DARK = Theme.BOARD_DARK;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

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
    private float coordTextSize;

    public BoardView(Context context) {
        super(context);
        setFocusable(true);
        setBackgroundColor(Theme.BG);
        coordPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // ------------------------------------------------------------- 外部接口

    public interface Listener {
        /** 玩家尝试走子。返回 true 表示已接受。 */
        boolean onMoveAttempt(int from, int to);

        /** 选中格变化，-1 表示取消选中。 */
        void onSelectionChanged(int square);
    }

    public void setBoard(Board b) {
        this.board = b;
        invalidate();
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
            invalidate();
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
        invalidate();
    }

    public void setCheckSquare(int sq) {
        this.checkSquare = sq;
        invalidate();
    }

    public void setHint(int from, int to) {
        this.hintFrom = from;
        this.hintTo = to;
        invalidate();
    }

    public void clearHint() {
        this.hintFrom = -1;
        this.hintTo = -1;
        invalidate();
    }

    public void clearSelection() {
        if (selected != -1 || targetCount > 0) {
            selected = -1;
            targetCount = 0;
            invalidate();
        }
    }

    public int selectedSquare() {
        return selected;
    }

    /** 外部（收到对手走子后）刷新。 */
    public void refresh() {
        computeCheckSquare();
        invalidate();
    }

    // --------------------------------------------------------------- 绘制

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGeometry(w, h);
    }

    private void computeGeometry(int w, int h) {
        // 留一点边距给坐标和边框
        float pad = Math.min(w, h) * 0.015f;
        float size = Math.min(w, h) - pad * 2f;
        squareSize = size / 8f;
        originX = (w - size) / 2f;
        originY = (h - size) / 2f;
        coordTextSize = Math.max(7f, squareSize * 0.26f);
        coordPaint.setTextSize(coordTextSize);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (board == null) {
            return;
        }
        if (squareSize <= 0) {
            computeGeometry(getWidth(), getHeight());
        }
        if (squareSize <= 0) {
            return;
        }

        float boardSize = squareSize * 8f;

        // 棋盘外框
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.BOARD_BORDER);
        rect.set(originX - squareSize * 0.06f, originY - squareSize * 0.06f,
                originX + boardSize + squareSize * 0.06f,
                originY + boardSize + squareSize * 0.06f);
        c.drawRect(rect, paint);

        // 底格
        paint.setStyle(Paint.Style.FILL);
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                float x = originX + viewCol(f) * squareSize;
                float y = originY + viewRow(r) * squareSize;
                paint.setColor(((f + r) & 1) == 1 ? LIGHT : DARK);
                c.drawRect(x, y, x + squareSize, y + squareSize, paint);
            }
        }

        // 高亮：最近一步
        if (lastMove != Move.NONE) {
            fillSquare(c, Move.from(lastMove), Theme.HILITE_LAST);
            fillSquare(c, Move.to(lastMove), Theme.HILITE_LAST);
        }
        // 高亮：引擎建议
        if (hintFrom >= 0) {
            fillSquare(c, hintFrom, Theme.HILITE_HINT);
            fillSquare(c, hintTo, Theme.HILITE_HINT);
        }
        // 高亮：选中格
        if (selected >= 0) {
            fillSquare(c, selected, Theme.HILITE_SEL);
        }
        // 高亮：被将军的王（径向渐变，像 lichess）
        if (checkSquare >= 0) {
            drawCheckGlow(c, checkSquare);
        }

        // 可落子提示：空格画圆点，有子画圆环
        for (int i = 0; i < targetCount; i++) {
            int sq = targets[i];
            float cx = originX + (viewCol(Chess.file(sq)) + 0.5f) * squareSize;
            float cy = originY + (viewRow(Chess.rank(sq)) + 0.5f) * squareSize;
            if (board.sq[sq] != 0) {
                // 有子：画圆环（描边），不遮挡棋子
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Theme.HILITE_TARGET);
                paint.setStrokeWidth(Math.max(2f, squareSize * 0.10f));
                c.drawCircle(cx, cy, squareSize * 0.42f, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                paint.setColor(Theme.HILITE_TARGET);
                c.drawCircle(cx, cy, squareSize * 0.16f, paint);
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
            Pieces.draw(c, piece, x, y, squareSize);
        }

        // 坐标（画在边线的格子里，颜色和格子底色相反）
        drawCoordinates(c);
    }

    private void drawCoordinates(Canvas c) {
        float boardSize = squareSize * 8f;
        for (int i = 0; i < 8; i++) {
            // 底边：列字母 a-h。视图第 i 列对应的真实文件号。
            int file = flipped ? 7 - i : i;
            // 底边那一行真实 rank：不翻转时是 0（白方底线），翻转时是 7
            int bottomRank = flipped ? 7 : 0;
            boolean lightSquare = ((file + bottomRank) & 1) == 1;
            coordPaint.setColor(lightSquare ? DARK : LIGHT);
            float x = originX + i * squareSize + squareSize * 0.06f;
            float y = originY + boardSize - squareSize * 0.08f;
            c.drawText(String.valueOf((char) ('a' + file)), x, y, coordPaint);

            // 左边：行号 1-8。视图第 (7-i) 行对应的真实 rank。
            int rank = flipped ? 7 - i : i;
            int leftFile = flipped ? 7 : 0;
            // 注意：这里要和格子的实际颜色一致，否则会出现"深底深字"看不见。
            // 第一版把 leftFile 写成了 flipped ? 0 : 7，翻转时坐标就隐形了。
            boolean lightRank = ((leftFile + rank) & 1) == 1;
            coordPaint.setColor(lightRank ? DARK : LIGHT);
            float x2 = originX + squareSize * 0.06f;
            float y2 = originY + (7 - i) * squareSize + coordTextSize * 1.05f;
            c.drawText(String.valueOf(rank + 1), x2, y2, coordPaint);
        }
    }

    private void drawCheckGlow(Canvas c, int sq) {
        float cx = originX + (viewCol(Chess.file(sq)) + 0.5f) * squareSize;
        float cy = originY + (viewRow(Chess.rank(sq)) + 0.5f) * squareSize;
        float radius = squareSize * 0.75f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[] { 0xFFFF2222, 0xCCFF0000, 0x00FF0000 },
                new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        c.drawRect(rect, paint);
        paint.setShader(null);
    }

    private void fillSquare(Canvas c, int sq, int color) {
        if (!Chess.onBoard(sq)) {
            return;
        }
        float x = originX + viewCol(Chess.file(sq)) * squareSize;
        float y = originY + viewRow(Chess.rank(sq)) * squareSize;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRect(x, y, x + squareSize, y + squareSize, paint);
    }

    private int viewCol(int file) {
        return flipped ? 7 - file : file;
    }

    private int viewRow(int rank) {
        return flipped ? rank : 7 - rank;
    }

    // --------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive || board == null) {
            return false;
        }
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        int sq = squareAt(e.getX(), e.getY());
        if (sq < 0) {
            return true;
        }
        int piece = board.sq[sq];
        boolean ownPiece = piece != 0 && Chess.colorOf(piece) == board.side;

        if (selected >= 0) {
            // 再点一次同一个子 → 取消选中
            if (sq == selected) {
                clearSelection();
                notifySelection();
                return true;
            }
            // 点的是可落子格 → 尝试走子
            if (isTarget(sq) && listener != null) {
                int from = selected;
                clearSelection();
                notifySelection();
                if (listener.onMoveAttempt(from, sq)) {
                    return true;
                }
                // 被拒绝：如果点的是自己的子，就改成选中它（方便改主意）
                if (ownPiece) {
                    select(sq);
                }
                return true;
            }
            // 点的是自己的另一个子 → 换选中
            if (ownPiece) {
                select(sq);
                return true;
            }
            // 点了非法目标 → 取消选中并给一次尝试（让上层决定是否提示）
            if (listener != null) {
                listener.onMoveAttempt(selected, sq);
            }
            clearSelection();
            notifySelection();
            return true;
        }

        if (ownPiece) {
            select(sq);
        }
        return true;
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
        invalidate();
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
