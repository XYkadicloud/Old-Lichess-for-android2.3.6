package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.lichessold.json.Json;
import org.lichessold.net.LichessApi;
import org.lichessold.util.Log;

/**
 * 主菜单。对应 Android 版的 {@code org.lichessold.ui.MainActivity}。
 * 布局照 lichess 官网的深色主题：绿色标题栏 + 分组列表项。
 */
public final class MainMenuPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel accountTitle;
    private final JLabel accountSubtitle;
    private boolean fetchingUsername;

    public MainMenuPanel() {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);
        add(DesktopTheme.header("lichess"), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(DesktopTheme.BG);
        body.setBorder(BorderFactory.createEmptyBorder(8, 14, 14, 14));

        // 账号行。文字要能跟着设置页的状态变，所以留引用。
        JPanel accountRow = new JPanel(new GridBagLayout());
        accountRow.setBackground(DesktopTheme.PANEL);
        accountRow.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        accountRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        accountRow.setAlignmentX(LEFT_ALIGNMENT);
        accountRow.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        accountTitle = DesktopTheme.bold("未登录", 15, DesktopTheme.TEXT);
        accountRow.add(accountTitle, c);

        c.gridy = 1;
        accountSubtitle = DesktopTheme.label("点这里填 API 令牌", 12, DesktopTheme.TEXT_DIM);
        accountRow.add(accountSubtitle, c);

        accountRow.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                DesktopApp.openSettings();
            }
        });
        body.add(accountRow);
        body.add(Box.createVerticalStrut(6));

        // ---- 下棋
        body.add(sectionLabel("下棋"));
        body.add(row("在线对局", "挑战电脑 · 找对手 · 接受挑战", new Runnable() {
            public void run() {
                DesktopApp.openLobby();
            }
        }));
        body.add(row("离线人机对战", "内置引擎，8 个强度等级，不联网", new Runnable() {
            public void run() {
                DesktopApp.openAiGame();
            }
        }));
        body.add(row("离线双人对战", "同一台电脑两人轮流走", new Runnable() {
            public void run() {
                DesktopApp.openLocalGame();
            }
        }));

        // ---- 我的
        body.add(sectionLabel("我的"));
        body.add(row("我的主页与排位分", "各分项等级分 · 战绩 · 在线时长", new Runnable() {
            public void run() {
                DesktopApp.openProfile("");
            }
        }));

        // ---- 学习
        body.add(sectionLabel("学习与观战"));
        body.add(row("谜题训练", "每日一题 · 19 种主题", new Runnable() {
            public void run() {
                DesktopApp.openPuzzle();
            }
        }));
        body.add(row("观战", "Lichess 焦点对局直播（不需要令牌）", new Runnable() {
            public void run() {
                DesktopApp.openTv();
            }
        }));

        // ---- 系统
        body.add(sectionLabel("系统"));
        body.add(row("设置", "令牌 · AI 强度 · 调试模式", new Runnable() {
            public void run() {
                DesktopApp.openSettings();
            }
        }));
        body.add(row("网络诊断", "DNS → TCP → TLS → 证书 → HTTP 逐步骤检查", new Runnable() {
            public void run() {
                DesktopApp.openDiag();
            }
        }));
        body.add(row("运行日志", "查看 · 保存 · 打开日志目录", new Runnable() {
            public void run() {
                DesktopApp.openLog();
            }
        }));

        JLabel ver = DesktopTheme.label(DesktopVersion.banner(), 12, DesktopTheme.TEXT_DIM);
        ver.setAlignmentX(LEFT_ALIGNMENT);
        ver.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
        body.add(ver);

        JLabel path = DesktopTheme.label(DesktopPaths.describe(), 12, DesktopTheme.TEXT_DIM);
        path.setAlignmentX(LEFT_ALIGNMENT);
        body.add(path);

        add(DesktopTheme.scroll(body), BorderLayout.CENTER);
    }

    /** 页面显示时调用：刷新账号行，并补拉一次用户名。 */
    public void onShow() {
        Log.setDebugEnabled(DesktopPrefs.get().isDebugMode());
        refreshAccountRow();
        fetchUsernameIfMissing();
    }

    /** 供设置页保存令牌后回调。 */
    public void refreshAccountLabel() {
        refreshAccountRow();
    }

    private void refreshAccountRow() {
        if (accountTitle == null) {
            return;
        }
        DesktopPrefs p = DesktopPrefs.get();
        String who = p.getUsername();
        if (who.length() > 0) {
            accountTitle.setForeground(DesktopTheme.TEXT_BRIGHT);
            accountTitle.setText(who);
            accountSubtitle.setText("已连接 lichess.org · 点这里改设置");
        } else if (p.hasToken()) {
            accountTitle.setForeground(DesktopTheme.TEXT);
            accountTitle.setText("已设置令牌");
            accountSubtitle.setText("正在确认账号…（点这里去设置）");
        } else {
            accountTitle.setForeground(DesktopTheme.TEXT);
            accountTitle.setText("未登录");
            accountSubtitle.setText("点这里填 API 令牌");
        }
    }

    /**
     * 有令牌但还没拿到用户名时，后台补一次 /api/account。
     *
     * 这一步很关键：对局界面要靠用户名判断"我执哪一边"。
     * 用户名缺了就会判不出颜色 —— 执黑时棋盘方向反、而且根本走不了子。
     * 所以只要令牌在，就一定要把用户名拿到并存下来。
     */
    private void fetchUsernameIfMissing() {
        final DesktopPrefs p = DesktopPrefs.get();
        if (fetchingUsername || !p.hasToken() || p.getUsername().length() > 0) {
            return;
        }
        fetchingUsername = true;
        DesktopAsync.run("whoami", new DesktopAsync.Job<String>() {
            public String run() throws Throwable {
                LichessApi api = DesktopNet.api();
                return api.accountUsername();
            }
        }, new DesktopAsync.Done<String>() {
            public void done(String name, Throwable error) {
                fetchingUsername = false;
                if (error != null) {
                    Log.w("UI", "自动获取用户名失败: " + error.getMessage());
                    if (accountTitle != null) {
                        accountTitle.setForeground(DesktopTheme.WARN);
                        accountTitle.setText("令牌可能无效");
                        accountSubtitle.setText("点这里去设置里测试连接");
                    }
                    return;
                }
                if (name != null && name.length() > 0) {
                    p.setUsername(name);
                    Log.i("UI", "自动获取到用户名 " + name);
                }
                refreshAccountRow();
            }
        });
    }

    private JLabel sectionLabel(String text) {
        JLabel l = DesktopTheme.label(text, 12, DesktopTheme.TEXT_DIM);
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(10, 2, 4, 2));
        return l;
    }

    private JPanel row(String title, String subtitle, final Runnable onClick) {
        JPanel box = new JPanel(new GridBagLayout());
        box.setBackground(DesktopTheme.PANEL);
        box.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        box.add(DesktopTheme.label(title, 14, DesktopTheme.TEXT), c);

        c.gridy = 1;
        box.add(DesktopTheme.label(subtitle, 11, DesktopTheme.TEXT_DIM), c);

        c.gridy = 0;
        c.gridheight = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 8, 0, 2);
        box.add(DesktopTheme.label("›", 20, DesktopTheme.GREEN), c);

        box.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                ((JPanel) e.getSource()).setBackground(DesktopTheme.PANEL_ALT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ((JPanel) e.getSource()).setBackground(DesktopTheme.PANEL);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    onClick.run();
                } catch (Throwable t) {
                    Log.e("UI", "菜单项打开失败", t);
                    DesktopApp.toast(MainMenuPanel.this, "打开失败: " + t);
                }
            }
        });

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(DesktopTheme.BG);
        wrap.add(box, BorderLayout.CENTER);
        wrap.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        return wrap;
    }
}
