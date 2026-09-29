package org.lichessold.desktop;

import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.net.TrustAnchors;
import org.lichessold.util.Log;

/**
 * 全局共享的 Lichess API 客户端。对应 Android 版的 {@code org.lichessold.platform.Net}。
 *
 * 整个程序只用一条连接池 + 一个令牌 —— 多开客户端会浪费 TLS 握手，
 * 也更容易撞上 Lichess 的速率限制（429 要等 60 秒）。
 */
public final class DesktopNet {

    /** 桌面版的 User-Agent。Android 版是 LichessOld/0.3.0 (Android 2.3.6; ...)。 */
    public static final String USER_AGENT =
            "LichessOldDesktop/" + DesktopVersion.VERSION + " (Java; +https://lichess.org)";

    private static LichessApi api;
    private static boolean tokenLoaded;

    private DesktopNet() {
    }

    public static synchronized LichessApi api() throws NetException {
        if (api == null) {
            TrustAnchors anchors = DesktopTrust.get();
            if (anchors == null) {
                throw new NetException(NetException.STAGE_CERT,
                        "内置根证书加载失败: " + DesktopTrust.lastError());
            }
            api = new LichessApi(anchors);
            api.http().setUserAgent(USER_AGENT);
            Log.i("Net", "API 客户端已创建，User-Agent = " + USER_AGENT);
        }
        if (!tokenLoaded) {
            applyToken();
        }
        return api;
    }

    /** 令牌变了（用户在设置里保存）之后调用。 */
    public static synchronized void reloadToken() {
        applyToken();
    }

    private static void applyToken() {
        if (api == null) {
            return;
        }
        String token = DesktopPrefs.get().getToken();
        api.setToken(token);
        tokenLoaded = true;
        // 注意：这里只记长度，绝不记内容
        Log.i("Net", "令牌已装载，长度 " + token.length()
                + (token.length() == 0 ? "（未设置）" : ""));
    }

    public static synchronized void close() {
        if (api != null) {
            api.close();
            api = null;
            tokenLoaded = false;
        }
    }
}
