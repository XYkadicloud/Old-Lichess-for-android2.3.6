package org.lichessold.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.util.Log;

/**
 * 锦标赛列表 —— 对应 lichess.org/tournament 那个页面。
 *
 * 数据源 {@code GET /api/tournament}，免登录。返回三段：
 *   created[]  即将开始（有待开始的倒计时）
 *   started[]  正在进行
 *   finished[] 刚结束
 *
 * 每条都能点进去看排行榜（TournamentActivity）。
 *
 * 说明：本机只做「看」，不做「报名」。
 * 报名要 POST /api/tournament/{id}/join，需要 OAuth 的 tournament:write 权限，
 * 现在这种手填个人令牌拿不到那个 scope。
 */
public class TournamentsActivity extends Activity {

    /** 列表里每段最多显示几条 —— 320x240 的屏幕，多了要滚很久。 */
    private static final int PER_SECTION = 12;

    private LinearLayout list;
    private TextView statusText;
    private Button refreshButton;
    private LichessApi api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.fullscreen(this);
        Log.i("UI", "TournamentsActivity onCreate");

        ScrollView scroll = Ui.scroll(this, null);
        LinearLayout root = Ui.column(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Theme.header(this, "锦标赛"));

        statusText = Ui.text(this, "正在读取锦标赛日程…", Ui.TEXT_SMALL, Ui.FG_DIM);
        statusText.setPadding(Ui.dp(this, 8), Ui.dp(this, 4),
                Ui.dp(this, 8), Ui.dp(this, 4));
        root.addView(statusText);

        list = Ui.column(this);
        root.addView(list);

        refreshButton = Ui.smallButton(this, "刷新");
        refreshButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });
        Button back = Ui.smallButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(Ui.buttonRow(this, refreshButton, back));

        try {
            api = Net.api(this);
        } catch (NetException e) {
            statusText.setText("网络层初始化失败");
            Ui.toast(this, e.getMessage());
            return;
        }

        load();
    }

    // ------------------------------------------------------------------ 拉取

    private void load() {
        statusText.setText("正在读取锦标赛日程…");
        list.removeAllViews();
        refreshButton.setEnabled(false);

        Async.run("tournaments", new Async.Job<Json>() {
            public Json run() throws Throwable {
                return api.tournaments();
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                refreshButton.setEnabled(true);
                if (error != null) {
                    Log.w("UI", "拿锦标赛列表失败: " + error.getMessage());
                    statusText.setText("读取失败：" + shortMsg(error));
                    return;
                }
                try {
                    render(j);
                } catch (Throwable t) {
                    Log.e("UI", "渲染锦标赛列表失败", t);
                    statusText.setText("解析失败：" + t);
                }
            }
        });
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Json root) {
        list.removeAllViews();
        int total = 0;

        total += section(root.arr("started"), "正在进行", true);
        total += section(root.arr("created"), "即将开始", false);
        total += section(root.arr("finished"), "刚结束", false);

        if (total == 0) {
            statusText.setText("现在没有锦标赛");
        } else {
            statusText.setText("共 " + total + " 场（点进去看排行榜）");
        }
    }

    /**
     * 渲染一段。
     * @param live 是否正在进行 —— 正在进行的排前面，并标出人数
     * @return 实际渲染的条数
     */
    private int section(Json arr, String title, boolean live) {
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return 0;
        }
        int n = Math.min(arr.size(), PER_SECTION);

        TextView label = Theme.sectionLabel(this, title + "（" + arr.size() + "）");
        list.addView(label);

        for (int i = 0; i < n; i++) {
            Json t = arr.at(i);
            if (t == null || !t.isObject()) {
                continue;
            }
            list.addView(tournamentRow(t, live));
        }
        return n;
    }

    private LinearLayout tournamentRow(final Json t, boolean live) {
        final String id = t.str("id", "");
        String name = t.str("fullName", "");
        if (name.length() == 0) {
            name = t.str("name", id);
        }

        LinearLayout box = Theme.row(this, name, subtitle(t, live));
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (id.length() == 0) {
                    Ui.toastShort(TournamentsActivity.this, "这场没有 ID");
                    return;
                }
                try {
                    Intent it = new Intent(TournamentsActivity.this,
                            TournamentActivity.class);
                    it.putExtra(TournamentActivity.EXTRA_ID, id);
                    startActivity(it);
                } catch (Throwable e) {
                    Log.e("UI", "打开锦标赛详情失败", e);
                    Ui.toast(TournamentsActivity.this, "打开失败: " + e);
                }
            }
        });
        return box;
    }

    /** 副标题：赛制 + 时长 + 人数 + 状态。 */
    private static String subtitle(Json t, boolean live) {
        StringBuilder sb = new StringBuilder();

        Json clock = t.obj("clock");
        double limit = clock == null ? 0 : clock.d("limit", 0);
        int inc = clock == null ? 0 : clock.i("increment", 0);
        if (limit > 0 || inc > 0) {
            sb.append(formatClock(limit, inc)).append(" · ");
        }

        int nb = t.i("nbPlayers", 0);
        if (nb > 0) {
            sb.append(nb).append(" 人 · ");
        }

        sb.append(t.b("rated", false) ? "排位" : "休闲");

        if (live) {
            int secs = t.i("secondsToFinish", -1);
            if (secs > 0) {
                sb.append(" · 还剩 ").append(formatDuration(secs));
            }
        } else {
            int secs = t.i("secondsToStart", -1);
            if (secs > 0) {
                sb.append(" · ").append(formatDuration(secs)).append("后开始");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 小工具

    /** 60 / 0 → "1+0"。limit 单位是**秒**。 */
    static String formatClock(double limitSec, int incSec) {
        double min = limitSec / 60.0;
        String l;
        if (min == Math.floor(min)) {
            l = String.valueOf((long) min);
        } else {
            l = String.valueOf(min);
        }
        return l + "+" + incSec;
    }

    /** 秒 → "1小时20分" / "13分" / "45秒" */
    static String formatDuration(int secs) {
        if (secs <= 0) {
            return "0秒";
        }
        if (secs >= 3600) {
            int h = secs / 3600;
            int m = (secs % 3600) / 60;
            return m > 0 ? h + "小时" + m + "分" : h + "小时";
        }
        if (secs >= 60) {
            return (secs / 60) + "分";
        }
        return secs + "秒";
    }
}
