package org.lichessold.desktop;

import java.io.File;
import java.net.URL;

/**
 * 桌面版的路径解析。
 *
 * 项目约定「工具链和产物全部在项目盘，不写 C 盘」，所以数据目录优先放在
 * **jar 所在目录下的 data/**；只有那里不可写（比如把 jar 放进了只读目录）
 * 才退回用户主目录。
 *
 * 配置、日志、诊断结果都在这里下面，用户删掉整个 data 目录就等于恢复出厂。
 */
public final class DesktopPaths {

    private static File baseDir;

    private DesktopPaths() {
    }

    /** jar 或 class 文件所在的目录。 */
    public static synchronized File appDir() {
        if (baseDir != null) {
            return baseDir;
        }
        File dir = null;
        try {
            URL self = DesktopPaths.class.getProtectionDomain().getCodeSource().getLocation();
            if (self != null) {
                File f = new File(self.toURI());
                dir = f.isFile() ? f.getParentFile() : f;
            }
        } catch (Throwable ignored) {
            // 拿不到就退回当前工作目录
        }
        if (dir == null) {
            dir = new File(System.getProperty("user.dir", "."));
        }
        baseDir = dir;
        return baseDir;
    }

    /** 数据目录，保证存在且可写。 */
    public static File dataDir() {
        File preferred = new File(appDir(), "data");
        if (ensureWritable(preferred)) {
            return preferred;
        }
        File fallback = new File(System.getProperty("user.home", "."), ".lichessold-desktop");
        ensureWritable(fallback);
        return fallback;
    }

    private static boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            return dir.isDirectory() && dir.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    public static File file(String name) {
        return new File(dataDir(), name);
    }

    public static File logDir() {
        File d = new File(dataDir(), "logs");
        ensureWritable(d);
        return d;
    }

    /** 打印一下实际用到哪，方便用户排查「配置到底存哪了」。 */
    public static String describe() {
        return "数据目录: " + dataDir().getAbsolutePath();
    }
}
