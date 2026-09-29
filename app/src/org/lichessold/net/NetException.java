package org.lichessold.net;

import java.io.IOException;

/**
 * 网络层异常。带一个"失败在哪一步"的标记，供网络诊断页逐步显示。
 */
public class NetException extends IOException {

    private static final long serialVersionUID = 1L;

    /** 失败阶段，供诊断页显示。 */
    public static final int STAGE_DNS = 1;
    public static final int STAGE_TCP = 2;
    public static final int STAGE_TLS = 3;
    public static final int STAGE_CERT = 4;
    public static final int STAGE_HTTP = 5;

    private final int stage;

    public NetException(int stage, String message) {
        super(message);
        this.stage = stage;
    }

    public NetException(int stage, String message, Throwable cause) {
        super(message + " [" + cause.getClass().getName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()) + "]");
        this.stage = stage;
    }

    public int getStage() {
        return stage;
    }

    public static String stageName(int stage) {
        switch (stage) {
            case STAGE_DNS:
                return "DNS 解析";
            case STAGE_TCP:
                return "TCP 连接";
            case STAGE_TLS:
                return "TLS 握手";
            case STAGE_CERT:
                return "证书校验";
            case STAGE_HTTP:
                return "HTTP 请求";
            default:
                return "未知阶段";
        }
    }
}
