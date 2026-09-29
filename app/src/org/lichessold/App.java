package org.lichessold;

import android.app.Application;
import android.os.Build;

import org.lichessold.log.CrashHandler;
import org.lichessold.log.Logging;
import org.lichessold.log.Storage;
import org.lichessold.log.Version;
import org.lichessold.store.Prefs;
import org.lichessold.util.Log;

/**
 * 应用入口。只做：起日志、装崩溃捕获、记录环境信息。
 * 不做任何网络或耗时操作，保证冷启动在 GT-S5360 上够快。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Logging.init(this);
        Log.i("App", "================ lichess for Android 2.3.6 ================");
        Log.i("App", "version   = " + Version.name(this) + " (code " + Version.code(this) + ")");
        Log.i("App", "buildTime = " + Version.buildTime());
        Log.i("App", "device    = " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.i("App", "android   = " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.i("App", "heapMax   = " + (Runtime.getRuntime().maxMemory() / 1024L) + " KB");
        Log.i("App", "vmName    = " + System.getProperty("java.vm.name"));
        Log.i("App", "cpuAbi    = " + abi());

        if (Storage.isExternalAvailable()) {
            Log.i("App", "external storage = " + Storage.getExternalLogDir());
        } else {
            Log.w("App", "external storage NOT available, falling back to internal");
        }
        Log.i("App", "log dir   = " + Logging.logDir());

        CrashHandler.install(this);

        Prefs prefs = Prefs.get(this);
        Log.i("App", "token saved = " + (prefs.hasToken() ? "yes" : "no")
                + ", debug = " + prefs.isDebugMode()
                + ", aiLevel = " + prefs.getAiLevel());

        Log.i("App", "startup complete");
    }

    private static String abi() {
        try {
            return Build.CPU_ABI;
        } catch (Throwable t) {
            return "?";
        }
    }
}
