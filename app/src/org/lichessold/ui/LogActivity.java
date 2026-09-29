package org.lichessold.ui;

import java.io.File;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lichessold.log.Logging;
import org.lichessold.util.Log;

/**
 * 日志页。没有 adb 的情况下，这里是唯一能看到现场的地方。
 *
 *  - 每 2 秒自动刷新，显示最近 600 行
 *  - 「保存到存储卡」把队列刷干净并告诉用户文件路径
 *  - 「触发测试崩溃」用来验证崩溃报告链路是否还通
 */
public class LogActivity extends Activity {

    public static final String EXTRA_SHOW_CRASH = "show_crash";

    private static final long REFRESH_MS = 2000L;

    private TextView logText;
    private ScrollView scroll;
    private final Handler handler = new Handler();
    private String frozenText;
    private boolean autoRefresh = true;

    private final Runnable refresher = new Runnable() {
        public void run() {
            if (!autoRefresh || isFinishing()) {
                return;
            }
            render();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));

        String crash = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SHOW_CRASH);
        root.addView(Theme.header(this, crash != null ? "崩溃报告" : "运行日志"),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        logText = Ui.text(this, "", 11, 0xFFD5D5D5);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101010);
        scroll.addView(logText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));

        Button save = Ui.smallButton(this, "保存到存储卡");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onSave();
            }
        });

        Button clear = Ui.smallButton(this, "清空");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Logging.clear();
                Ui.toastShort(LogActivity.this, "日志已清空");
                Log.i("UI", "用户清空了日志");
                render();
            }
        });

        Button crashTest = Ui.smallButton(this, "测试崩溃");
        crashTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i("UI", "用户触发了测试崩溃");
                Logging.flush(1500L);
                throw new RuntimeException("测试崩溃：这条是故意抛的，用来验证崩溃报告链路");
            }
        });

        Button back = Ui.smallButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        root.addView(Ui.buttonRow(this, save, clear, crashTest, back));

        if (crash != null) {
            frozenText = crash;
            autoRefresh = false;
        } else {
            Log.i("UI", "LogActivity onCreate");
        }

        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoRefresh) {
            handler.postDelayed(refresher, REFRESH_MS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void render() {
        String text;
        if (frozenText != null) {
            text = frozenText;
        } else {
            text = Log.getRecentText();
            if (text.length() == 0) {
                text = "(暂无日志)";
            }
        }
        logText.setText(text);
        scroll.post(new Runnable() {
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void onSave() {
        Logging.flush(3000L);
        File f = Logging.logFile();
        if (f == null || !f.exists()) {
            Log.w("UI", "保存日志失败：文件不可用");
            Ui.toast(this, "无法写入存储卡，请确认手机插了 SD 卡");
            return;
        }
        String path = f.getAbsolutePath();
        Log.i("UI", "日志已保存到 " + path + "（" + f.length() + " 字节）");
        Ui.toast(this, "日志已保存到：\n" + path);
    }
}
