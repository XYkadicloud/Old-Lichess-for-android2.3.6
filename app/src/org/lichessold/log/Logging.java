package org.lichessold.log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.content.Context;

import org.lichessold.util.Log;

/**
 * Android 侧的日志初始化与文件管理。
 *
 * 把"日志内容"（纯 Java 的 util.Log）和"日志落盘"（这里）分开，
 * 是为了让 net/chess/json 这些核心包能在桌面 JVM 上跑真实测试。
 */
public final class Logging {

    private static FileLogSink sink;
    private static File logDir;
    private static boolean external;

    private Logging() {
    }

    public static void init(Context ctx) {
        File dir = Storage.getLogDir(ctx);
        logDir = dir;
        external = dir != null && Storage.isExternal(dir);
        if (dir != null) {
            sink = new FileLogSink(dir);
            Log.setSink(sink);
        }
    }

    public static File logDir() {
        return logDir;
    }

    public static File logFile() {
        return sink == null ? null : sink.file();
    }

    public static boolean isExternal() {
        return external;
    }

    public static void flush(long timeoutMs) {
        if (sink != null) {
            sink.flush(timeoutMs);
        }
    }

    /** 清空内存环形缓冲并截断日志文件。 */
    public static void clear() {
        Log.clearRing();
        File f = logFile();
        if (f != null && f.exists()) {
            try {
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(new byte[0]);
                fos.flush();
                fos.close();
            } catch (IOException ignored) {
            }
        }
    }
}
