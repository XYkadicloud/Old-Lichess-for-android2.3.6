package org.lichessold.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * lichess 视觉风格。
 *
 * 配色取自 lichess 官网的深色主题：
 *   背景 #161512，面板 #262421，主色（绿）#629924，正文 #bfbdb2
 * 按钮用 GradientDrawable + StateListDrawable 画圆角，不依赖任何图片资源。
 *
 * 为什么要统一到这里：Android 2.3 的默认按钮是灰色渐变圆角，和 lichess 的
 * 扁平深色风格差得很远。全部自己画一遍，既统一又只占几百字节。
 */
public final class Theme {

    // ---- 背景与面板
    public static final int BG = 0xFF161512;
    public static final int PANEL = 0xFF262421;
    public static final int PANEL_ALT = 0xFF2E2B28;
    public static final int BORDER = 0xFF3D3A37;

    // ---- 文字
    public static final int TEXT = 0xFFBFBDB2;
    public static final int TEXT_BRIGHT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFF8A877E;

    // ---- 主色
    public static final int GREEN = 0xFF629924;
    public static final int GREEN_LIGHT = 0xFF9CCB3B;
    public static final int GREEN_PRESSED = 0xFF4E7A1C;
    public static final int RED = 0xFFA03030;
    public static final int RED_PRESSED = 0xFF7E2525;
    public static final int BLUE = 0xFF3692E7;

    // ---- 棋盘
    public static final int BOARD_LIGHT = 0xFFF0D9B5;
    public static final int BOARD_DARK = 0xFFB58863;
    public static final int BOARD_BORDER = 0xFF2A2724;
    public static final int HILITE_LAST = 0x669BC700;    // 最近一步：黄绿
    public static final int HILITE_SEL = 0x8014551E;     // 选中：深绿
    public static final int HILITE_TARGET = 0x8014551E;  // 可落子
    public static final int HILITE_CHECK = 0x99CC3333;   // 被将军
    public static final int HILITE_HINT = 0x663692E7;    // 引擎建议

    private Theme() {
    }

    // ------------------------------------------------------------ 尺寸

    public static int dp(Context c, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                c.getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ 形状

    /** 圆角矩形填充。 */
    public static GradientDrawable shape(int fill, int stroke, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (stroke != 0) {
            d.setStroke(Math.max(1, (int) (radiusPx / 4f)), stroke);
        }
        return d;
    }

    /** 带按下态的背景。 */
    public static Drawable pressable(Context c, int normal, int pressed, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        float r = dp(c, radiusDp);
        s.addState(new int[] { android.R.attr.state_pressed },
                shape(pressed, 0, r));
        s.addState(new int[] { android.R.attr.state_enabled },
                shape(normal, 0, r));
        s.addState(new int[] {}, shape(0x66_000000, 0, r));
        return s;
    }

    /** 面板背景（带描边）。 */
    public static Drawable panel(Context c) {
        return shape(PANEL, BORDER, dp(c, 4));
    }

    public static Drawable panelAlt(Context c) {
        return shape(PANEL_ALT, BORDER, dp(c, 4));
    }

    // ------------------------------------------------------------ 控件

    /** 主按钮：绿底白字。 */
    public static Button primaryButton(Context c, String text) {
        return styledButton(c, text, GREEN, GREEN_PRESSED, TEXT_BRIGHT, 18);
    }

    /** 次按钮：深灰底浅字。 */
    public static Button secondaryButton(Context c, String text) {
        return styledButton(c, text, PANEL_ALT, BORDER, TEXT, 17);
    }

    /** 危险按钮：暗红底。 */
    public static Button dangerButton(Context c, String text) {
        return styledButton(c, text, RED, RED_PRESSED, TEXT_BRIGHT, 17);
    }

    /** 小号次按钮（棋盘下方那一排）。 */
    public static Button smallButton(Context c, String text) {
        Button b = styledButton(c, text, PANEL_ALT, BORDER, TEXT, 14);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        return b;
    }

    /** 小号主按钮。 */
    public static Button smallPrimary(Context c, String text) {
        Button b = styledButton(c, text, GREEN, GREEN_PRESSED, TEXT_BRIGHT, 14);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        return b;
    }

    /**
     * 紧凑按钮：对局页底部那一行要塞 4 个，必须比 smallButton 再矮一截。
     *
     * 高度取 32dp（ldpi 下 24px）。Android 建议的触摸目标是 48dp，
     * 但 240×320 的屏幕上棋盘更值钱 —— 这是明确的取舍，不是疏忽。
     */
    public static Button compactButton(Context c, String text) {
        return compact(c, text, PANEL_ALT, BORDER, TEXT);
    }

    /** 紧凑主按钮。 */
    public static Button compactPrimary(Context c, String text) {
        return compact(c, text, GREEN, GREEN_PRESSED, TEXT_BRIGHT);
    }

    private static Button compact(Context c, String text, int normal, int pressed,
                                  int textColor) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(textColor);
        b.setBackgroundDrawable(pressable(c, normal, pressed, 3f));
        b.setPadding(dp(c, 2), 0, dp(c, 2), 0);
        b.setMinHeight(dp(c, 32));
        b.setMinimumHeight(dp(c, 32));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setShadowLayer(0, 0, 0, 0);
        return b;
    }

