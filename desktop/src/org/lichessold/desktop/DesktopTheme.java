package org.lichessold.desktop;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.Border;

/**
 * 桌面版视觉风格。配色与 Android 版 {@code org.lichessold.ui.Theme} 完全一致，
 * 取自 lichess 官网深色主题：
 *
 *   背景 #161512，面板 #262421，主色（绿）#629924，正文 #bfbdb2
 *
 * Android 版用 GradientDrawable + StateListDrawable 自绘按钮，
 * 桌面版对应的是自绘的 {@link FlatButton}（圆角 + 悬停/按下态）。
 *
 * 字号不用 dp/sp：桌面屏幕不需要按密度换算，直接给磅值更直观。
 */
public final class DesktopTheme {

    // ---- 背景与面板
    public static final Color BG = new Color(0x161512);
    public static final Color PANEL = new Color(0x262421);
    public static final Color PANEL_ALT = new Color(0x2E2B28);
    public static final Color BORDER = new Color(0x3D3A37);

    // ---- 文字
    public static final Color TEXT = new Color(0xBFBDB2);
    public static final Color TEXT_BRIGHT = new Color(0xFFFFFF);
    public static final Color TEXT_DIM = new Color(0x8A877E);

    // ---- 主色
    public static final Color GREEN = new Color(0x629924);
    public static final Color GREEN_LIGHT = new Color(0x9CCB3B);
    public static final Color GREEN_PRESSED = new Color(0x4E7A1C);
    public static final Color RED = new Color(0xA03030);
    public static final Color RED_PRESSED = new Color(0x7E2525);
    public static final Color BLUE = new Color(0x3692E7);
    public static final Color WARN = new Color(0xE57373);

    // ---- 棋盘
    public static final Color BOARD_LIGHT = new Color(0xF0D9B5);
    public static final Color BOARD_DARK = new Color(0xB58863);
    public static final Color BOARD_BORDER = new Color(0x2A2724);
    public static final Color HILITE_LAST = new Color(0x9B, 0xC7, 0x00, 0x66);
    public static final Color HILITE_SEL = new Color(0x14, 0x55, 0x1E, 0x80);
    public static final Color HILITE_TARGET = new Color(0x14, 0x55, 0x1E, 0x80);
    public static final Color HILITE_CHECK = new Color(0xCC, 0x33, 0x33, 0x99);
    public static final Color HILITE_HINT = new Color(0x36, 0x92, 0xE7, 0x66);

    /** 等宽字体（时钟、日志、诊断输出）。 */
    public static final Font MONO = pickFont(
            new String[] { "Consolas", "DejaVu Sans Mono", "Menlo", "Monospaced" },
            Font.PLAIN, 13);

    private DesktopTheme() {
    }

    // ------------------------------------------------------------------ 字体

    /**
     * 按优先级挑第一个系统里存在的字体。
     *
     * 为什么要挑：Swing 的默认逻辑字体在不同平台上差别很大，
     * 而界面上全是中文，必须落到一个真的有中文字形的字体上，
     * 否则会出现方块。Windows 上首选「微软雅黑」，macOS 上「苹方」。
     */
    public static Font pickFont(String[] names, int style, int size) {
        java.util.Set<String> have = new java.util.HashSet<String>();
        String[] all = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        for (int i = 0; i < all.length; i++) {
            have.add(all[i]);
        }
        for (int i = 0; i < names.length; i++) {
            if (have.contains(names[i])) {
                return new Font(names[i], style, size);
            }
        }
        return new Font(Font.DIALOG, style, size);
    }

