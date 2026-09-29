package org.lichessold.platform;

import android.content.Context;

import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.net.TrustAnchors;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 全局共享的 Lichess API 客户端。
 *
 * 整个 App 只用一条 HTTP 连接池 + 一个令牌。多开客户端会浪费握手
 * （ARM11 上一次 TLS 握手要好几秒），也容易撞上 Lichess 的速率限制。
 */
public final class Net {

    private static LichessApi api;
    private static boolean tokenLoaded;

    private Net() {
    }

    public static synchronized LichessApi api(Context ctx) throws NetException {
        if (api == null) {
            TrustAnchors anchors = AndroidTrust.get(ctx);
            if (anchors == null) {
                throw new NetException(NetException.STAGE_CERT,
                        "内置根证书加载失败: " + AndroidTrust.lastError());
            }
            Log.i("Net", "信任锚加载完成，共 " + anchors.size() + " 个根证书");
            api = new LichessApi(anchors);
        }
        if (!tokenLoaded) {
            applyToken(ctx);
        }
        return api;
    }

    /** 令牌变了（用户在设置里保存）之后调用。 */
    public static synchronized void reloadToken(Context ctx) {
        applyToken(ctx);
    }

    private static void applyToken(Context ctx) {
        if (api == null) {
            return;
        }
        String token = Prefs.get(ctx).getToken();
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
