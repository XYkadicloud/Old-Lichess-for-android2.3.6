package org.lichessold.desktop;

/**
 * 版本信息。对应 Android 版的 {@code org.lichessold.log.Version}
 * （那边从 PackageManager 读 versionName，这边是编译期常量 + 构建时间）。
 *
 * VERSION / BUILD_TIME 由 desktop/build.sh 在编译时写入 DesktopBuildInfo.java，
 * 所以这里只做读取和拼接，不硬编码。
 */
public final class DesktopVersion {

    /** 与 Android 版保持同一个版本号体系，桌面版从 1.0.0 起算。 */
    public static final String VERSION = "1.0.0";

    private DesktopVersion() {
    }

    public static String name() {
        return VERSION;
    }

    public static String buildTime() {
        return DesktopBuildInfo.BUILD_TIME;
    }

    public static String full() {
        return "v" + VERSION + "  (build " + buildTime() + ")";
    }

    /** 启动时打在日志头上的一行。 */
    public static String banner() {
        return "lichess desktop " + full();
    }
}
