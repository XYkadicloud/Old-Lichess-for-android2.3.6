package org.lichessold.util;

/**
 * 日志输出目标。纯 Java 接口，Android 层实现它把日志写到存储卡。
 * 这样 util/ json/ net/ chess/ 这些核心包完全不依赖 Android，可以在桌面 JVM 上直接跑测试。
 */
public interface LogSink {

    /** 写一行（不含换行符）。实现方自己决定缓冲与落盘。 */
    void write(String line);
}
