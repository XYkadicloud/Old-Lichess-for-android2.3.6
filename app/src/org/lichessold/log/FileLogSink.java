package org.lichessold.log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.LinkedList;

import org.lichessold.util.LogSink;

/**
 * 日志落盘。后台守护线程写 /sdcard/LichessOld/log.txt，超过 512KB 轮转到 log.1.txt。
 *
 * 为什么单独开线程：主线程在走子/渲染时写文件会卡顿；而且 SD 卡写入速度
 * 不稳定（尤其廉价卡），同步写会让界面一顿一顿的。
 */
public final class FileLogSink implements LogSink {

    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final int MAX_QUEUE = 3000;

    private final File dir;
    private final File logFile;
    private final LinkedList<String> queue = new LinkedList<String>();
    private final Object lock = new Object();
    private volatile boolean running = true;

    public FileLogSink(File dir) {
        this.dir = dir;
        this.logFile = new File(dir, "log.txt");
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "lichessold-log");
        t.setDaemon(true);
        t.start();
    }

    public File file() {
        return logFile;
    }

    public void write(String line) {
        if (!running) {
            return;
        }
        synchronized (lock) {
            if (queue.size() > MAX_QUEUE) {
                queue.removeFirst();
            }
            queue.addLast(line);
            lock.notifyAll();
        }
    }

    /** 等队列排空，最多等 timeoutMs 毫秒。 */
    public void flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                if (queue.isEmpty()) {
                    return;
                }
            }
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void loop() {
        BufferedWriter w = null;
        while (true) {
            String line;
            synchronized (lock) {
                while (queue.isEmpty() && running) {
                    try {
                        lock.wait(1000L);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (queue.isEmpty()) {
                    if (!running) {
                        break;
                    }
                    continue;
                }
                line = queue.removeFirst();
            }
            try {
                if (w == null) {
                    w = open();
                }
                if (w != null) {
                    w.write(line);
                    w.write('\n');
                    w.flush();
                    if (logFile.length() > MAX_FILE_BYTES) {
                        try {
                            w.close();
                        } catch (IOException ignored) {
                        }
                        w = null;
                        rotate();
                    }
                }
            } catch (IOException e) {
                closeQuietly(w);
                w = null;
            } catch (Throwable t) {
                closeQuietly(w);
                w = null;
            }
        }
        closeQuietly(w);
    }

    private BufferedWriter open() {
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(logFile, true);
            return new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"), 4096);
        } catch (Throwable t) {
            return null;
        }
    }

    private void rotate() {
        try {
            File old = new File(dir, "log.1.txt");
            if (old.exists()) {
                old.delete();
            }
            logFile.renameTo(old);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(BufferedWriter w) {
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
    }
}
