package org.lichessold.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import org.lichessold.util.Log;

/**
 * 桌面版设置项。对应 Android 版的 {@code org.lichessold.store.Prefs}
 * （那边是 SharedPreferences，这边是 data/settings.properties）。
 *
 * 键名与 Android 版保持一致，方便两边对照排查。
 *
 * ⚠️ 令牌只存本机、只用于发 Authorization 头，日志里会被 util.Log 脱敏。
 */
public final class DesktopPrefs {

    private static final String FILE = "settings.properties";

    private static final String KEY_TOKEN = "api_token";
    private static final String KEY_USERNAME = "account_username";
    private static final String KEY_DEBUG = "debug_mode";
    private static final String KEY_AI_LEVEL = "ai_level";
    private static final String KEY_PUZZLE_ANGLE = "puzzle_angle";
    private static final String KEY_FLIP_BOARD = "flip_board";
    private static final String KEY_RATED_DEFAULT = "rated_default";

    public static final int DEFAULT_AI_LEVEL = 3;

    private static DesktopPrefs instance;

    private final Properties props = new Properties();
    private final File file;

    private DesktopPrefs(File file) {
        this.file = file;
        load();
    }

    public static synchronized DesktopPrefs get() {
        if (instance == null) {
            instance = new DesktopPrefs(DesktopPaths.file(FILE));
        }
        return instance;
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
        } catch (Throwable t) {
            Log.w("Prefs", "读取设置失败（用默认值继续）: " + t);
        } finally {
            closeQuietly(in);
        }
    }

    /** 立刻落盘。设置项很少，每次写全量，不用做增量。 */
    private void save() {
        OutputStream out = null;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            out = new FileOutputStream(file);
            props.store(out, "lichess desktop settings");
        } catch (Throwable t) {
            Log.w("Prefs", "保存设置失败: " + t);
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private String str(String key, String def) {
        String v = props.getProperty(key);
        return v == null ? def : v;
    }

    private void put(String key, String value) {
        props.setProperty(key, value == null ? "" : value);
        save();
    }

    // ---------------------------------------------------------------- 令牌

    public String getToken() {
        return str(KEY_TOKEN, "").trim();
    }

    public void setToken(String token) {
        put(KEY_TOKEN, token == null ? "" : token.trim());
    }

    public boolean hasToken() {
        return getToken().length() > 0;
    }

    public void clearToken() {
        props.remove(KEY_TOKEN);
        props.remove(KEY_USERNAME);
        save();
    }

    // -------------------------------------------------------------- 用户名

    public String getUsername() {
        return str(KEY_USERNAME, "");
    }

    public void setUsername(String name) {
        put(KEY_USERNAME, name == null ? "" : name);
    }

    // ------------------------------------------------------------ 调试模式

    public boolean isDebugMode() {
        return Boolean.parseBoolean(str(KEY_DEBUG, "false"));
    }

    public void setDebugMode(boolean enabled) {
        put(KEY_DEBUG, String.valueOf(enabled));
    }

    // ------------------------------------------------------------- AI 等级

    public int getAiLevel() {
        int v;
        try {
            v = Integer.parseInt(str(KEY_AI_LEVEL, String.valueOf(DEFAULT_AI_LEVEL)));
        } catch (NumberFormatException e) {
            v = DEFAULT_AI_LEVEL;
        }
        return Math.max(1, Math.min(8, v));
    }

    public void setAiLevel(int level) {
        put(KEY_AI_LEVEL, String.valueOf(Math.max(1, Math.min(8, level))));
    }

    // --------------------------------------------------------------- 谜题

    public String getPuzzleAngle() {
        String s = str(KEY_PUZZLE_ANGLE, "mix");
        return s.length() == 0 ? "mix" : s;
    }

    public void setPuzzleAngle(String angle) {
        put(KEY_PUZZLE_ANGLE, angle == null ? "mix" : angle);
    }

    // --------------------------------------------------------------- 棋盘

    public boolean isFlipBoard() {
        return Boolean.parseBoolean(str(KEY_FLIP_BOARD, "false"));
    }

    public void setFlipBoard(boolean flip) {
        put(KEY_FLIP_BOARD, String.valueOf(flip));
    }

    // ------------------------------------------------------------ 排位/休闲

    public boolean isRatedDefault() {
        return Boolean.parseBoolean(str(KEY_RATED_DEFAULT, "false"));
    }

    public void setRatedDefault(boolean rated) {
        put(KEY_RATED_DEFAULT, String.valueOf(rated));
    }
}
