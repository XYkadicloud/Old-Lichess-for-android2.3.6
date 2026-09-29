package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import org.lichessold.util.Log;

/**
 * 运行日志。对应 Android 版的 {@code org.lichessold.ui.LogActivity}。
 *
 * 显示的是 util.Log 的内存环形缓冲（最近 600 行），不读文件 ——
 * 这样即使日志文件写入失败（磁盘满、权限不对）也能看到现场。
 * 令牌在进缓冲之前就已经被脱敏，所以这里看到的内容可以直接贴出来。
 */
public final class LogPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTextArea output = DesktopTheme.output();

    public LogPanel() {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.setBackground(DesktopTheme.BG);

        JButton refresh = DesktopTheme.smallPrimary("刷新");
        refresh.setPreferredSize(new Dimension(80, 30));
        refresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                reload();
            }
        });

        JButton save = DesktopTheme.smallButton("保存到文件");
        save.setPreferredSize(new Dimension(110, 30));
        save.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                save();
            }
        });

        JButton openDir = DesktopTheme.smallButton("打开日志目录");
        openDir.setPreferredSize(new Dimension(120, 30));
        openDir.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.openPath(DesktopLog.logDir());
            }
        });

        JButton clear = DesktopTheme.smallButton("清空");
        clear.setPreferredSize(new Dimension(80, 30));
        clear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopLog.clear();
                reload();
            }
        });

        JButton back = DesktopTheme.smallButton("返回");
        back.setPreferredSize(new Dimension(80, 30));
        back.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.back();
            }
        });

        top.add(refresh);
        top.add(save);
        top.add(openDir);
        top.add(clear);
        top.add(back);

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(DesktopTheme.BG);
        north.add(DesktopTheme.header("运行日志"), BorderLayout.NORTH);
        north.add(top, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(DesktopTheme.scroll(output), BorderLayout.CENTER);
        reload();
    }

    private void reload() {
        String text = Log.getRecentText();
        if (text.length() == 0) {
            text = "（还没有日志）\n\n日志文件: "
                    + (DesktopLog.logFile() == null ? "不可用"
                    : DesktopLog.logFile().getAbsolutePath());
        }
        output.setText(text);
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void save() {
        File f = new File(DesktopPaths.dataDir(), "log-snapshot-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt");
        OutputStreamWriter w = null;
        try {
            FileOutputStream fos = new FileOutputStream(f, false);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(Log.getRecentText());
            w.flush();
            DesktopApp.toast(this, "已保存到\n" + f.getAbsolutePath());
        } catch (Throwable e) {
            DesktopApp.toast(this, "保存失败: " + e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
