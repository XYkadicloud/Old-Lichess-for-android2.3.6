package org.lichessold.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lichessold.json.Json;
import org.lichessold.log.CrashHandler;
import org.lichessold.log.Version;
import org.lichessold.net.LichessApi;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 主菜单。布局照 lichess 官网的深色主题：绿色标题栏 + 分组列表项。
 */
public class MainActivity extends Activity {

    /** 账号行要能改文字，所以留引用。 */
    private Theme.RowRef accountRow;
    private boolean fetchingUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("UI", "MainActivity onCreate");

        LinearLayout root = Ui.column(this);
        root.addView(Theme.header(this, "lichess"),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // 账号状态。文字在 onResume 里刷新 —— 0.5.0 只在这里建一次，
        // 从设置页填完令牌回来还显示"未登录"，就是这么来的。
        accountRow = Theme.rowRef(this, "未登录", "点这里填 API 令牌");
        accountRow.setClickable(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        LinearLayout.LayoutParams ap = Ui.full();
        ap.topMargin = Theme.dp(this, 6);
        ap.leftMargin = Theme.dp(this, 5);
        ap.rightMargin = Theme.dp(this, 5);
        root.addView(accountRow.box, ap);

        // ---- 下棋
        root.addView(Theme.sectionLabel(this, "下棋"), sideMargin());
        addRow(root, "在线对局", "挑战电脑 · 找对手 · 接受挑战", GamesActivity.class);
        addRow(root, "离线人机对战", "手机内置引擎，8 个强度等级", AiGameActivity.class);
        addRow(root, "离线双人对战", "同一台手机两人轮流走", LocalGameActivity.class);

        // ---- 我的
        root.addView(Theme.sectionLabel(this, "我的"), sideMargin());
        addRow(root, "我的主页与排位分", "各分项等级分 · 战绩 · 在线时长", ProfileActivity.class);
        addRow(root, "最近战绩", "近几天的胜负与等级分涨跌", ActivityActivity.class);
        addRow(root, "棋谱", "最近一局 · 可存到存储卡", PgnActivity.class);

        // ---- 学习
        root.addView(Theme.sectionLabel(this, "学习与观战"), sideMargin());
        addRow(root, "谜题训练", "每日一题 · 19 种主题", PuzzleActivity.class);
        addRow(root, "观战", "Lichess 焦点对局直播", TvActivity.class);
        addRow(root, "锦标赛", "正在进行 · 即将开始 · 排行榜", TournamentsActivity.class);

        // ---- 系统
        root.addView(Theme.sectionLabel(this, "系统"), sideMargin());
        addRow(root, "设置", "令牌 · AI 强度 · 调试模式", SettingsActivity.class);
        addRow(root, "网络诊断", "逐步骤检查连接", DiagActivity.class);
        addRow(root, "运行日志", "查看 · 保存 · 测试崩溃", LogActivity.class);

        TextView ver = Ui.centered(this, "版本 " + Version.full(this), 12, Theme.TEXT_DIM);
        ver.setPadding(0, Theme.dp(this, 10), 0, Theme.dp(this, 8));
        root.addView(ver);

        setContentView(Ui.scroll(this, root));

        checkCrashReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs p = Prefs.get(this);
        Log.setDebugEnabled(p.isDebugMode());
        refreshAccountRow();
        fetchUsernameIfMissing();
    }

    /** 按本地存的状态刷新账号行。 */
    private void refreshAccountRow() {
        if (accountRow == null) {
            return;
        }
        Prefs p = Prefs.get(this);
        String who = p.getUsername();
        if (who.length() > 0) {
            accountRow.set(who, "已连接 lichess.org · 点这里改设置");
        } else if (p.hasToken()) {
            accountRow.set("已设置令牌", "正在确认账号…（点这里去设置）");
        } else {
            accountRow.set("未登录", "点这里填 API 令牌");
        }
    }

    /**
     * 有令牌但还没拿到用户名时，后台补一次 /api/account。
     *
     * 这一步很关键：对局界面要靠用户名判断"我执哪一边"。
     * 用户名缺了就会默认按白方处理 —— 执黑时棋盘方向反、而且根本走不了子。
     * 所以只要令牌在，就一定要把用户名拿到并存下来。
     */
    private void fetchUsernameIfMissing() {
        final Prefs p = Prefs.get(this);
        if (fetchingUsername || !p.hasToken() || p.getUsername().length() > 0) {
            return;
        }
        fetchingUsername = true;
        Async.run("whoami", new Async.Job<String>() {
            public String run() throws Throwable {
                LichessApi api = Net.api(MainActivity.this);
                return api.accountUsername();
            }
        }, new Async.Done<String>() {
            public void done(String name, Throwable error) {
                fetchingUsername = false;
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    Log.w("UI", "自动获取用户名失败: " + error.getMessage());
                    if (accountRow != null) {
                        accountRow.set("令牌可能无效", "点这里去设置里测试连接");
                    }
                    return;
                }
                if (name != null && name.length() > 0) {
                    p.setUsername(name);
                    Log.i("UI", "自动获取到用户名 " + name);
                }
                refreshAccountRow();
            }
        });
    }

    private LinearLayout.LayoutParams sideMargin() {
        LinearLayout.LayoutParams p = Ui.full();
        p.leftMargin = Theme.dp(this, 5);
        p.rightMargin = Theme.dp(this, 5);
        return p;
    }

    private void addRow(LinearLayout parent, String title, String subtitle,
                        final Class<?> target) {
        LinearLayout row = Theme.row(this, title, subtitle);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, target));
                } catch (Throwable t) {
                    Log.e("UI", "打开 " + target.getSimpleName() + " 失败", t);
                    Ui.toast(MainActivity.this, "打开失败: " + t);
                }
            }
        });
        LinearLayout.LayoutParams p = sideMargin();
        p.topMargin = Theme.dp(this, 2);
        parent.addView(row, p);
    }

    private void checkCrashReport() {
        final String report = CrashHandler.takePending(this);
        if (report == null) {
            return;
        }
        Log.w("UI", "发现上次崩溃报告，长度 " + report.length());

        String shown = report;
        if (shown.length() > 2200) {
            shown = shown.substring(0, 2200)
                    + "\n\n...(过长已截断，完整报告见存储卡 LichessOld/crash.txt)";
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("上次崩溃了")
                    .setMessage(shown)
                    .setPositiveButton("查看完整日志", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            Intent i = new Intent(MainActivity.this, LogActivity.class);
                            i.putExtra(LogActivity.EXTRA_SHOW_CRASH, report);
                            startActivity(i);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            Log.e("UI", "崩溃弹窗显示失败", t);
        }
    }
}
