package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

import org.lichessold.chess.Ai;
import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.util.Log;

/**
 * 设置页：令牌、测试连接、AI 等级、调试模式、清空令牌。
 * 对应 Android 版的 {@code org.lichessold.ui.SettingsActivity}。
 *
 * 令牌只存本机 data/settings.properties，只用于发 Authorization 头，
 * 日志里会被脱敏，不上传、不外发。
 */
public final class SettingsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel accountText;
    private final JPasswordField tokenInput;
    private final JButton levelButton;
    private boolean testing;

    public SettingsPanel() {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);
        add(DesktopTheme.header("设置"), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(DesktopTheme.BG);
        body.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        JLabel tip = DesktopTheme.label("Lichess API 令牌（只存在本机）", 12,
                DesktopTheme.TEXT_DIM);
        tip.setAlignmentX(LEFT_ALIGNMENT);
        body.add(tip);
        body.add(Box.createVerticalStrut(4));

        tokenInput = new JPasswordField();
        tokenInput.setFont(DesktopTheme.MONO);
        tokenInput.setForeground(DesktopTheme.TEXT_BRIGHT);
        tokenInput.setCaretColor(DesktopTheme.TEXT_BRIGHT);
        tokenInput.setBackground(new java.awt.Color(0x1D1B19));
        tokenInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DesktopTheme.BORDER),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        tokenInput.setEchoChar('*');
        String existing = DesktopPrefs.get().getToken();
        if (existing.length() > 0) {
            tokenInput.setText(existing);
        }
        tokenInput.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        tokenInput.setAlignmentX(LEFT_ALIGNMENT);
        body.add(tokenInput);
        body.add(Box.createVerticalStrut(8));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row1.setBackground(DesktopTheme.BG);
        JButton save = DesktopTheme.smallPrimary("保存令牌");
        save.setPreferredSize(new Dimension(100, 30));
        save.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                saveToken();
            }
        });
        JButton test = DesktopTheme.smallButton("测试连接");
        test.setPreferredSize(new Dimension(100, 30));
        test.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                testConnection();
            }
        });
        JButton clear = DesktopTheme.smallButton("清空令牌");
        clear.setPreferredSize(new Dimension(100, 30));
        clear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (DesktopApp.confirm(SettingsPanel.this, "清空令牌",
                        "确定要删除本机保存的令牌吗？")) {
                    DesktopPrefs.get().clearToken();
                    tokenInput.setText("");
                    accountText.setText("");
                    DesktopNet.reloadToken();
                    Log.i("Settings", "令牌已清空");
                    DesktopApp.toast(SettingsPanel.this, "已清空");
                    refreshAccountText();
                }
            }
        });
        row1.add(save);
        row1.add(test);
        row1.add(clear);
        row1.setAlignmentX(LEFT_ALIGNMENT);
        body.add(row1);
        body.add(Box.createVerticalStrut(8));

        accountText = DesktopTheme.label("", 13, DesktopTheme.GREEN_LIGHT);
        accountText.setAlignmentX(LEFT_ALIGNMENT);
        body.add(accountText);
        body.add(Box.createVerticalStrut(14));

        JLabel aiTip = DesktopTheme.label("离线 AI 强度", 12, DesktopTheme.TEXT_DIM);
        aiTip.setAlignmentX(LEFT_ALIGNMENT);
        body.add(aiTip);
        body.add(Box.createVerticalStrut(4));

        levelButton = DesktopTheme.smallButton("等级 " + DesktopPrefs.get().getAiLevel() + " / 8");
        levelButton.setPreferredSize(new Dimension(220, 30));
        levelButton.setMaximumSize(new Dimension(220, 30));
        levelButton.setAlignmentX(LEFT_ALIGNMENT);
        levelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                pickLevel();
            }
        });
        body.add(levelButton);
        body.add(Box.createVerticalStrut(12));

        final JCheckBox debug = new JCheckBox("调试模式（记录更多日志）");
        debug.setFont(DesktopTheme.ui(13));
        debug.setForeground(DesktopTheme.TEXT);
        debug.setBackground(DesktopTheme.BG);
        debug.setFocusPainted(false);
        debug.setSelected(DesktopPrefs.get().isDebugMode());
        debug.setAlignmentX(LEFT_ALIGNMENT);
        debug.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopPrefs.get().setDebugMode(debug.isSelected());
                Log.setDebugEnabled(debug.isSelected());
                Log.i("Settings", "调试模式 = " + debug.isSelected());
            }
        });
        body.add(debug);
        body.add(Box.createVerticalStrut(14));

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row2.setBackground(DesktopTheme.BG);
        JButton diag = DesktopTheme.smallButton("网络诊断");
        diag.setPreferredSize(new Dimension(100, 30));
        diag.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.openDiag();
            }
        });
        JButton logBtn = DesktopTheme.smallButton("运行日志");
        logBtn.setPreferredSize(new Dimension(100, 30));
        logBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.openLog();
            }
        });
        JButton openDir = DesktopTheme.smallButton("打开数据目录");
        openDir.setPreferredSize(new Dimension(120, 30));
        openDir.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.openPath(DesktopPaths.dataDir());
            }
        });
        JButton back = DesktopTheme.smallButton("返回");
        back.setPreferredSize(new Dimension(80, 30));
        back.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.back();
            }
        });
        row2.add(diag);
        row2.add(logBtn);
        row2.add(openDir);
        row2.add(back);
        row2.setAlignmentX(LEFT_ALIGNMENT);
        body.add(row2);
        body.add(Box.createVerticalStrut(16));

        JLabel ver = DesktopTheme.label("版本 " + DesktopVersion.full(), 12,
                DesktopTheme.TEXT_DIM);
        ver.setAlignmentX(LEFT_ALIGNMENT);
        body.add(ver);

        JLabel path = DesktopTheme.label(DesktopPaths.describe(), 12, DesktopTheme.TEXT_DIM);
        path.setAlignmentX(LEFT_ALIGNMENT);
        body.add(path);

        JLabel anchors = DesktopTheme.label(
                "根证书: " + DesktopTrust.anchorCount() + " 个（" + DesktopTrust.source() + "）",
                12, DesktopTheme.TEXT_DIM);
        anchors.setAlignmentX(LEFT_ALIGNMENT);
        body.add(anchors);

        add(DesktopTheme.scroll(body), BorderLayout.CENTER);

        refreshAccountText();
        if (DesktopPrefs.get().hasToken()) {
            testConnection();
        }
    }

    /** 先把本地已知的状态显示出来，不要留空白。 */
    private void refreshAccountText() {
        if (accountText == null) {
            return;
        }
        String who = DesktopPrefs.get().getUsername();
        if (who.length() > 0) {
            accountText.setForeground(DesktopTheme.GREEN_LIGHT);
            accountText.setText("已登录：" + who);
        } else if (DesktopPrefs.get().hasToken()) {
            accountText.setForeground(DesktopTheme.TEXT_DIM);
            accountText.setText("已设置令牌，用户名未知");
        } else {
            accountText.setForeground(DesktopTheme.TEXT_DIM);
            accountText.setText("未设置令牌");
        }
    }

    private void saveToken() {
        String t = new String(tokenInput.getPassword()).trim();
        DesktopPrefs.get().setToken(t);
        DesktopNet.reloadToken();
        // 只记长度，绝不记内容
        Log.i("Settings", "令牌已保存，长度 " + t.length());
        DesktopApp.toast(this, t.length() == 0 ? "已清空令牌" : "已保存，正在验证…");
        // 保存后立刻验证一次 —— 用户名必须落到本地，
        // 对局界面要靠它判断"我执哪一边"。
        if (t.length() > 0) {
            testConnection();
        } else {
            refreshAccountText();
        }
    }

    private void testConnection() {
        testing = true;
        accountText.setForeground(DesktopTheme.TEXT_DIM);
        accountText.setText("正在连接 lichess.org…");
        final String token = DesktopPrefs.get().getToken();
        DesktopAsync.run("testConn", new DesktopAsync.Job<String>() {
            public String run() throws Throwable {
                LichessApi api = DesktopNet.api();
                api.setToken(token);
                Json j = api.account();
                return j.str("username", "?");
            }
        }, new DesktopAsync.Done<String>() {
            public void done(String name, Throwable error) {
                testing = false;
                if (error != null) {
                    accountText.setForeground(DesktopTheme.WARN);
                    accountText.setText("连接失败：" + error.getMessage());
                    Log.w("Settings", "测试连接失败: " + error);
                } else {
                    accountText.setForeground(DesktopTheme.GREEN_LIGHT);
                    accountText.setText("已登录：" + name);
                    DesktopPrefs.get().setUsername(name);
                    Log.i("Settings", "测试连接成功，用户名 " + name);
                    DesktopApp.refreshAccountLabel();
                }
            }
        });
    }

    private void pickLevel() {
        String[] labels = new String[8];
        for (int i = 0; i < 8; i++) {
            labels[i] = "等级 " + (i + 1) + "（思考约 "
                    + (Ai.timeBudgetForLevel(i + 1) / 1000.0) + " 秒）";
        }
        Object pick = DesktopApp.choose(this, "离线 AI 强度", "选择等级", labels,
                labels[DesktopPrefs.get().getAiLevel() - 1]);
        if (pick == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            if (labels[i].equals(pick)) {
                DesktopPrefs.get().setAiLevel(i + 1);
                break;
            }
        }
        levelButton.setText("等级 " + DesktopPrefs.get().getAiLevel() + " / 8");
        DesktopApp.toast(this, "AI 强度已设为 " + DesktopPrefs.get().getAiLevel());
    }
}
