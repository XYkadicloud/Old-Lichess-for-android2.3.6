package org.lichessold.desktop;

import javax.swing.SwingUtilities;

import org.lichessold.net.LichessApi;
import org.lichessold.net.NetException;
import org.lichessold.util.Log;

/**
 * 桌面版异步工具。对应 Android 版的 {@code org.lichessold.platform.Async}。
 *
 * Android 那边是 Handler + Looper；这边是「后台线程跑任务，结果回到 EDT」。
 * 语义完全一致：Job 在后台跑，Done 保证在 UI 线程回调。
 *
 * 为什么不用 SwingWorker：SwingWorker 的泛型和生命周期对这里的用法太重了，
 * 而且它的 cancel 语义容易和"页面已经关掉"混在一起。这里只要一个线程 + invokeLater。
 */
public final class DesktopAsync {

    public interface Job<T> {
        T run() throws Throwable;
    }

    public interface Done<T> {
        void done(T value, Throwable error);
    }

    private DesktopAsync() {
    }

    public static <T> void run(final String tag, final Job<T> job, final Done<T> done) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                T value = null;
                Throwable error = null;
                try {
                    value = job.run();
                } catch (Throwable e) {
                    error = e;
                }
                final T fv = value;
                final Throwable fe = error;
                post(new Runnable() {
                    public void run() {
                        try {
                            done.done(fv, fe);
                        } catch (Throwable e) {
                            Log.e("Async", tag + " 回调异常", e);
                        }
                    }
                });
            }
        }, "lichessold-" + tag);
        t.setDaemon(true);
        t.start();
    }

    /** 后台跑，忽略结果。 */
    @SuppressWarnings("unchecked")
    public static void fire(final String tag, final Job<?> job) {
        run(tag, (Job<Object>) job, new Done<Object>() {
            public void done(Object value, Throwable error) {
                if (error != null) {
                    Log.w("Async", tag + " 后台任务出错: " + error);
                }
            }
        });
    }

    /** 把一段代码丢到 UI 线程执行。 */
    public static void post(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 后台守护线程。长连接流（事件流/对局流/观战流）用它。 */
    public static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, "lichessold-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 便捷：后台线程里直接拿全局 API 客户端。 */
    public static LichessApi api() throws NetException {
        return DesktopNet.api();
    }
}
