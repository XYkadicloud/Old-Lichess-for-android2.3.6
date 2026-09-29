package org.lichessold.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（纯 Java，无 Android 依赖）。
 *
 * 为什么不用 android 自带的 org.json：
 *  1. org.json 在 Android 上是 fork，API 和 Maven 上的 org.json:json 不一样，
 *     导致核心网络层没法在桌面 JVM 上跑测试；
 *  2. 自己写一份，行为完全可控，也不给 dex 增加依赖。
 *
 * 设计取舍：所有取值方法都提供默认值版本，调用方不需要到处判空。
 * 解析失败抛 JsonException，绝不返回半截数据。
 */
public final class Json {

    public static final int T_NULL = 0;
    public static final int T_BOOL = 1;
    public static final int T_NUM = 2;
    public static final int T_STR = 3;
    public static final int T_ARR = 4;
    public static final int T_OBJ = 5;

    private static final Json NULL = new Json(T_NULL, null);
    private static final Json TRUE = new Json(T_BOOL, Boolean.TRUE);
    private static final Json FALSE = new Json(T_BOOL, Boolean.FALSE);
    private static final Json EMPTY_OBJ = new Json(T_OBJ, new LinkedHashMap<String, Json>());
    private static final Json EMPTY_ARR = new Json(T_ARR, new ArrayList<Json>());

    private final int type;
    private final Object value;

    private Json(int type, Object value) {
        this.type = type;
        this.value = value;
    }

    // ------------------------------------------------------------- factories

    public static Json of(String s) {
        return s == null ? NULL : new Json(T_STR, s);
    }

    public static Json of(long v) {
        return new Json(T_NUM, Double.valueOf((double) v));
    }

    public static Json of(boolean b) {
        return b ? TRUE : FALSE;
    }

    public static Json emptyObject() {
        return EMPTY_OBJ;
    }

    public static Json emptyArray() {
        return EMPTY_ARR;
    }

