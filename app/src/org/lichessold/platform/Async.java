package org.lichessold.platform;

import android.os.Handler;
import android.os.Looper;

import org.lichessold.util.Log;

/**
 * 极简异步工具：把耗时任务丢到后台线程，结果回主线程。
 *
 * 为什么不用 AsyncTask：AsyncTask 在 API 10 上有已知的线程池行为差异，
 * 而且它和 Activity 生命周期耦合容易泄漏。这里只要一个 Handler，行为完全可控。
 */
public final class Async {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Job<T> {
        T run() throws Throwable;
    }

    public interface Done<T> {
        void done(T value, Throwable error);
    }

    private Async() {
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
                MAIN.post(new Runnable() {
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

    public static void post(Runnable r) {
        MAIN.post(r);
    }
}
