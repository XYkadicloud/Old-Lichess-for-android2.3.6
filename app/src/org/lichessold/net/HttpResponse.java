package org.lichessold.net;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 响应。纯数据结构。
 */
public final class HttpResponse {

    public final int status;
    public final String reason;
    public final byte[] body;
    private final Map<String, String> headers;

    public HttpResponse(int status, String reason, Map<String, String> headers, byte[] body) {
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        this.body = body == null ? new byte[0] : body;
    }

    /** 头字段名大小写不敏感。 */
    public String header(String name) {
        if (name == null) {
            return null;
        }
        return headers.get(name.toLowerCase(Locale.US));
    }

    public String[] headerNames() {
        List<String> l = new ArrayList<String>(headers.keySet());
        return l.toArray(new String[l.size()]);
    }

    public String text() {
        try {
            return new String(body, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(body);
        }
    }

    public boolean isOk() {
        return status >= 200 && status < 300;
    }

    /** 错误响应的简短描述，用于给用户看的提示。 */
    public String errorSummary() {
        String t = text();
        if (t.length() > 300) {
            t = t.substring(0, 300) + "...";
        }
        return "HTTP " + status + " " + reason + (t.length() == 0 ? "" : "\n" + t);
    }

    public Map<String, String> headers() {
        return headers;
    }
}
