package org.lichessold.log;

import org.lichessold.BuildInfo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * 版本信息。
 *
 * versionName / versionCode 由构建脚本注入 AndroidManifest（构建时才知道），
 * 所以运行时从 PackageManager 读，而不是硬编码在 Java 里。
 * 构建时间由脚本生成的 BuildInfo 提供。
 */
public final class Version {

    private Version() {
    }

    public static String name(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            if (pi.versionName == null) {
                return "?";
            }
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public static int code(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String buildTime() {
        return BuildInfo.BUILD_TIME;
    }

    public static String full(Context ctx) {
        return "v" + name(ctx) + "  (build " + buildTime() + ")";
    }
}