    private static final String[] UI_FONT_NAMES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
            "Noto Sans CJK SC", "Source Han Sans SC", "Dialog",
    };

    /** 界面正文字体。 */
    public static Font ui(int size) {
        return pickFont(UI_FONT_NAMES, Font.PLAIN, size);
    }

    /** 界面加粗字体。 */
    public static Font uiBold(int size) {
        return pickFont(UI_FONT_NAMES, Font.BOLD, size);
    }

    // ------------------------------------------------------------------ 绘制

    /** 打开抗锯齿。所有自绘的地方都要先调它。 */
    public static Graphics2D quality(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        return g2;
    }

    /** 圆角矩形填充 + 可选描边。 */
    public static void roundRect(Graphics2D g, int x, int y, int w, int h,
                                 int radius, Color fill, Color stroke) {
        if (fill != null) {
            g.setColor(fill);
            g.fillRoundRect(x, y, w, h, radius, radius);
        }
        if (stroke != null) {
            g.setColor(stroke);
            g.drawRoundRect(x, y, w - 1, h - 1, radius, radius);
        }
    }

    // ------------------------------------------------------------------ 构件

    /** 深色面板。 */
    public static JPanel panel() {
        JPanel p = new JPanel();
        p.setBackground(PANEL);
        p.setOpaque(true);
        return p;
    }

    /** 带描边的面板（圆角由自绘的 RoundedPanel 提供）。 */
    public static JPanel card() {
        RoundedPanel p = new RoundedPanel(PANEL, BORDER, 6);
        p.setLayout(new java.awt.BorderLayout());
        return p;
    }

    /** 带描边和圆角的面板。 */
    public static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color stroke;
        private final int radius;

        public RoundedPanel(Color fill, Color stroke, int radius) {
            this.fill = fill;
            this.stroke = stroke;
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = quality(g);
            roundRect(g2, 0, 0, getWidth(), getHeight(), radius, fill, stroke);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 普通文字标签。 */
    public static JLabel label(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(ui(size));
        l.setForeground(color);
        return l;
    }

    /** 加粗标签。 */
    public static JLabel bold(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(uiBold(size));
        l.setForeground(color);
        return l;
    }

    /** 深色输入框。 */
    public static JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.setFont(ui(13));
        f.setForeground(TEXT_BRIGHT);
        f.setCaretColor(TEXT_BRIGHT);
        f.setBackground(new Color(0x1D1B19));
        Border line = BorderFactory.createLineBorder(BORDER);
        Border pad = BorderFactory.createEmptyBorder(5, 7, 5, 7);
        f.setBorder(BorderFactory.createCompoundBorder(line, pad));
        if (placeholder != null) {
            f.setToolTipText(placeholder);
        }
        return f;
    }

    /** 深色只读文本区（日志、诊断输出）。 */
    public static JTextArea output() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(MONO);
        a.setForeground(TEXT);
        a.setBackground(new Color(0x101010));
        a.setCaretColor(TEXT_BRIGHT);
        a.setLineWrap(false);
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return a;
    }

    /** 把文本区包进滚动面板，并统一滚动条颜色。 */
    public static JScrollPane scroll(JComponent inner) {
        JScrollPane sp = new JScrollPane(inner);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(inner.getBackground());
        sp.setBackground(BG);
        sp.getVerticalScrollBar().setBackground(PANEL);
        sp.getHorizontalScrollBar().setBackground(PANEL);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** 顶部标题栏：深色底 + 绿色下边线，像 lichess 的页头。 */
    public static JPanel header(String title) {
        JPanel wrap = new JPanel(new java.awt.BorderLayout());
        wrap.setBackground(PANEL);
        wrap.setOpaque(true);

        JLabel t = bold(title, 17, TEXT_BRIGHT);
        t.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        wrap.add(t, java.awt.BorderLayout.CENTER);

        JPanel line = new JPanel();
        line.setBackground(GREEN);
        line.setPreferredSize(new java.awt.Dimension(0, 2));
        wrap.add(line, java.awt.BorderLayout.SOUTH);
        return wrap;
    }

    /** 分组小标题。 */
    public static JLabel sectionLabel(String text) {
        JLabel l = label(text, 12, TEXT_DIM);
        l.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        return l;
    }

    // -------------------------------------------------------------- 自绘按钮

    /**
     * 扁平圆角按钮，替代 Swing 默认的金属渐变外观。
     *
     * Android 版是 GradientDrawable + StateListDrawable；桌面版手绘三种状态，
     * 逻辑一一对应（normal / hover / pressed）。
     */
    public static final class FlatButton extends JButton {
        private Color normal;
        private Color hover;
        private Color pressed;
        private final int radius;

        FlatButton(String text, Color normal, Color pressedColor, Color textColor, int radius) {
            super(text);
            this.normal = normal;
            this.pressed = pressedColor;
            this.hover = brighten(normal, 0.12f);
            this.radius = radius;
            setForeground(textColor);
            setFont(ui(13));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new java.awt.Insets(0, 0, 0, 0));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = quality(g);
            Color base = normal;
            if (!isEnabled()) {
                base = new Color(0x33, 0x30, 0x2D);
            } else if (getModel().isPressed()) {
                base = pressed;
            } else if (getModel().isRollover()) {
                base = hover;
            }
            roundRect(g2, 0, 0, getWidth(), getHeight(), radius, base, null);
            g2.dispose();
            super.paintComponent(g);
        }

        /** 手动换配色（按钮启用/禁用状态之外的颜色变化，比如"停止寻找"）。 */
        public void recolor(Color n, Color p, Color textColor) {
            this.normal = n;
            this.pressed = p;
            this.hover = brighten(n, 0.12f);
            setForeground(textColor);
            repaint();
        }
    }

    private static Color brighten(Color c, float amount) {
        int r = Math.min(255, (int) (c.getRed() + 255 * amount));
        int g = Math.min(255, (int) (c.getGreen() + 255 * amount));
        int b = Math.min(255, (int) (c.getBlue() + 255 * amount));
        return new Color(r, g, b);
    }

    /** 主按钮：绿底白字。 */
    public static JButton primaryButton(String text) {
        return new FlatButton(text, GREEN, GREEN_PRESSED, TEXT_BRIGHT, 6);
    }

    /** 次按钮：深灰底浅字。 */
    public static JButton secondaryButton(String text) {
        return new FlatButton(text, PANEL_ALT, BORDER, TEXT, 6);
    }

    /** 危险按钮：暗红底。 */
    public static JButton dangerButton(String text) {
        return new FlatButton(text, RED, RED_PRESSED, TEXT_BRIGHT, 6);
    }

    /** 小号按钮（棋盘下方那一排）。 */
    public static JButton smallButton(String text) {
        JButton b = new FlatButton(text, PANEL_ALT, BORDER, TEXT, 4);
        b.setFont(ui(12));
        b.setPreferredSize(new java.awt.Dimension(0, 28));
        return b;
    }

    /** 小号主按钮。 */
    public static JButton smallPrimary(String text) {
        JButton b = new FlatButton(text, GREEN, GREEN_PRESSED, TEXT_BRIGHT, 4);
        b.setFont(ui(12));
        b.setPreferredSize(new java.awt.Dimension(0, 28));
        return b;
    }

    /** 列表行按钮：左对齐，看起来像可点的行。 */
    public static JButton rowButton(String text) {
        JButton b = new FlatButton(text, PANEL, PANEL_ALT, TEXT, 4);
        b.setHorizontalAlignment(JButton.LEFT);
        b.setFont(ui(13));
        return b;
    }

    /** 让按钮在鼠标移入时有点反馈（FlatButton 已经处理，这里只给需要的人用）。 */
    public static void onClick(JComponent c, final Runnable run) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                run.run();
            }
        });
    }
}