    /** 解析一个 JSON 文档。允许前后有空白。 */
    public static Json parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("input is null");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Json v = p.parseValue(0);
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("trailing content at offset " + p.pos);
        }
        return v;
    }

    /** 解析失败时返回 def，不抛异常。流式响应里偶尔有坏行时用这个。 */
    public static Json parseQuietly(String text, Json def) {
        try {
            return parse(text);
        } catch (Throwable t) {
            return def == null ? NULL : def;
        }
    }

    // ------------------------------------------------------------------ type

    public int type() {
        return type;
    }

    public boolean isNull() {
        return type == T_NULL;
    }

    public boolean isObject() {
        return type == T_OBJ;
    }

    public boolean isArray() {
        return type == T_ARR;
    }

    public boolean isString() {
        return type == T_STR;
    }

    public boolean isNumber() {
        return type == T_NUM;
    }

    public boolean isBoolean() {
        return type == T_BOOL;
    }

    /** 对象成员数 / 数组长度；其它类型返回 0。 */
    public int size() {
        if (type == T_OBJ) {
            return ((Map<?, ?>) value).size();
        }
        if (type == T_ARR) {
            return ((List<?>) value).size();
        }
        return 0;
    }

    public boolean has(String key) {
        return type == T_OBJ && ((Map<?, ?>) value).containsKey(key);
    }

    // ----------------------------------------------------------- object access

    /** 取对象成员；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    public Json get(String key) {
        if (type != T_OBJ || key == null) {
            return null;
        }
        return ((Map<String, Json>) value).get(key);
    }

    /** 取对象成员；不存在返回空 Json（isNull() 为 true），方便链式取值。 */
    public Json opt(String key) {
        Json j = get(key);
        return j == null ? NULL : j;
    }

    public String[] keys() {
        if (type != T_OBJ) {
            return new String[0];
        }
        @SuppressWarnings("unchecked")
        Map<String, Json> m = (Map<String, Json>) value;
        return m.keySet().toArray(new String[m.size()]);
    }

    // ------------------------------------------------------------ array access

    /** 取数组元素；越界返回 null。 */
    @SuppressWarnings("unchecked")
    public Json at(int index) {
        if (type != T_ARR) {
            return null;
        }
        List<Json> l = (List<Json>) value;
        if (index < 0 || index >= l.size()) {
            return null;
        }
        return l.get(index);
    }

    public Json optAt(int index) {
        Json j = at(index);
        return j == null ? NULL : j;
    }

    /** 数组转 String[]；非数组返回空数组。 */
    public String[] asStringArray() {
        if (type != T_ARR) {
            return new String[0];
        }
        List<?> l = (List<?>) value;
        String[] out = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            Object o = l.get(i);
            out[i] = o instanceof Json ? ((Json) o).asString("") : String.valueOf(o);
        }
        return out;
    }

    // ------------------------------------------------------------ conversions

    public String asString(String def) {
        if (type == T_STR) {
            return (String) value;
        }
        if (type == T_NUM) {
            double d = ((Double) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (type == T_BOOL) {
            return String.valueOf(value);
        }
        return def;
    }

    public int asInt(int def) {
        if (type == T_NUM) {
            return (int) ((Double) value).doubleValue();
        }
        if (type == T_STR) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        if (type == T_BOOL) {
            return ((Boolean) value).booleanValue() ? 1 : 0;
        }
        return def;
    }

    public long asLong(long def) {
        if (type == T_NUM) {
            return (long) ((Double) value).doubleValue();
        }
        if (type == T_STR) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public double asDouble(double def) {
        if (type == T_NUM) {
            return ((Double) value).doubleValue();
        }
        if (type == T_STR) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public boolean asBool(boolean def) {
        if (type == T_BOOL) {
            return ((Boolean) value).booleanValue();
        }
        if (type == T_NUM) {
            return ((Double) value).doubleValue() != 0d;
        }
        if (type == T_STR) {
            String s = (String) value;
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return def;
    }

    // ------------------------------------------------- 便捷组合取值（最常用）

    public String str(String key, String def) {
        Json j = get(key);
        return j == null ? def : j.asString(def);
    }

    public int i(String key, int def) {
        Json j = get(key);
        return j == null ? def : j.asInt(def);
    }

    public long l(String key, long def) {
        Json j = get(key);
        return j == null ? def : j.asLong(def);
    }

    public double d(String key, double def) {
        Json j = get(key);
        return j == null ? def : j.asDouble(def);
    }

    public boolean b(String key, boolean def) {
        Json j = get(key);
        return j == null ? def : j.asBool(def);
    }

    /** 取子对象；不是对象就返回空对象（不会返回 null）。 */
    public Json obj(String key) {
        Json j = get(key);
        return (j != null && j.isObject()) ? j : EMPTY_OBJ;
    }

    /** 取子数组；不是数组就返回空数组（不会返回 null）。 */
    public Json arr(String key) {
        Json j = get(key);
        return (j != null && j.isArray()) ? j : EMPTY_ARR;
    }

    // --------------------------------------------------------------- output

    /** 重新序列化，仅用于调试日志。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        write(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void write(StringBuilder sb) {
        switch (type) {
            case T_NULL:
                sb.append("null");
                break;
            case T_BOOL:
                sb.append(value);
                break;
            case T_NUM: {
                double d = ((Double) value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)
                        && d >= -9.007199254740992E15 && d <= 9.007199254740992E15) {
                    sb.append((long) d);
                } else {
                    sb.append(d);
                }
                break;
            }
            case T_STR:
                writeString(sb, (String) value);
                break;
            case T_ARR: {
                sb.append('[');
                List<Json> l = (List<Json>) value;
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    l.get(i).write(sb);
                }
                sb.append(']');
                break;
            }
            case T_OBJ: {
                sb.append('{');
                Map<String, Json> m = (Map<String, Json>) value;
                boolean first = true;
                for (Map.Entry<String, Json> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, e.getKey());
                    sb.append(':');
                    e.getValue().write(sb);
                }
                sb.append('}');
                break;
            }
            default:
                sb.append("null");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String h = Integer.toHexString(c);
                        for (int k = h.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(h);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // -------------------------------------------------------------- parser

    private static final class Parser {

        private final String s;
        private int pos;
        private int depth;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() throws JsonException {
            if (pos >= s.length()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        Json parseValue(int d) throws JsonException {
            if (d > 64) {
                throw new JsonException("nesting too deep");
            }
            skipWs();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject(d);
                case '[':
                    return parseArray(d);
                case '"':
                    return Json.of(parseString());
                case 't':
                    expect("true");
                    return TRUE;
                case 'f':
                    expect("false");
                    return FALSE;
                case 'n':
                    expect("null");
                    return NULL;
                default:
                    return parseNumber();
            }
        }

        private void expect(String word) throws JsonException {
            if (s.regionMatches(pos, word, 0, word.length())) {
                pos += word.length();
            } else {
                throw new JsonException("expected " + word + " at offset " + pos);
            }
        }

        private Json parseObject(int d) throws JsonException {
            pos++; // '{'
            LinkedHashMap<String, Json> map = new LinkedHashMap<String, Json>();
            skipWs();
            if (!eof() && peek() == '}') {
                pos++;
                return new Json(T_OBJ, map);
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("expected key string at offset " + pos);
                }
                String key = parseString();
                skipWs();
                if (peek() != ':') {
                    throw new JsonException("expected ':' at offset " + pos);
                }
                pos++;
                Json v = parseValue(d + 1);
                map.put(key, v);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return new Json(T_OBJ, map);
                }
                throw new JsonException("expected ',' or '}' at offset " + pos);
            }
        }

        private Json parseArray(int d) throws JsonException {
            pos++; // '['
            ArrayList<Json> list = new ArrayList<Json>();
            skipWs();
            if (!eof() && peek() == ']') {
                pos++;
                return new Json(T_ARR, list);
            }
            while (true) {
                list.add(parseValue(d + 1));
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return new Json(T_ARR, list);
                }
                throw new JsonException("expected ',' or ']' at offset " + pos);
            }
        }

        private String parseString() throws JsonException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder(32);
            while (true) {
                if (pos >= s.length()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new JsonException("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u': {
                        if (pos + 4 > s.length()) {
                            throw new JsonException("bad \\u escape");
                        }
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(s.charAt(pos + k), 16);
                            if (digit < 0) {
                                throw new JsonException("bad \\u escape at offset " + (pos + k));
                            }
                            cp = (cp << 4) | digit;
                        }
                        pos += 4;
                        sb.append((char) cp);
                        break;
                    }
                    default:
                        throw new JsonException("bad escape \\" + e);
                }
            }
        }

        private Json parseNumber() throws JsonException {
            int start = pos;
            if (!eof() && (peek() == '-' || peek() == '+')) {
                pos++;
            }
            boolean digits = false;
            while (!eof()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    if (c >= '0' && c <= '9') {
                        digits = true;
                    }
                    pos++;
                } else {
                    break;
                }
            }
            if (!digits) {
                throw new JsonException("not a number at offset " + start);
            }
            String raw = s.substring(start, pos);
            try {
                return new Json(T_NUM, Double.valueOf(Double.parseDouble(raw)));
            } catch (NumberFormatException e) {
                throw new JsonException("bad number '" + raw + "'");
            }
        }
    }
}
