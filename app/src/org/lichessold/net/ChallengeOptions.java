package org.lichessold.net;

/**
 * 挑战（/api/challenge/ai、/api/challenge/{username}）可用的时间档 + 校验。
 *
 * ⚠️ 单位：这里的 clock.limit 是**秒**，和 /api/board/seek 的 time（分钟）不同。
 *
 * lila 的真实校验（modules/setup/src/main/ApiConfig.scala）：
 *   clockLimitSeconds = { 0, 15, 30, 45, 60, 90 } ∪ { 120, 180, 240, ..., 10800 }
 *                       （即 2~180 分钟的整倍数）
 *   clockMapping:
 *     "limit"     -> number.into[Clock.LimitSeconds].verifying(clockLimitSeconds.contains)
 *     "increment" -> increment  (0~180 秒)
 *     整个 mapping .verifying("Invalid clock", _.estimateTotalTime.nonZero)
 *   所以 limit=0 且 increment=0 也会被拒（估算总时长为 0）。
 *
 * 另外 /api/challenge/ai **没有 rated 字段** —— 和电脑下永远是休闲，
 * 服务端固定 rated=false（openapi 示例： "rated": false, "source": "ai"）。
 */
public final class ChallengeOptions {

    public static final String[] LABELS = {
            "1+0   超快棋",
            "3+0   闪电棋",
            "3+2   闪电棋",
            "5+0   闪电棋",
            "5+3   闪电棋",
            "10+0  快速棋",
            "10+5  快速棋",
            "15+10 快速棋",
            "30+0  经典棋",
    };

    /** {clock.limit 秒, clock.increment 秒}。 */
    public static final int[][] VALUES = {
            { 60, 0 }, { 180, 0 }, { 180, 2 }, { 300, 0 }, { 300, 3 },
            { 600, 0 }, { 600, 5 }, { 900, 10 }, { 1800, 0 },
    };

    public static final int DEFAULT_INDEX = 5;

    private ChallengeOptions() {
    }

    public static int count() {
        return VALUES.length;
    }

    /** 镜像 lila 的 ApiConfig.clockLimitSeconds.contains。 */
    public static boolean limitAllowed(int seconds) {
        if (seconds == 0 || seconds == 15 || seconds == 30
                || seconds == 45 || seconds == 60 || seconds == 90) {
            return true;
        }
        return seconds >= 120 && seconds <= 10800 && (seconds % 60) == 0;
    }

    /** 镜像 increment 映射：0~180 秒。 */
    public static boolean incrementAllowed(int seconds) {
        return seconds >= 0 && seconds <= 180;
    }

    /** limit + increment 不能都是 0，否则估算总时长为 0 会被拒。 */
    public static boolean nonZeroClock(int limitSec, int incSec) {
        return limitSec + incSec > 0;
    }

    public static boolean isUsable(int index) {
        if (index < 0 || index >= VALUES.length) {
            return false;
        }
        int limit = VALUES[index][0];
        int inc = VALUES[index][1];
        return limitAllowed(limit) && incrementAllowed(inc) && nonZeroClock(limit, inc);
    }

    /** 第一个不合法档位的下标；全部合法返回 -1。 */
    public static int firstBadIndex() {
        if (LABELS.length != VALUES.length) {
            return 0;
        }
        for (int i = 0; i < VALUES.length; i++) {
            if (!isUsable(i)) {
                return i;
            }
        }
        return -1;
    }
}
