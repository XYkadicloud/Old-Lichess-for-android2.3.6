package org.lichessold.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 自建日志系统（纯 Java，无 Android 依赖）。
 *
 *  - 内存里保留最近 600 行环形缓冲，「查看日志」页直接读内存，不读文件。
 *  - 文件写入交给外部注册的 {@link LogSink}（Android 层负责写 /sdcard/LichessOld/log.txt）。
 *  - 所有内容在进入环形缓冲之前先过 {@link #redact}，令牌绝不会进日志。
 *
 * 之所以不用 android.util.Log：它只写 logcat，没有 adb 就看不到。
 */
public final class Log {

    public static final int VERBOSE = 0;
    public static final int DEBUG = 1;
    public static final int INFO = 2;
    public static final int WARN = 3;
    public static final int ERROR = 4;

    private static final String[] LEVEL_NAMES = { "V", "D", "I", "W", "E" };
    private static final int MAX_LINES = 600;

    private static final String[] ring = new String[MAX_LINES];
    private static int ringCount = 0;
    private static int ringNext = 0;
    private static final Object ringLock = new Object();

    private static volatile LogSink sink;
    private static volatile boolean debugEnabled = false;

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 脱敏正则，预先编译。 */
    private static final Pattern RE_BEARER =
            Pattern.compile("(?i)(bearer[ \\t]+)[^ \\t\\r\\n\"']+");
    private static final Pattern RE_LIP =
            Pattern.compile("lip_[A-Za-z0-9]{6,}");
    private static final Pattern RE_TOKEN =
            Pattern.compile("(?i)(\"?token\"?[ \\t]*[:=][ \\t]*\"?)[^\"' \\t,}]{6,}");
    private static final Pattern RE_AUTH =
            Pattern.compile("(?i)(authorization[ \\t]*:[ \\t]*)\\S+");

    private Log() {
    }

    public static void setSink(LogSink s) {
        sink = s;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    // ------------------------------------------------------------ log calls

    public static void v(String tag, String msg) {
        add(VERBOSE, tag, msg, null);
    }

    public static void d(String tag, String msg) {
        if (!debugEnabled) {
            return;
        }
        add(DEBUG, tag, msg, null);
    }

    public static void i(String tag, String msg) {
        add(INFO, tag, msg, null);
    }

    public static void w(String tag, String msg) {
        add(WARN, tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        add(WARN, tag, msg, t);
    }

    public static void e(String tag, String msg) {
        add(ERROR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        add(ERROR, tag, msg, t);
    }

    private static void add(int level, String tag, String msg, Throwable t) {
        String line = format(level, tag, msg);
        pushRing(line);
        emit(line);
        if (t != null) {
            StackTraceElement[] els = t.getStackTrace();
            int n = els == null ? 0 : Math.min(els.length, 24);
            for (int i = 0; i < n; i++) {
                String s = "    at " + els[i];
                pushRing(s);
                emit(s);
            }
        }
    }

    private static String format(int level, String tag, String msg) {
        String ts;
        synchronized (FMT) {
            ts = FMT.format(new Date());
        }
        String lv = (level >= 0 && level < LEVEL_NAMES.length) ? LEVEL_NAMES[level] : "?";
        return ts + " " + lv + "/" + (tag == null ? "-" : tag) + ": " + redact(msg);
    }

    private static void emit(String line) {
        LogSink s = sink;
        if (s != null) {
            try {
                s.write(line);
            } catch (Throwable ignored) {
                // 日志系统本身绝不能把 App 搞崩
            }
        }
    }

    // ---------------------------------------------------------- ring buffer

    private static void pushRing(String line) {
        synchronized (ringLock) {
            ring[ringNext] = line;
            ringNext = (ringNext + 1) % MAX_LINES;
            if (ringCount < MAX_LINES) {
                ringCount++;
            }
        }
    }

    /** 最近日志，按时间从旧到新。 */
    public static String[] getRecent() {
        synchronized (ringLock) {
            String[] out = new String[ringCount];
            int start = (ringNext - ringCount + MAX_LINES * 2) % MAX_LINES;
            for (int i = 0; i < ringCount; i++) {
                out[i] = ring[(start + i) % MAX_LINES];
            }
            return out;
        }
    }

    public static String getRecentText() {
        String[] lines = getRecent();
        StringBuilder sb = new StringBuilder(lines.length * 64);
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public static void clearRing() {
        synchronized (ringLock) {
            for (int i = 0; i < MAX_LINES; i++) {
                ring[i] = null;
            }
            ringCount = 0;
            ringNext = 0;
        }
    }

    // ------------------------------------------------------------- redact

    /** 任何可能带令牌的字符串在写日志前必须过这里。宁可多替换，不可漏。 */
    public static String redact(String s) {
        if (s == null) {
            return "null";
        }
        try {
            String r = RE_AUTH.matcher(s).replaceAll("$1***");
            r = RE_BEARER.matcher(r).replaceAll("$1***");
            r = RE_LIP.matcher(r).replaceAll("***");
            r = RE_TOKEN.matcher(r).replaceAll("$1***");
            return r;
        } catch (Throwable ignored) {
            return "<redact-failed>";
        }
    }
}
