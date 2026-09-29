package org.lichessold.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lichessold.json.Json;
import org.lichessold.log.Version;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 设置页：令牌、测试连接、AI 等级、调试模式、清空令牌。
 *
 * 令牌只存本机 SharedPreferences，只用于发 Authorization 头，
 * 日志里会被脱敏，不上传、不外发。
 */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private TextView statusText;
    private EditText tokenInput;
    private TextView accountText;
    private Button levelButton;
    private boolean testing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        LinearLayout root = Ui.column(this);
        root.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

        root.addView(Theme.header(this, "设置"),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.text(this, "Lichess API 令牌（只存在本机）", Ui.TEXT_SMALL, Ui.FG_DIM));

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setHint("lip_xxxxxxxx");
        tokenInput.setTextSize(13);
        String existing = prefs.getToken();
        if (existing.length() > 0) {
            tokenInput.setText(existing);
        }
        root.addView(tokenInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = Ui.smallButton(this, "保存令牌");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = tokenInput.getText().toString().trim();
                prefs.setToken(t);
                Net.reloadToken(SettingsActivity.this);
                // 只记长度，绝不记内容
                Log.i("Settings", "令牌已保存，长度 " + t.length());
                Ui.toastShort(SettingsActivity.this, t.length() == 0
                        ? "已清空令牌" : "已保存，正在验证…");
                // 保存后立刻验证一次 —— 用户名必须落到本地，
                // 对局界面要靠它判断"我执哪一边"。以前要用户自己再点一次
                // 「测试连接」，结果主菜单一直显示"未登录"。
                if (t.length() > 0) {
                    testConnection();
                } else {
                    refreshAccountText();
                }
            }
        });

        Button test = Ui.smallButton(this, "测试连接");
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testConnection();
            }
        });

        root.addView(Ui.buttonRow(this, save, test));

        accountText = Ui.text(this, "", Ui.TEXT_SMALL, Ui.ACCENT);
        accountText.setGravity(Gravity.CENTER);
        root.addView(accountText);

        root.addView(Ui.text(this, "离线 AI 强度", Ui.TEXT_SMALL, Ui.FG_DIM));
        levelButton = Ui.smallButton(this, "等级 " + prefs.getAiLevel() + " / 8");
        levelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickLevel();
            }
        });
        root.addView(levelButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final CheckBox debug = new CheckBox(this);
        debug.setText("调试模式（记录更多日志）");
        debug.setTextSize(13);
        debug.setTextColor(Ui.FG);
        debug.setChecked(prefs.isDebugMode());
        debug.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.setDebugMode(debug.isChecked());
                Log.setDebugEnabled(debug.isChecked());
                Log.i("Settings", "调试模式 = " + debug.isChecked());
            }
        });
        root.addView(debug);

        Button clear = Ui.smallButton(this, "清空令牌");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ui.confirm(SettingsActivity.this, "清空令牌", "确定要删除本机保存的令牌吗？",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                prefs.clearToken();
                                tokenInput.setText("");
                                accountText.setText("");
                                Net.reloadToken(SettingsActivity.this);
                                Log.i("Settings", "令牌已清空");
                                Ui.toastShort(SettingsActivity.this, "已清空");
                            }
                        });
            }
        });

        Button diag = Ui.smallButton(this, "网络诊断");
        diag.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, DiagActivity.class));
            }
        });

        Button back = Ui.smallButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        root.addView(Ui.buttonRow(this, clear, diag));
        root.addView(Ui.buttonRow(this, back));

        statusText = Ui.text(this, "版本 " + Version.full(this), Ui.TEXT_SMALL, Ui.FG_DIM);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        setContentView(Ui.scroll(this, root));

        refreshAccountText();
        if (prefs.hasToken()) {
            testConnection();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accountText != null && !testing) {
            refreshAccountText();
        }
    }

    /** 先把本地已知的状态显示出来，不要留空白。 */
    private void refreshAccountText() {
        if (accountText == null) {
            return;
        }
        String who = prefs.getUsername();
        if (who.length() > 0) {
            accountText.setTextColor(Ui.ACCENT);
            accountText.setText("已登录：" + who);
        } else if (prefs.hasToken()) {
            accountText.setTextColor(Ui.FG_DIM);
            accountText.setText("已设置令牌，用户名未知");
        } else {
            accountText.setTextColor(Ui.FG_DIM);
            accountText.setText("未设置令牌");
        }
    }

    private void pickLevel() {
        final String[] labels = new String[8];
        for (int i = 0; i < 8; i++) {
            labels[i] = "等级 " + (i + 1) + "（思考约 "
                    + (org.lichessold.chess.Ai.timeBudgetForLevel(i + 1) / 1000.0) + " 秒）";
        }
        Ui.choose(this, "离线 AI 强度", labels, prefs.getAiLevel() - 1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.setAiLevel(which + 1);
                        d.dismiss();
                        // recreate() 是 API 11+，这里手动刷新按钮文字
                        if (levelButton != null) {
                            levelButton.setText("等级 " + prefs.getAiLevel() + " / 8");
                        }
                        Ui.toastShort(SettingsActivity.this,
                                "AI 强度已设为 " + prefs.getAiLevel());
                    }
                });
    }

    private void testConnection() {
        testing = true;
        accountText.setText("正在连接 lichess.org…");
        final String token = prefs.getToken();
        Async.run("testConn", new Async.Job<String>() {
            public String run() throws Throwable {
                LichessApi api = Net.api(SettingsActivity.this);
                api.setToken(token);
                Json j = api.account();
                return j.str("username", "?");
            }
        }, new Async.Done<String>() {
            public void done(String name, Throwable error) {
                testing = false;
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    accountText.setTextColor(Ui.WARN);
                    accountText.setText("连接失败：" + error.getMessage());
                    Log.w("Settings", "测试连接失败: " + error);
                } else {
                    accountText.setTextColor(Ui.ACCENT);
                    accountText.setText("已登录：" + name);
                    prefs.setUsername(name);
                    Log.i("Settings", "测试连接成功，用户名 " + name);
                }
            }
        });
    }
}
