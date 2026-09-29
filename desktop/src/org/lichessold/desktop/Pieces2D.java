package org.lichessold.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

import org.lichessold.chess.Chess;

/**
 * 棋子绘制（Java2D）。对应 Android 版的 {@code org.lichessold.ui.Pieces}。
 *
 * 造型数据在 {@link PieceArt2D}（由 desktop/tools/make_pieceart2d.py 从
 * Android 版的 PieceArt.java 转换而来），这里只负责把它画到 Graphics2D 上。
 *
 * 坐标是归一化的 0..1，所以直接对 Graphics2D 做 translate + scale，
 * 描边宽度也随之一并缩放（不用自己换算）—— 与 Android 版的做法一致。
 */
public final class Pieces2D {

    private Pieces2D() {
    }

    /**
     * 把一枚棋子画进 (left, top, size, size) 方格。
     *
     * @param piece 棋子编码（正=白，负=黑，0=空）
     */
    public static void draw(Graphics2D g, int piece, float left, float top, float size) {
        if (piece == 0 || size <= 0f) {
            return;
        }
        int type = Chess.type(piece);
        if (type < Chess.PAWN || type > Chess.KING) {
            return;
        }
        // 索引与 Android 版一致：类型 1..6 -> 下标 0..11，白在前黑在后
        int idx = (type - 1) * 2 + (piece > 0 ? 0 : 1);
        int n = PieceArt2D.count(idx);

        AffineTransform saved = g.getTransform();
        g.translate(left, top);
        g.scale(size, size);

        for (int i = 0; i < n; i++) {
            Path2D.Float p = PieceArt2D.path(idx, i);

            int fill = PieceArt2D.fillColor(idx, i);
            if (fill != 0) {
                g.setColor(new Color(fill, true));
                g.fill(p);
            }
            int stroke = PieceArt2D.strokeColor(idx, i);
            if (stroke != 0) {
                g.setColor(new Color(stroke, true));
                float w = PieceArt2D.strokeWidth(idx, i);
                g.setStroke(new BasicStroke(w,
                        PieceArt2D.cap(idx, i), PieceArt2D.join(idx, i)));
                g.draw(p);
            }
        }

        g.setTransform(saved);
    }

    /** 提前构建 Path 缓存，避免第一帧卡顿。 */
    public static void warmUp() {
        PieceArt2D.warmUp();
    }
}
