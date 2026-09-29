package org.lichessold.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lichessold.net.LichessApi;
import org.lichessold.net.LichessUser;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 个人主页：等级分（排位分）、战绩、在线时长。
 *
 * 不传 EXTRA_USERNAME 就是看自己（走 /api/account → /api/user/{name}）；
 * 传了就是看别人（走 /api/user/{name}，这个端点是公开的）。
 * 在对局界面点对手名字就能跳过来。
 *
 * 排位分（等级分）说明：
 *   lichess 每个分项（子弹/闪电/快速/经典/通信/各种变体/谜题）各自独立算分，
 *   用 Glicko-2。rd 越大说明这个分数越不准（打得少），界面上标一个"?"。
 *   打排位赛会改这些数字，休闲赛不会。
 */
public class ProfileActivity extends Activity {

    public static final String EXTRA_USERNAME = "username";

    private LichessApi api;
    private String targetName = "";

    private LinearLayout content;
    private TextView statusText;
    private TextView nameText;
    private TextView subText;
    private Button challengeButton;

    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null) {
            String n = intent.getStringExtra(EXTRA_USERNAME);
            if (n != null) {
                targetName = n.trim();
            }
        }

        LinearLayout root = Ui.column(this);
        root.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        root.addView(Theme.header(this, "主页"),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // 名字块
        LinearLayout nameBox = Ui.column(this);
        nameBox.setBackgroundDrawable(Theme.panel(this));
        nameBox.setPadding(Theme.dp(this, 6), Theme.dp(this, 5),
                Theme.dp(this, 6), Theme.dp(this, 5));
        nameText = Ui.text(this, "加载中…", 20, Theme.TEXT_BRIGHT);
        nameText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameBox.addView(nameText);
        subText = Ui.text(this, "", 12, Theme.TEXT_DIM);
        nameBox.addView(subText);
        root.addView(nameBox, Ui.full());

        statusText = Ui.centered(this, "", 13, Theme.TEXT_DIM);
        root.addView(statusText);

        content = Ui.column(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));

        Button refresh = Ui.smallButton(this, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });

        challengeButton = Ui.smallButton(this, "挑战 TA");
        challengeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ChallengeFlow.challengeUser(ProfileActivity.this, targetName);
            }
        });

        Button back = Ui.smallButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        root.addView(Ui.buttonRow(this, refresh, challengeButton, back));

        setContentView(root);

        try {
            api = Net.api(this);
        } catch (NetException e) {
            statusText.setText("网络层初始化失败");
            Ui.toast(this, e.getMessage());
            return;
        }

        load();
    }

    // -------------------------------------------------------------- 加载

    private void load() {
        if (loading) {
            return;
        }
        boolean self = targetName.length() == 0;
        if (!self && !Prefs.get(this).hasToken()) {
            statusText.setText("查看别人主页也要令牌（lichess 的 /api/user 需要认证）");
        }
        loading = true;
        statusText.setText("正在加载" + (self ? "我的" : targetName + " 的") + "资料…");

        Async.run("profile", new Async.Job<LichessUser>() {
            public LichessUser run() throws Throwable {
                if (targetName.length() == 0) {
                    LichessUser u = api.myProfile();
                    // 顺手把用户名补进本地设置 —— 对局界面靠它判断执哪一边
                    if (u != null && u.username.length() > 0) {
                        Prefs.get(ProfileActivity.this).setUsername(u.username);
                    }
                    return u;
                }
                return api.userProfile(targetName);
            }
        }, new Async.Done<LichessUser>() {
            public void done(LichessUser u, Throwable error) {
                loading = false;
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    statusText.setText("加载失败");
                    renderError(error);
                    return;
                }
                statusText.setText("");
                render(u);
            }
        });
    }

    private void renderError(Throwable error) {
        content.removeAllViews();
        nameText.setText(targetName.length() == 0 ? "我的主页" : targetName);
        subText.setText("");
        TextView t = Ui.text(this, "加载失败：\n" + error.getMessage(), 13, Ui.WARN);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 4));
        content.addView(t);
    }

    // -------------------------------------------------------------- 渲染

    private void render(LichessUser u) {
        if (u == null) {
            renderError(new IllegalStateException("服务端返回空资料"));
            return;
        }
        boolean self = targetName.length() == 0
                || u.username.equalsIgnoreCase(Prefs.get(this).getUsername());

        String title = u.title.length() > 0 ? u.title + " " : "";
        nameText.setText(title + u.username);
        if (self) {
            nameText.append("  （你）");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("注册 ").append(LichessUser.formatDate(u.createdAt));
        if (u.patron) {
            sb.append("  · 赞助者");
        }
        if (u.verified) {
            sb.append("  · 已认证");
        }
        if (u.streamer) {
            sb.append("  · 主播");
        }
        subText.setText(sb.toString());

        // 看别人的主页才能挑战；看自己的没必要
        challengeButton.setVisibility(self ? View.GONE : View.VISIBLE);

        content.removeAllViews();

        // ---- 等级分（排位分）
        content.addView(Theme.sectionLabel(this, "等级分（排位分）"));
        int shown = 0;
        for (int i = 0; i < LichessUser.PROFILE_ORDER.length; i++) {
            String key = LichessUser.PROFILE_ORDER[i];
            LichessUser.Perf p = u.perf(key);
            if (p == null || !p.hasRating()) {
                continue;
            }
            addKv(LichessUser.displayName(key),
                    p.rating + (p.isProvisional() ? "?" : ""),
                    p.games + " 局",
                    p.isProvisional() ? Theme.TEXT_DIM : Theme.GREEN_LIGHT);
            shown++;
        }
        if (shown == 0) {
            addNote("还没有下过棋，没有等级分。");
        } else {
            addNote("带 ? 的分数是临时分（局数少，rd 高，还不准）");
        }

        // ---- 战绩
        content.addView(Theme.sectionLabel(this, "战绩"));
        addKv("总对局", String.valueOf(u.countAll), "其中排位 " + u.countRated, Theme.TEXT_BRIGHT);
        addKv("胜 / 负 / 和",
                u.countWin + " / " + u.countLoss + " / " + u.countDraw,
                "", Theme.TEXT_BRIGHT);
        int wr = u.winRate();
        addKv("胜率", wr < 0 ? "—" : wr + "%", "", Theme.TEXT_BRIGHT);
        if (u.countPlaying > 0) {
            addKv("进行中", String.valueOf(u.countPlaying), "局", Theme.GREEN_LIGHT);
        }

        // ---- 其他
        content.addView(Theme.sectionLabel(this, "其他"));
        addKv("在线时长", LichessUser.formatPlayTime(u.playTimeTotal),
                "观战 " + LichessUser.formatPlayTime(u.playTimeTv), Theme.TEXT);
        if (u.seenAt > 0) {
            addKv("最后在线", LichessUser.formatDate(u.seenAt), "", Theme.TEXT);
        }
        if (u.country.length() > 0) {
            addKv("国家/地区", u.country, "", Theme.TEXT);
        }
        if (u.realName.length() > 0) {
            addKv("真名", u.realName, "", Theme.TEXT);
        }
        if (u.bio.length() > 0) {
            TextView bio = Ui.text(this, u.bio, 12, Theme.TEXT);
            bio.setBackgroundDrawable(Theme.panelAlt(this));
            bio.setPadding(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
            content.addView(bio, Ui.fullSpaced(this));
        }
        if (u.url.length() > 0) {
            addNote(u.url);
        }
    }

    /** 一行 "左边名称 / 右边数值"，右侧还可以带一小段灰字说明。 */
    private void addKv(String key, String value, String note, int valueColor) {
        LinearLayout row = Ui.row(this);
        row.setBackgroundDrawable(Theme.shape(Theme.PANEL, 0, Theme.dp(this, 3)));
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView k = Ui.text(this, key, 13, Theme.TEXT_DIM);
        row.addView(k, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (note != null && note.length() > 0) {
            TextView n = Ui.text(this, note, 11, Theme.TEXT_DIM);
            n.setPadding(0, 0, Ui.dp(this, 5), 0);
            row.addView(n);
        }

        TextView v = Ui.text(this, value, 15, valueColor);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.RIGHT);
        row.addView(v);

        LinearLayout.LayoutParams p = Ui.full();
        p.topMargin = Ui.dp(this, 2);
        content.addView(row, p);
    }

    private void addNote(String text) {
        TextView t = Ui.text(this, text, 11, Theme.TEXT_DIM);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        content.addView(t);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从挑战流程回来时用户名可能刚被补上，重刷一下名字块
        if (nameText != null && targetName.length() == 0) {
            String who = Prefs.get(this).getUsername();
            if (who.length() > 0 && nameText.getText().toString().indexOf(who) < 0) {
                load();
            }
        }
    }

    /** 供外部打开某个玩家的主页。 */
    public static Intent intentFor(Activity from, String username) {
        Intent i = new Intent(from, ProfileActivity.class);
        if (username != null && username.length() > 0) {
            i.putExtra(EXTRA_USERNAME, username);
        }
        return i;
    }
}
