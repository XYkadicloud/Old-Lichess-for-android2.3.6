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
 * 单个锦标赛详情：赛制信息 + 排行榜 + 焦点对局。
 *
 * 数据源 {@code GET /api/tournament/{id}?page=n}，免登录。
 *
 * 实测接口结构（2026-10 核对）：
 * <pre>
 * {
 *   "id":"8Ula6Wc0", "fullName":"Hourly Bullet Arena",
 *   "clock":{"limit":60,"increment":0}, "minutes":27, "rated":true,
 *   "nbPlayers":49, "secondsToStart":1, "startsAt":"2026-10-01T10:30:20Z",
 *   "perf":{"key":"bullet","name":"Bullet"},
 *   "standing":{"page":1,"players":[{"name":"x","rank":1,"rating":2572,"score":0, ...}]}
 * }
 * </pre>
 *
 * 注意 startsAt 在**列表**接口里是毫秒整数，在**详情**接口里是 ISO 字符串，
 * 两边格式不一致，这里分别处理。
 */
public class TournamentActivity extends Activity {

    public static final String EXTRA_ID = "id";

    /** 排行榜一页显示多少行。屏幕小，一页 15 行已经要滚两屏了。 */
    private static final int ROWS_PER_PAGE = 15;

    private String tid = "";
    private int page = 1;
    private int lastPage = 1;

    private LinearLayout list;
    private TextView statusText;
    private TextView headerText;
    private Button prevButton;
    private Button nextButton;
    private LichessApi api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.fullscreen(this);
        Log.i("UI", "TournamentActivity onCreate");