    /**
     * 隐藏系统状态栏，把整块屏幕让给棋盘。
     *
     * 必须在 setContentView 之前调用（否则窗口已经按带状态栏的高度布局完了）。
     */
    public static void fullscreen(Activity a) {
        a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private static Button styledButton(Context c, String text, int normal, int pressed,
                                       int textColor, float sp) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(textColor);
        b.setBackgroundDrawable(pressable(c, normal, pressed, 4f));
        b.setPadding(dp(c, 6), dp(c, 8), dp(c, 6), dp(c, 8));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setGravity(Gravity.CENTER);
        b.setShadowLayer(0, 0, 0, 0);
        return b;
    }

    /** 顶部标题栏：深色底 + 绿色下边线，像 lichess 的页头。 */
    public static LinearLayout header(Context c, String title) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundDrawable(shape(PANEL, 0, 0));
        bar.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT_BRIGHT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 绿色下边线
        LinearLayout wrapper = new LinearLayout(c);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(c);
        line.setBackgroundColor(GREEN);
        wrapper.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, dp(c, 2)));
        return wrapper;
    }

    /** 列表行：面板底色 + 右侧箭头，像 lichess 的设置项。 */
    public static LinearLayout row(Context c, String title, String subtitle) {
        return rowRef(c, title, subtitle).box;
    }

    /**
     * 可动态改文字的行。
     *
     * 为什么需要它：主菜单的账号行要在 onResume 里跟着设置页的状态变
     * （0.5.0 就栽在这里 —— 只建了一次，回来还是"未登录"）。
     * 把两个 TextView 交出去，比回头去 children 里摸出来靠谱。
     */
    public static final class RowRef {
        public final LinearLayout box;
        public final TextView title;
        public final TextView subtitle;

        RowRef(LinearLayout box, TextView title, TextView subtitle) {
            this.box = box;
            this.title = title;
            this.subtitle = subtitle;
        }

        public void set(String t, String s) {
            title.setText(t == null ? "" : t);
            if (subtitle == null) {
                return;
            }
            subtitle.setText(s == null ? "" : s);
            subtitle.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        }

        public void setClickable(View.OnClickListener l) {
            box.setClickable(true);
            box.setOnClickListener(l);
        }
    }

    public static RowRef rowRef(Context c, String title, String subtitle) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundDrawable(pressable(c, PANEL, PANEL_ALT, 3f));
        box.setPadding(dp(c, 10), dp(c, 9), dp(c, 8), dp(c, 9));
        box.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        texts.addView(t);

        TextView s = null;
        if (subtitle != null && subtitle.length() > 0) {
            s = new TextView(c);
            s.setText(subtitle);
            s.setTextColor(TEXT_DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            texts.addView(s);
        }
        box.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(c);
        arrow.setText("›");
        arrow.setTextColor(GREEN);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        arrow.setPadding(dp(c, 4), 0, dp(c, 2), 0);
        box.addView(arrow);

        return new RowRef(box, t, s);
    }

    /** 小标题（分组用）。 */
    public static TextView sectionLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(c, 6), dp(c, 8), dp(c, 4), dp(c, 3));
        return t;
    }

    /** 玩家条：名字 + 时钟胶囊。 */
    public static LinearLayout playerBar(Context c, boolean active) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundDrawable(shape(active ? PANEL_ALT : PANEL, 0, dp(c, 3)));
        bar.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
        return bar;
    }

    /** 时钟胶囊。 */
    public static TextView clockView(Context c, String text, boolean active) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(active ? 0xFF161512 : TEXT_BRIGHT);
        t.setBackgroundDrawable(shape(active ? GREEN_LIGHT : PANEL_ALT, 0, dp(c, 3)));
        t.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 状态条：左边一个颜色点，右边文字。 */
    public static LinearLayout statusBar(Context c, int dotColor, String text) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));

        View dot = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(dotColor);
        dot.setBackgroundDrawable(g);
        int s = dp(c, 7);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(s, s);
        dp2.rightMargin = dp(c, 6);
        bar.addView(dot, dp2);

        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bar.addView(t);
        return bar;
    }
}
