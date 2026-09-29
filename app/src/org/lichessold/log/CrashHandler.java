package org.lichessold.log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.lichessold.util.Log;

/**
 * 未捕获异常处理。因为没有 adb，崩溃报告是唯一的排错手段。
 *
 *  1. 崩溃时把堆栈写 /sdcard/LichessOld/crash.txt（直接写，不走异步队列，进程随时会死）
 *  2. 报告存 SharedPreferences（commit 同步落盘）
 *  3. 下次启动时主界面弹窗，用户拍照即可反馈
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String PREFS = "lichessold_crash";
    private static final String KEY_PENDING = "pending_report";
    private static final String CRASH_FILE = "crash.txt";

    private static Thread.UncaughtExceptionHandler previous;
    private static Context appContext;

    public static void install(Context ctx) {
        appContext = ctx.getApplicationContext();
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
        Log.i("Crash", "uncaught exception handler installed");
    }

    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            String report = buildReport(thread, ex);
            Log.e("Crash", "UNCAUGHT on thread " + thread.getName(), ex);
            writeCrashFile(report);
            savePending(appContext, report);
            Logging.flush(2500L);
        } catch (Throwable ignored) {
            // 崩溃处理本身绝不能再抛异常
        }
        if (previous != null) {
            previous.uncaughtException(thread, ex);
        }
    }

    private static String buildReport(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder(3072);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        sb.append("===== LichessOld crash report =====\n");
        sb.append("time      : ").append(fmt.format(new Date())).append('\n');
        sb.append("version   : ").append(Version.name(appContext)).append('\n');
        sb.append("buildTime : ").append(Version.buildTime()).append('\n');
        sb.append("thread    : ").append(thread == null ? "?" : thread.getName()).append('\n');
        sb.append("device    : ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        sb.append("android   : ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("heapMaxKB : ").append(Runtime.getRuntime().maxMemory() / 1024L).append('\n');
        sb.append("heapUsedKB: ").append(usedHeapKb()).append('\n');
        sb.append("---- stack trace ----\n");
        sb.append(stackTrace(ex));
        sb.append("---- last logs ----\n");
        sb.append(Log.getRecentText());
        sb.append("===== end =====\n");
        return sb.toString();
    }

    private static long usedHeapKb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024L;
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "<null throwable>\n";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static void writeCrashFile(String report) {
        File dir = Logging.logDir();
        if (dir == null) {
            return;
        }
        OutputStreamWriter osw = null;
        try {
            FileOutputStream fos = new FileOutputStream(new File(dir, CRASH_FILE), false);
            osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(report);
            osw.flush();
        } catch (IOException ignored) {
        } finally {
            if (osw != null) {
                try {
                    osw.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void savePending(Context ctx, String report) {
        if (ctx == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // commit() 而不是 apply()：进程马上要死，必须同步落盘
        sp.edit().putString(KEY_PENDING, report).commit();
    }

    /** 取出上次崩溃报告并清除标记。没有崩溃则返回 null。 */
    public static String takePending(Context ctx) {
        if (ctx == null) {
            return null;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_PENDING, null);
        if (s != null) {
            sp.edit().remove(KEY_PENDING).commit();
        }
        return s;
    }
}
