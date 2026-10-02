package org.lichessold.ui;

import android.app.Activity;
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
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 最近战绩 —— 对应 lichess 个人主页里的 activity 那一块。
 *
 * 数据源 {@code GET /api/user/{name}/activity}，免登录。
 * 返回的是一个**数组**（不是对象），每天一项，实测结构：
 * <pre>
 * [
 *   { "interval": {"start":1790812800000, "end":1790899200000},
 *     "games": { "blitz": {"win":13,"loss":43,"draw":3,
 *                          "rp":{"before":1227,"after":1277}} },
 *     "tournaments": { "nb":7,
 *                      "best":[{"tournament":{"id":"..","name":".."},
 *                               "nbGames":11,"score":7,
 *                               "rank":224,"rankPercent":28}] } }
 * ]
 * </pre>
 *
 * 这里把它压成一张紧凑的表：每天一行，显示胜负和分项等级分涨跌。
 * 320x240 的屏幕上，一天一行是能看清的上限。
 */
public class ActivityActivity extends Activity {

    /** 最多显示几天 —— 接口默认给 7 天（实测 len=7）。 */
    private static final int MAX_DAYS = 14;

    private LinearLayout list;
    private TextView statusText;
    private TextView summaryText;
    private Button refreshButton;
    private LichessApi api;

    private String targetName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.fullscreen(this);
        Log.i("UI", "ActivityActivity onCreate");

        // 没传名字就用自己的
        targetName = getIntent() == null ? "" : getIntent().getStringExtra("username");
        if (targetName == null) {
            targetName = "";
        }
        if (targetName.length() == 0) {
            targetName = Prefs.get(this).getUsername();
        }

        ScrollView scroll = Ui.scroll(this, null);
        LinearLayout root = Ui.column(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Theme.header(this, "最近战绩"));

        summaryText = Ui.text(this, targetName.length() > 0 ? targetName : "（未知用户）",
                Ui.TEXT_NORMAL, Ui.FG);
        summaryText.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), 0);
        root.addView(summaryText);

        statusText = Ui.text(this, "正在读取…", Ui.TEXT_SMALL, Ui.FG_DIM);
        statusText.setPadding(Ui.dp(this, 8), Ui.dp(this, 2),
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

        if (targetName.length() == 0) {
            statusText.setText("还不知道用户名 —— 先去设置里填令牌，或从「我的主页」进来");
            return;
        }

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
        statusText.setText("正在读取 " + targetName + " 的战绩…");
        list.removeAllViews();
        refreshButton.setEnabled(false);

        Async.run("activity", new Async.Job<Json>() {
            public Json run() throws Throwable {
                return api.userActivity(targetName);
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                refreshButton.setEnabled(true);
                if (error != null) {
                    Log.w("UI", "拿战绩失败: " + error.getMessage());
                    statusText.setText("读取失败：" + msg(error));
                    return;
                }
                try {
                    render(j);
                } catch (Throwable t) {
                    Log.e("UI", "渲染战绩失败", t);
                    statusText.setText("解析失败：" + t);
                }
            }
        });
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Json root) {
        list.removeAllViews();

        if (root == null || !root.isArray() || root.size() == 0) {
            statusText.setText("最近没有对局记录");
            return;
        }

        int days = Math.min(root.size(), MAX_DAYS);
        int totWin = 0;
        int totLoss = 0;
        int totDraw = 0;
        int totGames = 0;

        // 先统计总量，标题里显示
        for (int i = 0; i < days; i++) {
            int[] agg = aggregateGames(root.at(i));
            totWin += agg[0];
            totLoss += agg[1];
            totDraw += agg[2];
            totGames += agg[0] + agg[1] + agg[2];
        }

        statusText.setText("近 " + days + " 天 " + totGames + " 局 · "
                + totWin + "胜 " + totLoss + "负 " + totDraw + "和");

        list.addView(Theme.sectionLabel(this, "每天明细"));

        for (int i = 0; i < days; i++) {
            Json day = root.at(i);
            if (day == null || !day.isObject()) {
                continue;
            }
            addDayRow(day);
        }
    }

    /** 把一天里所有分项（blitz/rapid/...）的胜负加总。返回 [win, loss, draw]。 */
    private static int[] aggregateGames(Json day) {
        int[] out = new int[3];
        if (day == null || !day.isObject()) {
            return out;
        }
        Json games = day.obj("games");
        if (games == null) {
            return out;
        }
        String[] keys = games.keys();
        for (int i = 0; i < keys.length; i++) {
            Json g = games.obj(keys[i]);
            if (g == null) {
                continue;
            }
            out[0] += g.i("win", 0);
            out[1] += g.i("loss", 0);
            out[2] += g.i("draw", 0);
        }
        return out;
    }

    private void addDayRow(Json day) {
        Json interval = day.obj("interval");
        long startMs = interval == null ? 0 : interval.l("start", 0);

        int[] agg = aggregateGames(day);
        int total = agg[0] + agg[1] + agg[2];

        String date = formatDay(startMs);
        String title;
        if (total == 0) {
            title = date + "  无对局";
        } else {
            title = date + "  " + agg[0] + "胜 " + agg[1] + "负 " + agg[2] + "和";
        }

        String sub = ratingChanges(day) + tournamentsOf(day);
        list.addView(Theme.row(this, title, sub));
    }

    /** 把各分项等级分变化拼成 "Blitz +50 / Rapid -8"。 */
    private static String ratingChanges(Json day) {
        Json games = day.obj("games");
        if (games == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] keys = games.keys();
        for (int i = 0; i < keys.length; i++) {
            Json g = games.obj(keys[i]);
            if (g == null) {
                continue;
            }
            Json rp = g.obj("rp");
            if (rp == null) {
                continue;
            }
            int before = rp.i("before", 0);
            int after = rp.i("after", 0);
            int diff = after - before;
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(prettyPerf(keys[i]));
            sb.append(diff >= 0 ? " +" : " ");
            sb.append(diff);
        }
        return sb.toString();
    }

    /** 锦标赛：打了几场 + 最好名次。 */
    private static String tournamentsOf(Json day) {
        Json t = day.obj("tournaments");
        if (t == null) {
            return "";
        }
        int nb = t.i("nb", 0);
        if (nb == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("  ·  锦标赛 ").append(nb).append(" 场");
        Json best = t.arr("best");
        if (best != null && best.size() > 0) {
            Json b = best.at(0);
            int rank = b.i("rank", 0);
            if (rank > 0) {
                sb.append("，最好第 ").append(rank).append(" 名");
            }
        }
        return sb.toString();
    }

    /** "blitz" → "Blitz"。 */
    static String prettyPerf(String key) {
        if (key.length() == 0) {
            return key;
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /**
     * 毫秒时间戳 → "09-30"。
     * 不用 java.text.SimpleDateFormat，因为 API 10 上它有点慢，
     * 而这里只要月日，手算更快也够用。
     */
    static String formatDay(long ms) {
        if (ms <= 0) {
            return "??-??";
        }
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(ms);
            int mon = c.get(java.util.Calendar.MONTH) + 1;
            int day = c.get(java.util.Calendar.DAY_OF_MONTH);
            return (mon < 10 ? "0" : "") + mon + "-" + (day < 10 ? "0" : "") + day;
        } catch (Throwable t) {
            return "??-??";
        }
    }
}
