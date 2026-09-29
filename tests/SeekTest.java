import java.util.LinkedHashMap;
import java.util.Map;

import org.lichessold.json.Json;
import org.lichessold.net.ChallengeOptions;
import org.lichessold.net.Http;
import org.lichessold.net.LichessApi;
import org.lichessold.net.SeekOptions;

/**
 * 时间控制参数测试。
 *
 * 这个测试类存在的唯一理由：0.5.0 的「寻找真人对手」整个功能是坏的 ——
 * 随便选哪个时间都报
 *     HTTP 400 {"time": ["无效值"], "error": {"time": ["无效值"]}}
 * 根因是 **单位搞错**：/api/board/seek 的 time 是**分钟**（0~180），
 * 代码传的是**秒**（60/180/300/600/900/1800），300 以上全部越界。
 *
 * 所以这里把 lila 的校验规则原样抄下来当断言，把每个档位都过一遍。
 * 规则来源（都从 lila / scalachess 源码核过）：
 *   modules/setup/src/main/Config.scala        validateTime / validateIncrement
 *   modules/setup/src/main/Mappings.scala      time = of[Double].verifying(validateTime)
 *   modules/setup/src/main/ApiConfig.scala     clockLimitSeconds
 *   modules/core/src/main/game/misc.scala      isBoardCompatible = Speed >= Rapid
 *   scalachess core Speed.scala                Rapid = 480..1499 秒
 *
 * 顺带把 /api/challenge/* 的 clock.limit 白名单也验一遍。
 */
