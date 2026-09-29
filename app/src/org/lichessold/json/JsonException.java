package org.lichessold.json;

/**
 * JSON 解析异常。
 */
public class JsonException extends Exception {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