        tid = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ID);
        if (tid == null) {
            tid = "";
        }

        ScrollView scroll = Ui.scroll(this, null);
        LinearLayout root = Ui.column(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Theme.header(this, "锦标赛"));

        headerText = Ui.text(this, tid.length() > 0 ? tid : "（没有 ID）",
                Ui.TEXT_NORMAL, Ui.FG);
        headerText.setPadding(Ui.dp(this, 8), Ui.dp(this, 4),
                Ui.dp(this, 8), 0);
        root.addView(headerText);

        statusText = Ui.text(this, "正在读取…", Ui.TEXT_SMALL, Ui.FG_DIM);
        statusText.setPadding(Ui.dp(this, 8), Ui.dp(this, 2),
                Ui.dp(this, 8), Ui.dp(this, 4));
        root.addView(statusText);

        list = Ui.column(this);
        root.addView(list);

        prevButton = Ui.smallButton(this, "上一页");
        prevButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (page > 1) {
                    page--;
                    load();
                }
            }
        });

        nextButton = Ui.smallButton(this, "下一页");
        nextButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (page < lastPage) {
                    page++;
                    load();
                }
            }
        });

        Button reload = Ui.smallButton(this, "刷新");
        reload.setOnClickListener(new View.OnClickListener() {
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

        root.addView(Ui.buttonRow(this, prevButton, nextButton, reload, back));

        if (tid.length() == 0) {
            statusText.setText("没有拿到锦标赛 ID");
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
        statusText.setText("正在读取第 " + page + " 页…");
        list.removeAllViews();
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);

        final int wantPage = page;

        Async.run("tournament", new Async.Job<Json>() {
            public Json run() throws Throwable {
                return api.tournament(tid, wantPage);
            }
        }, new Async.Done<Json>() {
            public void done(Json j, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    Log.w("UI", "拿锦标赛详情失败: " + error.getMessage());
                    statusText.setText("读取失败：" + msg(error));
                    prevButton.setEnabled(page > 1);
                    return;
                }
                try {
                    render(j);
                } catch (Throwable t) {
                    Log.e("UI", "渲染锦标赛详情失败", t);
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

    private void render(Json t) {
        list.removeAllViews();
        if (t == null || !t.isObject()) {
            statusText.setText("服务端返回了空数据");
            return;
        }

        String name = t.str("fullName", t.str("name", tid));
        headerText.setText(name);

        // ---- 赛制信息 ----
        Json clock = t.obj("clock");
        double limit = clock == null ? 0 : clock.d("limit", 0);
        int inc = clock == null ? 0 : clock.i("increment", 0);

        StringBuilder info = new StringBuilder();
        info.append(TournamentsActivity.formatClock(limit, inc));
        info.append(" · ").append(t.i("minutes", 0)).append("分钟");
        info.append(" · ").append(t.b("rated", false) ? "排位" : "休闲");
        info.append(" · ").append(t.i("nbPlayers", 0)).append(" 人");

        Json perf = t.obj("perf");
        if (perf != null && perf.str("name", "").length() > 0) {
            info.append(" · ").append(perf.str("name", ""));
        }

        int secsToStart = t.i("secondsToStart", -1);
        int secsToFinish = t.i("secondsToFinish", -1);
        if (secsToStart > 0) {
            info.append(" · ").append(TournamentsActivity.formatDuration(secsToStart))
                    .append("后开始");
        } else if (secsToFinish > 0) {
            info.append(" · 还剩 ")
                    .append(TournamentsActivity.formatDuration(secsToFinish));
        } else if (t.b("isFinished", false)) {
            info.append(" · 已结束");
        }

        statusText.setText(info.toString());

        // ---- 字符串形式的开始时间（详情接口和列表接口格式不同）----
        String startsAt = t.str("startsAt", "");
        if (startsAt.length() > 0) {
            TextView when = Ui.text(this, "开始于 " + simplifyIso(startsAt),
                    Ui.TEXT_SMALL, Ui.FG_DIM);
            when.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
            list.addView(when);
        }

        // ---- 焦点对局 ----
        renderFeatured(t);

        // ---- 排行榜 ----
        Json standing = t.obj("standing");
        Json players = standing == null ? null : standing.arr("players");

        if (players == null || players.size() == 0) {
            TextView empty = Ui.text(this, "排行榜暂时没有数据", Ui.TEXT_SMALL, Ui.FG_DIM);
            empty.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), 0, 0);
            list.addView(empty);
        } else {
            list.addView(Theme.sectionLabel(this, "排行榜 · 第 " + page + " 页"));
            int n = Math.min(players.size(), ROWS_PER_PAGE);
            for (int i = 0; i < n; i++) {
                Json p = players.at(i);
                if (p != null && p.isObject()) {
                    list.addView(playerRow(p));
                }
            }
            // 这一页取满了就说明后面多半还有
            lastPage = players.size() >= ROWS_PER_PAGE ? page + 1 : page;
        }

        prevButton.setEnabled(page > 1);
        nextButton.setEnabled(page < lastPage);
    }

    private LinearLayout playerRow(Json p) {
        int rank = p.i("rank", 0);
        String name = p.str("name", "?");
        int rating = p.i("rating", 0);
        Json score = p.get("score");
        String scoreText = score == null ? "0" : String.valueOf(score.asInt(0));

        String title = "#" + rank + "  " + name;
        String sub = (rating > 0 ? rating + " 分" : "") + " · 得分 " + scoreText;
        return Theme.row(this, title, sub);
    }

    /** 焦点对局 —— 服务端挑的高分段对局，点进去能观战。 */
    private void renderFeatured(Json t) {
        Json duels = t.arr("duels");
        if (duels == null || duels.size() == 0) {
            return;
        }
        list.addView(Theme.sectionLabel(this, "焦点对局"));

        int n = Math.min(duels.size(), 4);
        for (int i = 0; i < n; i++) {
            Json d = duels.at(i);
            if (d == null || !d.isObject()) {
                continue;
            }
            String gameId = d.str("id", "");
            if (gameId.length() == 0) {
                continue;
            }
            String white = d.str("p1", "?");
            String black = d.str("p2", "?");
            LinearLayout row = Theme.row(this, white + " vs " + black, "点这里观战");
            row.setOnClickListener(watch(gameId));
            list.addView(row);
        }
    }

    private View.OnClickListener watch(final String gameId) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent it = new Intent(TournamentActivity.this, TvActivity.class);
                    it.putExtra(TvActivity.EXTRA_GAME_ID, gameId);
                    startActivity(it);
                } catch (Throwable e) {
                    Log.e("UI", "从锦标赛打开观战失败", e);
                    Ui.toast(TournamentActivity.this, "打开观战失败: " + e);
                }
            }
        };
    }

    /** "2026-10-01T10:30:20Z" → "10-01 10:30"。 */
    static String simplifyIso(String iso) {
        if (iso.length() < 16) {
            return iso;
        }
        try {
            String date = iso.substring(5, 10);   // MM-DD
            String time = iso.substring(11, 16);   // HH:MM
            return date + " " + time;
        } catch (Throwable t) {
            return iso;
        }
    }
}
