package org.lichessold.platform;

import java.io.IOException;
import java.io.InputStream;

import android.content.Context;

import org.lichessold.net.TrustAnchors;

/**
 * 从 assets/cacerts.pem 加载信任锚。
 *
 * 放在 platform 包里而不是 net 包里，是因为它依赖 android.content.Context，
 * 而 net 包必须保持纯 Java 才能在桌面 JVM 上跑真实网络测试。
 */
public final class AndroidTrust {

    private static TrustAnchors cached;
    private static String error;

    private AndroidTrust() {
    }

    /** 加载信任锚；失败返回 null 并把原因记在 lastError()。 */
    public static synchronized TrustAnchors get(Context ctx) {
        if (cached != null) {
            return cached;
        }
        InputStream in = null;
        try {
            in = ctx.getAssets().open("cacerts.pem");
            cached = TrustAnchors.fromPem(in);
            error = null;
            return cached;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String lastError() {
        return error;
    }

    public static int anchorCount() {
        return cached == null ? 0 : cached.size();
    }
}
