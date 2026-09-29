package org.lichessold.log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.content.Context;
import android.os.Environment;

/**
 * 存储路径解析。
 *
 * 优先用外置存储（GT-S5360 上是 microSD，挂载点通常是 /mnt/sdcard），
 * 不可用时退回 App 内部私有目录 —— 保证日志系统不会因为存储问题把 App 搞崩。
 * 每次解析都做一次真实写入测试，不靠 canWrite() 猜。
 */
public final class Storage {

    public static final String DIR_NAME = "LichessOld";

    private Storage() {
    }

    public static File getLogDir(Context ctx) {
        File external = tryExternal();
        if (external != null) {
            return external;
        }
        return tryInternal(ctx);
    }

    public static File getExternalLogDir() {
        File base = externalBase();
        return base == null ? null : new File(base, DIR_NAME);
    }

    public static boolean isExternalAvailable() {
        return externalBase() != null;
    }

    /** dir 是否位于外置存储上。 */
    public static boolean isExternal(File dir) {
        if (dir == null) {
            return false;
        }
        File base = externalBase();
        if (base == null) {
            return false;
        }
        try {
            return dir.getCanonicalPath().startsWith(base.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static File tryExternal() {
        File base = externalBase();
        if (base == null) {
            return null;
        }
        File dir = new File(base, DIR_NAME);
        return ensureWritable(dir) ? dir : null;
    }

    private static File tryInternal(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        return ensureWritable(dir) ? dir : null;
    }

    private static File externalBase() {
        try {
            String state = Environment.getExternalStorageState();
            if (!Environment.MEDIA_MOUNTED.equals(state)) {
                return null;
            }
            return Environment.getExternalStorageDirectory();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 建目录并做一次真实写入测试。 */
    private static boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            if (!dir.isDirectory()) {
                return false;
            }
            File probe = new File(dir, ".probe");
            FileOutputStream fos = new FileOutputStream(probe, false);
            fos.write(new byte[] { 0x4F, 0x4B });
            fos.flush();
            fos.close();
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
