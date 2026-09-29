package org.lichessold.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import org.lichessold.chess.Chess;

/**
 * 棋子绘制。
 *
 * 造型数据在 {@link PieceArt}（由 scripts/make_pieces.py 从 Cburnett 的 SVG 生成），
 * 这里只负责把它画到画布上。
 *
 * 为什么不用 Unicode 棋子字符（♔♕♖…）：Android 2.3 的 Droid Sans 不覆盖
 * U+2654~U+265F，真机上会显示成方框。也不用 PNG：要按密度切图、徒增体积，
 * 放大还会糊。矢量只有一份数据，格子多大都清晰。
 *
 * 坐标是归一化的 0..1，所以直接对 Canvas 做 translate + scale，
 * 描边宽度也随之一并缩放（不用自己换算）。
 */
public final class Pieces {

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint.Cap[] CAPS = Paint.Cap.values();
    private static final Paint.Join[] JOINS = Paint.Join.values();

    static {
        FILL.setStyle(Paint.Style.FILL);
        LINE.setStyle(Paint.Style.STROKE);
    }

    private Pieces() {
    }

    /**
     * 把一枚棋子画进 (left, top, size, size) 方格。
     *
     * @param piece 棋子编码（正=白，负=黑，0=空）
     */
    public static void draw(Canvas c, int piece, float left, float top, float size) {
        if (piece == 0 || size <= 0f) {
            return;
        }
        int type = Chess.type(piece);
        if (type < Chess.PAWN || type > Chess.KING) {
            return;
        }
        int idx = (type - 1) * 2 + (piece > 0 ? 0 : 1);
        int n = PieceArt.count(idx);

        int save = c.save();
        c.translate(left, top);
        c.scale(size, size);
        for (int i = 0; i < n; i++) {
            Path p = PieceArt.path(idx, i);
            int fill = PieceArt.fillColor(idx, i);
            if (fill != 0) {
                FILL.setColor(fill);
                c.drawPath(p, FILL);
            }
            int stroke = PieceArt.strokeColor(idx, i);
            if (stroke != 0) {
                LINE.setColor(stroke);
                LINE.setStrokeWidth(PieceArt.strokeWidth(idx, i));
                LINE.setStrokeCap(CAPS[PieceArt.cap(idx, i)]);
                LINE.setStrokeJoin(JOINS[PieceArt.join(idx, i)]);
                c.drawPath(p, LINE);
            }
        }
        c.restoreToCount(save);
    }

    /** 提前构建 Path 缓存，避免第一帧卡顿。 */
    public static void warmUp() {
        for (int p = 0; p < 12; p++) {
            int n = PieceArt.count(p);
            for (int i = 0; i < n; i++) {
                PieceArt.path(p, i);
            }
        }
    }
}
