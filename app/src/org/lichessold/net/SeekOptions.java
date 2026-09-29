package org.lichessold.net;

/**
 * Board API「寻找对手」可用的时间档 + 合法性校验。
 *
 * 为什么单独一个类：这套规则来自服务端，是**协议知识**，不是界面知识。
 * 放在纯 Java 层（零 Android 依赖）就能在桌面 JVM 上跑测试，把每个档位
 * 都按 lila 的真实校验规则过一遍 —— 0.5.0 就是因为没这么干，
 * 把秒当成分钟传，整个功能直接 400。
 *
 * 两条规则（都从 lila 源码核过）：
 *
 * 1) POST /api/board/seek 的 `time` 是**分钟**（Double）。
 *    modules/setup/src/main/Config.scala:
 *      timeMin = 0, timeMax = 180
 *      acceptableFractions = { 1/4, 1/2, 3/4, 3/2 }
 *      validateTime(t) = t >= 0 && t <= 180 && (t.isWhole || acceptableFractions(t))
 *    `increment` 是**秒**，0~180。
 *
 * 2) 用个人令牌时 Board API 只允许 Rapid 及更慢：
 *    lila isBoardCompatible(clock) = Speed(clock) >= Speed.Rapid
 *    Speed 按 estimateTotalSeconds = limit + 40 * increment 分档（scalachess）：
 *      UltraBullet 0-29 / Bullet 30-179 / Blitz 180-479
 *      Rapid 480-1499 / Classical 1500-21599 / Correspondence 21600+
 *    所以下限是 480 秒。
 */
public final class SeekOptions {

    /** 下拉里显示的档位标签。 */
    public static final String[] LABELS = {
            "8+0    快速棋",
            "10+0   快速棋",
            "10+5   快速棋",
            "15+0   快速棋",
            "15+10  快速棋",
            "30+0   经典棋",
    };

    /** 初始时间，单位**分钟**（不是秒！）。 */
    public static final double[] MINUTES = { 8, 10, 10, 15, 15, 30 };

    /** 每步加秒。 */
    public static final int[] INCREMENTS = { 0, 0, 5, 0, 10, 0 };

    /** 默认高亮第几个（10+0 快速棋）。 */
    public static final int DEFAULT_INDEX = 1;

    private SeekOptions() {
    }

    public static int count() {
        return MINUTES.length;
    }

    /**
     * 镜像 lila 的 validateTime：分钟数必须是 0~180 的整数，
     * 或者是 0.25 / 0.5 / 0.75 / 1.5 这几个允许的分数。
     */
    public static boolean validateTime(double minutes) {
        if (Double.isNaN(minutes) || Double.isInfinite(minutes)) {
            return false;
        }
        if (minutes < 0 || minutes > 180) {
            return false;
        }
        if (minutes == Math.floor(minutes)) {
            return true;
        }
        return minutes == 0.25 || minutes == 0.5 || minutes == 0.75 || minutes == 1.5;
    }

    /**
     * 镜像 lila 的 validateIncrement：秒数 0~180 的整数。
     */
    public static boolean validateIncrement(int seconds) {
        return seconds >= 0 && seconds <= 180;
    }

    /**
     * 这个档位能不能真的被服务端接受。
     * = 分钟合法 && 加秒合法 && 速度达到 Rapid 下限（480 秒）
     */
    public static boolean isUsable(int index) {
        if (index < 0 || index >= MINUTES.length) {
            return false;
        }
        double minutes = MINUTES[index];
        int inc = INCREMENTS[index];
        return validateTime(minutes)
                && validateIncrement(inc)
                && LichessApi.boardSeekCompatible((int) (minutes * 60), inc);
    }

    /**
     * 自检：表里每一项都必须可用。
     * 桌面测试和界面启动时都可以调，防止以后加档位时忘了算 480 秒下限。
     *
     * @return 第一个不合法档位的下标；全部合法返回 -1
     */
    public static int firstBadIndex() {
        if (MINUTES.length != INCREMENTS.length || MINUTES.length != LABELS.length) {
            return 0;
        }
        for (int i = 0; i < MINUTES.length; i++) {
            if (!isUsable(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 估算总时长（秒），用来判断速度档位。 */
    public static int estimateTotalSeconds(int index) {
        if (index < 0 || index >= MINUTES.length) {
            return 0;
        }
        return (int) (MINUTES[index] * 60) + 40 * INCREMENTS[index];
    }

    /** 速度档位的中文名（lichess 的 6 档）。 */
    public static String speedName(int estimateSeconds) {
        if (estimateSeconds < 30) {
            return "超快棋";
        }
        if (estimateSeconds < 180) {
            return "子弹棋";
        }
        if (estimateSeconds < 480) {
            return "闪电棋";
        }
        if (estimateSeconds < 1500) {
            return "快速棋";
        }
        if (estimateSeconds < 21600) {
            return "经典棋";
        }
        return "通信棋";
    }
}
