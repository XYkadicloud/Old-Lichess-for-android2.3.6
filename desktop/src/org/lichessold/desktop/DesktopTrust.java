package org.lichessold.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.lichessold.net.TrustAnchors;
import org.lichessold.util.Log;

/**
 * 桌面版的信任锚加载。对应 Android 版的 {@code org.lichessold.platform.AndroidTrust}
 * （那边从 assets/ 读，这边从 jar 内资源或磁盘上的 assets/ 读）。
 *
 * 查找顺序：
 *   1) jar 同级的 assets/cacerts.pem  —— 用户想换根证书包，直接替换文件即可，不用重打包
 *   2) jar 内置资源 /assets/cacerts.pem —— 单文件 JAR 的默认来源
 *
 * 证书解析和链校验走的是同一套纯 Java 代码（net/TrustAnchors + net/CertVerifier），
 * 桌面版没有任何特殊处理 —— 这是本项目"桌面验证 == 手机行为"的前提。
 */
public final class DesktopTrust {

    private static final String ASSET_NAME = "cacerts.pem";

    private static TrustAnchors cached;
    private static String error;
    private static String source = "";

    private DesktopTrust() {
    }

    public static synchronized TrustAnchors get() {
        if (cached != null) {
            return cached;
        }
        // 1) 磁盘上的 assets/cacerts.pem
        File onDisk = new File(new File(DesktopPaths.appDir(), "assets"), ASSET_NAME);
        if (onDisk.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(onDisk);
                cached = TrustAnchors.fromPem(in);
                source = onDisk.getAbsolutePath();
                error = null;
                Log.i("Trust", "信任锚加载完成（外部文件），共 " + cached.size() + " 个根证书");
                return cached;
            } catch (Throwable t) {
                error = "外部文件解析失败: " + t;
                Log.w("Trust", error);
            } finally {
                closeQuietly(in);
            }
        }

        // 2) jar 内置资源
        InputStream in = null;
        try {
            in = DesktopTrust.class.getResourceAsStream("/assets/" + ASSET_NAME);
            if (in == null) {
                error = "jar 里找不到 assets/" + ASSET_NAME;
                Log.e("Trust", error);
                return null;
            }
            cached = TrustAnchors.fromPem(in);
            source = "jar:/assets/" + ASSET_NAME;
            error = null;
            Log.i("Trust", "信任锚加载完成（jar 内置），共 " + cached.size() + " 个根证书");
            return cached;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            Log.e("Trust", "信任锚加载失败: " + error);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String lastError() {
        return error;
    }

    public static String source() {
        return source;
    }

    public static int anchorCount() {
        return cached == null ? 0 : cached.size();
    }
}
