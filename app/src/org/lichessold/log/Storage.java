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

    // ------------------------------------------------------------ 对外写文件

    /** 棋谱目录名 —— 和日志分开放，用户在文件管理器里好找。 */
    public static final String PGN_DIR_NAME = "LichessOld/games";

    /**
     * 把一段文本存成 UTF-8 文件，返回文件对象。
     *
     * 路径优先外置存储（GT-S5360 上是 microSD，用户能直接用读卡器拷出来），
     * 不可用时退回内部私有目录，绝不因为存储问题抛异常打断调用方。
     *
     * @param name 文件名，会自动做一次安全化（去掉路径分隔符和 ..
     * @return 写入成功的文件；彻底失败时返回 null
     */
    public static File writeText(Context ctx, String name, String text) {
        File dir = tryExternalPgn();
        if (dir == null) {
            dir = tryInternalPgn(ctx);
        }
        if (dir == null) {
            return null;
        }
        File out = new File(dir, safeName(name));
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out, false);
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                    // 关不掉也没别的办法，文件可能已经写成功了
                }
            }
        }
    }

    private static File tryExternalPgn() {
        File base = externalBase();
        if (base == null) {
            return null;
        }
        File dir = new File(base, PGN_DIR_NAME);
        return ensureWritable(dir) ? dir : null;
    }

    private static File tryInternalPgn(Context ctx) {
        File dir = new File(ctx.getFilesDir(), PGN_DIR_NAME);
        return ensureWritable(dir) ? dir : null;
    }

    /**
     * 文件名安全化。
     * 用户提供的用户名里可能带奇怪字符（lichess 用户名是字母数字，
     * 但为了防御性编程还是过一遍），不能让它跳出目标目录。
     */
    static String safeName(String name) {
        if (name == null || name.length() == 0) {
            return "untitled.pgn";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        // 全是点会导致 ". .." 这类特殊名
        if (s.replace(".", "").length() == 0) {
            return "untitled.pgn";
        }
        return s;
    }
}