public final class SeekTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== 时间控制参数测试 ===");
        System.out.println();

        testMinutesFormatter();
        testSeekTable();
        testSeekRequestForm();
        testLilaValidateTimeMirror();
        testBoardCompatibleBoundary();
        testRegression0_5_0WasRejected();
        testChallengeTable();
        testChallengeRequestForm();
        testChallengeColorField();

        summary();
    }

    // ------------------------------------------------------------------ 1

    private static void testMinutesFormatter() {
        check("formatMinutes(10) = \"10\"（整数不带小数点）",
                "10".equals(LichessApi.formatMinutes(10)), LichessApi.formatMinutes(10));
        check("formatMinutes(0.25) = \"0.25\"（允许的分数档）",
                "0.25".equals(LichessApi.formatMinutes(0.25)), LichessApi.formatMinutes(0.25));
        check("formatMinutes(1.5) = \"1.5\"",
                "1.5".equals(LichessApi.formatMinutes(1.5)), LichessApi.formatMinutes(1.5));
        check("formatMinutes(-5) 夹到 0",
                "0".equals(LichessApi.formatMinutes(-5)), LichessApi.formatMinutes(-5));
        check("formatMinutes(180) = \"180\"（上限）",
                "180".equals(LichessApi.formatMinutes(180)), LichessApi.formatMinutes(180));

        // 格式化出来的字符串必须能被 Double 解析回原值（服务端就是这么解析的）
        double[] samples = { 0, 0.25, 0.5, 0.75, 1.5, 3, 8, 10, 15, 30, 60, 180 };
        boolean roundTrip = true;
        String bad = "";
        for (int i = 0; i < samples.length; i++) {
            String s = LichessApi.formatMinutes(samples[i]);
            try {
                double back = Double.parseDouble(s);
                if (back != samples[i]) {
                    roundTrip = false;
                    bad = samples[i] + " -> " + s + " -> " + back;
                    break;
                }
            } catch (NumberFormatException e) {
                roundTrip = false;
                bad = samples[i] + " -> " + s + " 无法解析";
                break;
            }
        }
        check("分钟格式化后能被 Double 解析回原值", roundTrip, bad);
    }

    // ------------------------------------------------------------------ 2

    private static void testSeekTable() {
        check("SeekOptions 表长一致",
                SeekOptions.MINUTES.length == SeekOptions.INCREMENTS.length
                        && SeekOptions.MINUTES.length == SeekOptions.LABELS.length,
                "minutes=" + SeekOptions.MINUTES.length
                        + " increments=" + SeekOptions.INCREMENTS.length
                        + " labels=" + SeekOptions.LABELS.length);

        check("SeekOptions 至少有一档", SeekOptions.count() > 0,
                "count=" + SeekOptions.count());

        int bad = SeekOptions.firstBadIndex();
        check("SeekOptions 每一档都能被服务端接受（validateTime + Rapid 下限）",
                bad < 0, bad < 0 ? "" : describeSeek(bad));

        check("默认档位合法",
                SeekOptions.DEFAULT_INDEX >= 0 && SeekOptions.DEFAULT_INDEX < SeekOptions.count()
                        && SeekOptions.isUsable(SeekOptions.DEFAULT_INDEX),
                "default=" + SeekOptions.DEFAULT_INDEX);

        // 逐档打印，出问题时一眼能看出是哪一档
        for (int i = 0; i < SeekOptions.count(); i++) {
            int est = SeekOptions.estimateTotalSeconds(i);
            System.out.println("       [" + i + "] " + SeekOptions.MINUTES[i] + " 分钟 +"
                    + SeekOptions.INCREMENTS[i] + " 秒  -> 估算 " + est + " 秒 = "
                    + SeekOptions.speedName(est)
                    + (SeekOptions.isUsable(i) ? "" : "   <<< 不合法"));
        }
    }

    private static String describeSeek(int i) {
        return "第 " + i + " 档: " + SeekOptions.MINUTES[i] + "分钟 +"
                + SeekOptions.INCREMENTS[i] + "秒, 估算 "
                + SeekOptions.estimateTotalSeconds(i) + " 秒";
    }

    // ------------------------------------------------------------------ 3

    private static void testSeekRequestForm() {
        // 完全按 LichessApi.seek 的写法拼一遍表单，检查字段名和单位
        for (int i = 0; i < SeekOptions.count(); i++) {
            double minutes = SeekOptions.MINUTES[i];
            int inc = SeekOptions.INCREMENTS[i];

            Map<String, String> form = new LinkedHashMap<String, String>();
            form.put("rated", "false");
            form.put("time", LichessApi.formatMinutes(minutes));
            form.put("increment", String.valueOf(Math.max(0, inc)));
            String body = Http.formEncode(form);

            boolean hasTime = body.contains("time=" + LichessApi.formatMinutes(minutes));
            boolean hasInc = body.contains("increment=" + inc);
            boolean hasRated = body.contains("rated=");

            check("seek 表单第 " + i + " 档字段齐全（" + body + "）",
                    hasTime && hasInc && hasRated, body);
        }

        // 关键回归：表单里的 time 必须是**分钟**
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("rated", "true");
        form.put("time", LichessApi.formatMinutes(10));
        form.put("increment", "5");
        String body = Http.formEncode(form);
        check("10+5 的表单是 time=10（分钟）而不是 time=600（秒）",
                body.contains("time=10") && !body.contains("time=600"), body);
        check("10+5 的表单含 rated=true（排位）",
                body.contains("rated=true"), body);
    }

    // ------------------------------------------------------------------ 4

    private static void testLilaValidateTimeMirror() {
        // lila: t >= 0 && t <= 180 && (t.isWhole || t in {0.25, 0.5, 0.75, 1.5})
        double[] ok = { 0, 0.25, 0.5, 0.75, 1.5, 1, 2, 3, 5, 8, 10, 15, 30, 60, 180 };
        double[] no = { -1, -0.25, 0.1, 0.3, 1.25, 2.5, 180.5, 181, 600, 900, 1800 };
        boolean allOk = true;
        String bad = "";
        for (int i = 0; i < ok.length; i++) {
            if (!SeekOptions.validateTime(ok[i])) {
                allOk = false;
                bad = "应当合法却判为非法: " + ok[i];
                break;
            }
        }
        check("validateTime 接受所有合法分钟数（含 0.25/0.5/0.75/1.5）", allOk, bad);

        boolean allNo = true;
        bad = "";
        for (int i = 0; i < no.length; i++) {
            if (SeekOptions.validateTime(no[i])) {
                allNo = false;
                bad = "应当非法却判为合法: " + no[i];
                break;
            }
        }
        check("validateTime 拒绝越界与非法分数", allNo, bad);

        check("validateIncrement 边界：0 合法、180 合法、181 非法、-1 非法",
                SeekOptions.validateIncrement(0) && SeekOptions.validateIncrement(180)
                        && !SeekOptions.validateIncrement(181)
                        && !SeekOptions.validateIncrement(-1), "");
    }

    // ------------------------------------------------------------------ 5

    private static void testBoardCompatibleBoundary() {
        // Rapid 下限正好是 480 秒
        check("boardSeekCompatible(480, 0) = true（Rapid 下限，含等号）",
                LichessApi.boardSeekCompatible(480, 0), "");
        check("boardSeekCompatible(479, 0) = false（差 1 秒就是闪电棋）",
                !LichessApi.boardSeekCompatible(479, 0), "");
        check("boardSeekCompatible(180, 80) = true（3+2 靠加秒凑够 480）",
                LichessApi.boardSeekCompatible(180, 80), "");
        check("boardSeekCompatible(300, 3) = false（5+3 = 420 秒，不够 480）",
                !LichessApi.boardSeekCompatible(300, 3), "");
        check("boardSeekCompatible(300, 5) = true（5+5 = 500 秒，刚好够）",
                LichessApi.boardSeekCompatible(300, 5), "");
        check("boardSeekCompatible(180, 400) = true（3+10 = 580 秒）",
                LichessApi.boardSeekCompatible(180, 400), "");

        // 0.5.0 的档位在修正单位后仍然只有一部分能用 —— 这些必须被判为不可用
        check("旧的 1+0 / 3+0 / 5+3 档位被正确判为 Board API 不可用",
                !LichessApi.boardSeekCompatible(60, 0)
                        && !LichessApi.boardSeekCompatible(180, 0)
                        && !LichessApi.boardSeekCompatible(300, 3), "");
    }

    // ------------------------------------------------------------------ 6

    private static void testRegression0_5_0WasRejected() {
        // 0.5.0 实际发出去的 time 值（秒）
        int[] oldSeconds = { 60, 180, 180, 300, 300, 600, 600, 900, 1800 };
        int rejected = 0;
        for (int i = 0; i < oldSeconds.length; i++) {
            if (!SeekOptions.validateTime(oldSeconds[i])) {
                rejected++;
            }
        }
        check("回归：0.5.0 传的秒值里有 " + rejected + " 个会被 lila 拒掉（正是用户看到的 400）",
                rejected == 6, "被拒 " + rejected + "/" + oldSeconds.length
                        + "（应等于 6：300/300/600/600/900/1800）");

        check("回归：旧值 600 确实非法（>180 上限）",
                !SeekOptions.validateTime(600), "");
    }

    // ------------------------------------------------------------------ 7

    private static void testChallengeTable() {
        check("ChallengeOptions 表长一致",
                ChallengeOptions.LABELS.length == ChallengeOptions.VALUES.length,
                "labels=" + ChallengeOptions.LABELS.length
                        + " values=" + ChallengeOptions.VALUES.length);

        int bad = ChallengeOptions.firstBadIndex();
        check("ChallengeOptions 每一档的 clock.limit 都在 lila 白名单里",
                bad < 0, bad < 0 ? "" : ("第 " + bad + " 档: "
                        + ChallengeOptions.VALUES[bad][0] + "+"
                        + ChallengeOptions.VALUES[bad][1]));

        // 白名单边界
        check("clockLimitSeconds 边界：60 合法、120 合法、10800 合法、10860 非法、200 非法",
                ChallengeOptions.limitAllowed(60) && ChallengeOptions.limitAllowed(120)
                        && ChallengeOptions.limitAllowed(10800)
                        && !ChallengeOptions.limitAllowed(10860)
                        && !ChallengeOptions.limitAllowed(200), "");
        check("clockLimitSeconds 特例：0/15/30/45/60/90 都在集合里",
                ChallengeOptions.limitAllowed(0) && ChallengeOptions.limitAllowed(15)
                        && ChallengeOptions.limitAllowed(30) && ChallengeOptions.limitAllowed(45)
                        && ChallengeOptions.limitAllowed(90), "");
        boolean incOk = true;
        String incBad = "";
        for (int i = 0; i < ChallengeOptions.count(); i++) {
            int inc = ChallengeOptions.VALUES[i][1];
            if (inc > 60) {
                incOk = false;
                incBad = "第 " + i + " 档 increment=" + inc + "，超过 openapi 的 60 上限";
                break;
            }
        }
        check("challenge 表里所有 clock.increment 都不超过 60（openapi 上限）", incOk, incBad);

        // 全零时钟会被 .verifying(estimateTotalTime.nonZero) 拒掉
        check("limit=0 且 increment=0 会被拒（estimateTotalTime 为 0）",
                !ChallengeOptions.nonZeroClock(0, 0) && ChallengeOptions.nonZeroClock(0, 1), "");

        for (int i = 0; i < ChallengeOptions.count(); i++) {
            System.out.println("       [" + i + "] " + ChallengeOptions.LABELS[i]
                    + "  -> clock.limit=" + ChallengeOptions.VALUES[i][0]
                    + " clock.increment=" + ChallengeOptions.VALUES[i][1]
                    + (ChallengeOptions.isUsable(i) ? "" : "   <<< 不合法"));
        }
    }

    // ------------------------------------------------------------------ 8

    private static void testChallengeRequestForm() {
        for (int i = 0; i < ChallengeOptions.count(); i++) {
            int limit = ChallengeOptions.VALUES[i][0];
            int inc = ChallengeOptions.VALUES[i][1];

            Map<String, String> form = new LinkedHashMap<String, String>();
            form.put("rated", "true");
            form.put("color", "random");
            form.put("clock.limit", String.valueOf(limit));
            form.put("clock.increment", String.valueOf(Math.max(0, inc)));
            String body = Http.formEncode(form);

            check("challenge 表单第 " + i + " 档字段齐全（" + body + "）",
                    body.contains("clock.limit=" + limit)
                            && body.contains("clock.increment=" + inc)
                            && body.contains("rated=true"), body);
        }

        // 和 seek 的区别必须清晰：这里单位是秒
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("clock.limit", "600");
        form.put("clock.increment", "5");
        String body = Http.formEncode(form);
        check("challenge 的 clock.limit 是秒（600 就是 10 分钟）",
                body.contains("clock.limit=600"), body);
    }

    // ------------------------------------------------------------------ 9

    private static void testChallengeColorField() {
        // openapi 的 challenges-challengeAi.json 示例
        String sample = "{\"id\":\"KtgoVyDJ\",\"variant\":{\"key\":\"standard\"},"
                + "\"speed\":\"correspondence\",\"perf\":\"correspondence\","
                + "\"rated\":false,\"source\":\"ai\",\"createdAt\":1789845994138,"
                + "\"turns\":0,\"status\":{\"id\":20,\"name\":\"started\"},"
                + "\"player\":\"white\",\"fullId\":\"KtgoVyDJCSDS\"}";
        Json j = Json.parseQuietly(sample, null);
        check("从 /api/challenge/ai 响应里读出 player=white",
                "white".equals(LichessApi.myColorFromChallenge(j)),
                String.valueOf(LichessApi.myColorFromChallenge(j)));

        Json black = Json.parseQuietly("{\"player\":\"black\"}", null);
        check("player=black 能读出来",
                "black".equals(LichessApi.myColorFromChallenge(black)), "");

        // 兼容旧字段名 color
        Json fallback = Json.parseQuietly("{\"color\":\"black\"}", null);
        check("没有 player 时退回读 color",
                "black".equals(LichessApi.myColorFromChallenge(fallback)), "");

        // 垃圾值不能当成颜色
        Json junk = Json.parseQuietly("{\"player\":\"purple\"}", null);
        check("非法颜色值返回空串",
                "".equals(LichessApi.myColorFromChallenge(junk)), "");
        check("null 输入返回空串",
                "".equals(LichessApi.myColorFromChallenge(null)), "");
    }

    // ---------------------------------------------------------------- 工具

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (!ok && detail != null && detail.length() > 0) {
            System.out.println("       " + detail);
        }
    }

    private static void summary() {
        System.out.println("================================");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("================================");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
