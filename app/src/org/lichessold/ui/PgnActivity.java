package org.lichessold.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lichessold.chess.Pgn;
import org.lichessold.log.Storage;
import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.platform.Async;
import org.lichessold.platform.Net;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

import java.io.File;

/**
 * 棋谱 —— 拿某人最近一局的完整 PGN，可以重放、也可以存到存储卡。
 *
 * 数据源 {@code GET /api/user/{name}/current-game}，免登录。
 *
 * 为什么是这个端点：原来项目里写的是 {@code /api/games/user/{name}?max=n}，
 * 那个端点在 2026 年实测已经 404 了，那条导出链是断的。
 * current-game 依然有效，而且返回的就是一份标准 PGN，正好够用。
 *
 * 两个已知的服务端行为（都是设计如此，不是 bug）：
 *   1. 正在下的棋会**延迟 3 步**才吐出来 —— 防作弊用的
 *   2. 如果对方最近没下棋，服务端返回 404，这里翻译成「没有棋谱」
 */
public class PgnActivity extends Activity {

    /** PGN 预览最多显示多少行 —— 320x240 上再长就滚不完了。 */
    private static final int PREVIEW_LINES = 40;

    private TextView statusText;
    private TextView headText;
    private LinearLayout previewBox;
    private Button saveButton;

    private LichessApi api;
    private String targetName = "";
    private String pgn = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.fullscreen(this);
        Log.i("UI", "PgnActivity onCreate");

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

        root.addView(Theme.header(this, "棋谱"));

        headText = Ui.text(this, targetName.length() > 0 ? targetName : "（未知用户）",
                Ui.TEXT_NORMAL, Ui.FG);
        headText.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), 0);
        root.addView(headText);

        statusText = Ui.text(this, "正在读取…", Ui.TEXT_SMALL, Ui.FG_DIM);
        statusText.setPadding(Ui.dp(this, 8), Ui.dp(this, 2),
                Ui.dp(this, 8), Ui.dp(this, 4));
        root.addView(statusText);

        previewBox = Ui.column(this);
        root.addView(previewBox);

        Button reload = Ui.smallButton(this, "重新读取");
        reload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });

        saveButton = Ui.smallButton(this, "存到存储卡");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });

        Button back = Ui.smallButton(this, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        root.addView(Ui.buttonRow(this, reload, saveButton, back));

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
        statusText.setText("正在读取 " + targetName + " 最近一局的棋谱…");
        previewBox.removeAllViews();
        saveButton.setEnabled(false);
        pgn = "";

        Async.run("pgn", new Async.Job<String>() {
            public String run() throws Throwable {
                return api.lastGamePgn(targetName);
            }
        }, new Async.Done<String>() {
            public void done(String value, Throwable error) {
                if (isFinishing()) {
                    return;
                }
                if (error != null) {
                    Log.w("UI", "拿棋谱失败: " + error.getMessage());
                    statusText.setText("读取失败：" + msg(error));
                    return;
                }
                pgn = value == null ? "" : value;
                render();
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

    private void render() {
        previewBox.removeAllViews();

        if (pgn.length() == 0) {
            statusText.setText("没有棋谱（对方最近没下棋，或者这一局还没开始）");
            return;
        }
        if (pgn.startsWith("<!DOCTYPE") || pgn.startsWith("<html")) {
            // 兜底：服务端偶尔会返回 HTML 错误页，别把它当棋谱显示
            statusText.setText("服务端返回的是网页而不是棋谱，请稍后重试");
            return;
        }

        String white = tagOf("White");
        String black = tagOf("Black");
        String result = tagOf("Result");
        String site = tagOf("Site");
        String tc = tagOf("TimeControl");
        String opening = tagOf("Opening");

        statusText.setText("共 " + countMoves() + " 步 · "
                + (result.length() > 0 ? result : "进行中"));

        // 摘要行
        String vs = (white.length() > 0 ? white : "?") + " vs "
                + (black.length() > 0 ? black : "?");
        previewBox.addView(Theme.row(this, vs, summarise(site, tc, opening)));

        // 棋谱正文
        previewBox.addView(Theme.sectionLabel(this, "棋谱"));
        // 注意：不能用 setTextIsSelectable()，那是 API 11+ 的东西，
        // 本项目的 minSdk 锁在 10。想复制就存到存储卡拿文件。
        TextView body = Ui.text(this, preview(pgn), 12, Ui.FG);
        body.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 6));
        previewBox.addView(body);

        saveButton.setEnabled(true);
    }

    private static String summarise(String site, String tc, String opening) {
        StringBuilder sb = new StringBuilder();
        if (tc.length() > 0) {
            sb.append(convertTimeControl(tc));
        }
        if (opening.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(opening);
        }
        if (site.length() > 0) {
            int slash = site.lastIndexOf('/');
            String id = slash >= 0 ? site.substring(slash + 1) : site;
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** PGN 的 TimeControl "180+0" → "3+0 分钟"。 */
    static String convertTimeControl(String tc) {
        int plus = tc.indexOf('+');
        if (plus <= 0) {
            return tc;
        }
        try {
            int secs = Integer.parseInt(tc.substring(0, plus).trim());
            String inc = tc.substring(plus + 1).trim();
            int min = secs / 60;
            if (secs % 60 == 0 && min > 0) {
                return min + "+" + inc;
            }
            return secs + "秒+" + inc;
        } catch (Throwable t) {
            return tc;
        }
    }

    /** 从 PGN 头部读一个标签，读不到返回空串。 */
    String tagOf(String tag) {
        String key = "[" + tag + " \"";
        int at = pgn.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = at + key.length();
        int end = pgn.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        String v = pgn.substring(start, end);
        // PGN 里转义的双引号
        return v.replace("\\\"", "\"");
    }

    /** 用项目自带的 Pgn 解析器数步数，比数空格靠谱。 */
    int countMoves() {
        try {
            return Pgn.toUciList(pgn).length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 只显示前 PREVIEW_LINES 行，避免给一个小屏幕塞几千行文本。 */
    static String preview(String full) {
        String[] lines = full.split("\n");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < lines.length && shown < PREVIEW_LINES; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            // 跳过 PGN 的一堆标签头，只留走子部分（上面已经单列摘要了）
            if (line.startsWith("[") && line.endsWith("]")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
            shown++;
        }
        if (lines.length > shown) {
            sb.append("\n…（只显示了前面一部分，存到存储卡可以看全部）");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 存盘

    private void save() {
        if (pgn.length() == 0) {
            return;
        }
        String name = "lichess-" + targetName + "-" + stamp() + ".pgn";
        File out = Storage.writeText(this, name, pgn);
        if (out == null) {
            Ui.toast(this, "保存失败：存储卡不可写");
            return;
        }
        Log.i("UI", "棋谱已保存: " + out.getAbsolutePath());
        Ui.toast(this, "已保存到\n" + out.getAbsolutePath());
    }

    private static String stamp() {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            int y = c.get(java.util.Calendar.YEAR);
            int mo = c.get(java.util.Calendar.MONTH) + 1;
            int d = c.get(java.util.Calendar.DAY_OF_MONTH);
            int h = c.get(java.util.Calendar.HOUR_OF_DAY);
            int mi = c.get(java.util.Calendar.MINUTE);
            return y + pad(mo) + pad(d) + "-" + pad(h) + pad(mi);
        } catch (Throwable t) {
            return "now";
        }
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
