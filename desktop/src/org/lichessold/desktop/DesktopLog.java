package org.lichessold.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.lichessold.log.FileLogSink;
import org.lichessold.util.Log;

/**
 * 桌面版日志初始化。对应 Android 版的 {@code org.lichessold.log.Logging}。
 *
 * 日志内容（环形缓冲 + 令牌脱敏）由纯 Java 的 {@code util.Log} 负责，
 * 落盘由 {@code log.FileLogSink} 负责 —— 那个文件本身没有任何 Android 依赖，
 * 所以桌面版直接复用它，行为（含 512KB 轮转）与手机完全一致。
 */
public final class DesktopLog {

    private static FileLogSink sink;
    private static File logDir;

    private DesktopLog() {
    }

    public static void init() {
        logDir = DesktopPaths.logDir();
        sink = new FileLogSink(logDir);
        Log.setSink(sink);
        Log.setDebugEnabled(DesktopPrefs.get().isDebugMode());
    }

    public static File logDir() {
        return logDir;
    }

    public static File logFile() {
        return sink == null ? null : sink.file();
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
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f, false);
                fos.write(new byte[0]);
                fos.flush();
            } catch (IOException ignored) {
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
