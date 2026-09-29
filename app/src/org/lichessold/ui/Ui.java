package org.lichessold.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 界面构件。所有颜色与形状都走 {@link Theme}，统一成 lichess 的深色风格。
 *
 * 为什么不用 XML 布局：屏幕只有 240×320，代码构建更容易精确控制尺寸，
 * 也避免 R.id 与布局文件之间的隐性耦合。
 */
public final class Ui {

    // 兼容旧调用点的别名，统一指向 Theme
    public static final int BG = Theme.BG;
    public static final int FG = Theme.TEXT;
    public static final int FG_DIM = Theme.TEXT_DIM;
    public static final int ACCENT = Theme.GREEN_LIGHT;
    public static final int WARN = 0xFFE57373;

    public static final int TEXT_SMALL = 13;
    public static final int TEXT_NORMAL = 15;
    public static final int TEXT_LARGE = 18;
    public static final int TEXT_TITLE = 22;

    private Ui() {
    }

    // ------------------------------------------------------------ 容器

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Theme.BG);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    /** 面板容器：深色底 + 描边 + 圆角。 */
    public static LinearLayout panel(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundDrawable(Theme.panel(c));
        l.setPadding(Theme.dp(c, 6), Theme.dp(c, 6), Theme.dp(c, 6), Theme.dp(c, 6));
        return l;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setBackgroundColor(Theme.BG);
        s.addView(child);
        return s;
    }

    // ------------------------------------------------------------ 文本

    public static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(Theme.dp(c, 3), Theme.dp(c, 2), Theme.dp(c, 3), Theme.dp(c, 2));
        return t;
    }

    public static TextView centered(Context c, String s, int sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = centered(c, s, TEXT_TITLE, Theme.TEXT_BRIGHT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(Theme.dp(c, 2), Theme.dp(c, 4), Theme.dp(c, 2), Theme.dp(c, 4));
        return t;
    }

    // ------------------------------------------------------------ 按钮

    public static Button button(Context c, String s, int sp) {
        if (sp >= TEXT_LARGE) {
            return Theme.primaryButton(c, s);
        }
        return Theme.smallButton(c, s);
    }

    public static Button menuButton(Context c, String s) {
        return Theme.primaryButton(c, s);
    }

    public static Button smallButton(Context c, String s) {
        return Theme.smallButton(c, s);
    }

    /** 横向等分的一行按钮。 */
    public static LinearLayout buttonRow(Context c, Button... buttons) {
        LinearLayout r = row(c);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            int m = Theme.dp(c, 2);
            p.leftMargin = i == 0 ? 0 : m;
            p.rightMargin = i == buttons.length - 1 ? 0 : m;
            buttons[i].setLayoutParams(p);
            r.addView(buttons[i]);
        }
        return r;
    }

    public static int dp(Context c, int dp) {
        return Theme.dp(c, dp);
    }

    // ------------------------------------------------------------ 交互

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    public static void toastShort(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    public static void confirm(Context c, String title, String msg,
                               DialogInterface.OnClickListener onYes) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", onYes)
                .setNegativeButton("取消", null)
                .show();
    }

    public static void choose(Context c, String title, final String[] items,
                              int checked, DialogInterface.OnClickListener onPick) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, onPick)
                .setNegativeButton("取消", null)
                .show();
    }

    /** 让一个 View 撑满宽度（纵向排列时用）。 */
    public static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 带顶部间距的撑满参数。 */
    public static LinearLayout.LayoutParams fullSpaced(Context c) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = Theme.dp(c, 3);
        return p;
    }
}
