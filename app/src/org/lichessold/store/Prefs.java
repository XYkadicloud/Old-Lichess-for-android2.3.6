package org.lichessold.store;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置项封装。令牌只存在本机，任何写日志的地方都不允许直接引用 token 字段。
 */
public final class Prefs {

    private static final String NAME = "lichessold";

    private static final String KEY_TOKEN = "api_token";
    private static final String KEY_USERNAME = "account_username";
    private static final String KEY_DEBUG = "debug_mode";
    private static final String KEY_AI_LEVEL = "ai_level";
    private static final String KEY_PUZZLE_ANGLE = "puzzle_angle";
    private static final String KEY_FLIP_BOARD = "flip_board";
    private static final String KEY_RATED_DEFAULT = "rated_default";

    public static final int DEFAULT_AI_LEVEL = 3;

    private final SharedPreferences sp;

    private Prefs(SharedPreferences sp) {
        this.sp = sp;
    }

    public static Prefs get(Context ctx) {
        return new Prefs(ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE));
    }

    // ---------------------------------------------------------------- 令牌

    public String getToken() {
        String t = sp.getString(KEY_TOKEN, null);
        return t == null ? "" : t;
    }

    public void setToken(String token) {
        sp.edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).commit();
    }

    public boolean hasToken() {
        return getToken().length() > 0;
    }

    public void clearToken() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).commit();
    }

    // -------------------------------------------------------------- 用户名

    public String getUsername() {
        String u = sp.getString(KEY_USERNAME, null);
        return u == null ? "" : u;
    }

    public void setUsername(String name) {
        sp.edit().putString(KEY_USERNAME, name == null ? "" : name).commit();
    }

    // ------------------------------------------------------------ 调试模式

    public boolean isDebugMode() {
        return sp.getBoolean(KEY_DEBUG, false);
    }

    public void setDebugMode(boolean enabled) {
        sp.edit().putBoolean(KEY_DEBUG, enabled).commit();
    }

    // ------------------------------------------------------------- AI 等级

    public int getAiLevel() {
        int v = sp.getInt(KEY_AI_LEVEL, DEFAULT_AI_LEVEL);
        if (v < 1) {
            return 1;
        }
        if (v > 8) {
            return 8;
        }
        return v;
    }

    public void setAiLevel(int level) {
        sp.edit().putInt(KEY_AI_LEVEL, Math.max(1, Math.min(8, level))).commit();
    }

    // --------------------------------------------------------------- 谜题

    public String getPuzzleAngle() {
        String s = sp.getString(KEY_PUZZLE_ANGLE, "mix");
        return s == null || s.length() == 0 ? "mix" : s;
    }

    public void setPuzzleAngle(String angle) {
        sp.edit().putString(KEY_PUZZLE_ANGLE, angle == null ? "mix" : angle).commit();
    }

    // --------------------------------------------------------------- 棋盘

    public boolean isFlipBoard() {
        return sp.getBoolean(KEY_FLIP_BOARD, false);
    }

    public void setFlipBoard(boolean flip) {
        sp.edit().putBoolean(KEY_FLIP_BOARD, flip).commit();
    }

    // ------------------------------------------------------------ 排位/休闲

    /**
     * 上次选的排位/休闲，下次默认勾同一个。
     * true = 排位赛（结果加减等级分），false = 休闲赛（不计分）。
     */
    public boolean isRatedDefault() {
        return sp.getBoolean(KEY_RATED_DEFAULT, false);
    }

    public void setRatedDefault(boolean rated) {
        sp.edit().putBoolean(KEY_RATED_DEFAULT, rated).commit();
    }
}
