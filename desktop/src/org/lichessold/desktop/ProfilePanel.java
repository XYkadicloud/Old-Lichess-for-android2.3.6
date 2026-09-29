package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.lichessold.net.LichessApi;
import org.lichessold.net.LichessUser;
import org.lichessold.net.NetException;
import org.lichessold.util.Log;

/**
 * 个人主页：等级分（排位分）、战绩、在线时长。
 * 对应 Android 版的 {@code org.lichessold.ui.ProfileActivity}。
 *
 * 不传用户名就是看自己（走 /api/account → /api/user/{name}）；
 * 传了就是看别人（走 /api/user/{name}，这个端点是公开的）。
 * 在对局界面点对手名字就能跳过来。
 *
 * 排位分（等级分）说明：
 *   lichess 每个分项（子弹/闪电/快速/经典/通信/各种变体/谜题）各自独立算分，
 *   用 Glicko-2。rd 越大说明这个分数越不准（打得少），界面上标一个"?"。
 *   打排位赛会改这些数字，休闲赛不会。
 */
public final class ProfilePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private LichessApi api;
    private String targetName = "";

    private final JPanel content = new JPanel();
    private final JLabel statusText;
    private final JLabel nameText;
    private final JLabel subText;
    private final JButton challengeButton;

    private boolean loading;
    private volatile boolean destroyed;

    public ProfilePanel(String username) {
        super(new BorderLayout(0, 0));
        setBackground(DesktopTheme.BG);
        if (username != null) {
            targetName = username.trim();
        }

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBackground(DesktopTheme.PANEL);
        head.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        nameText = DesktopTheme.bold("加载中…", 20, DesktopTheme.TEXT_BRIGHT);
        nameText.setAlignmentX(LEFT_ALIGNMENT);
        head.add(nameText);

        subText = DesktopTheme.label("", 12, DesktopTheme.TEXT_DIM);
        subText.setAlignmentX(LEFT_ALIGNMENT);
        head.add(subText);

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(DesktopTheme.BG);
        north.add(DesktopTheme.header("主页"), BorderLayout.NORTH);
        north.add(head, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        JPanel lower = new JPanel(new BorderLayout());
        lower.setBackground(DesktopTheme.BG);

        statusText = DesktopTheme.label("", 13, DesktopTheme.TEXT_DIM);
        statusText.setBorder(BorderFactory.createEmptyBorder(6, 14, 4, 14));
        lower.add(statusText, BorderLayout.NORTH);

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(DesktopTheme.BG);
        content.setBorder(BorderFactory.createEmptyBorder(0, 14, 10, 14));
        lower.add(DesktopTheme.scroll(content), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        buttons.setBackground(DesktopTheme.BG);
        JButton refresh = DesktopTheme.smallButton("刷新");
        refresh.setPreferredSize(new Dimension(80, 30));
        refresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                load();
            }
        });
        challengeButton = DesktopTheme.smallPrimary("挑战 TA");
        challengeButton.setPreferredSize(new Dimension(96, 30));
        challengeButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                ChallengeFlow.challengeUser(ProfilePanel.this, targetName);
            }
        });
        JButton back = DesktopTheme.smallButton("返回");
        back.setPreferredSize(new Dimension(80, 30));
        back.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                DesktopApp.back();
            }
        });
        buttons.add(refresh);
        buttons.add(challengeButton);
        buttons.add(back);
        lower.add(buttons, BorderLayout.SOUTH);

        add(lower, BorderLayout.CENTER);

        try {
            api = DesktopNet.api();
        } catch (NetException e) {
            statusText.setText("网络层初始化失败");
            DesktopApp.toast(this, e.getMessage());
            return;
        }
        load();
    }

    public void dispose() {
        destroyed = true;
    }

    // -------------------------------------------------------------- 加载

    private void load() {
        if (loading || api == null) {
            return;
        }
        final boolean self = targetName.length() == 0;
        if (!self && !DesktopPrefs.get().hasToken()) {
            statusText.setText("查看别人主页也要令牌（lichess 的 /api/user 需要认证）");
        }
        loading = true;
        statusText.setText("正在加载" + (self ? "我的" : targetName + " 的") + "资料…");

        DesktopAsync.run("profile", new DesktopAsync.Job<LichessUser>() {
            public LichessUser run() throws Throwable {
                if (targetName.length() == 0) {
                    LichessUser u = api.myProfile();
                    // 顺手把用户名补进本地设置 —— 对局界面靠它判断执哪一边
                    if (u != null && u.username.length() > 0) {
                        DesktopPrefs.get().setUsername(u.username);
                    }
                    return u;
                }
                return api.userProfile(targetName);
            }
        }, new DesktopAsync.Done<LichessUser>() {
            public void done(LichessUser u, Throwable error) {
                loading = false;
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    statusText.setText("加载失败");
                    renderError(error);
                    return;
                }
                statusText.setText("");
                render(u);
            }
        });
    }

    private void renderError(Throwable error) {
        content.removeAll();
        nameText.setText(targetName.length() == 0 ? "我的主页" : targetName);
        subText.setText("");
        JLabel t = DesktopTheme.label("加载失败：\n" + error.getMessage(), 13, DesktopTheme.WARN);
        t.setAlignmentX(LEFT_ALIGNMENT);
        content.add(t);
        content.revalidate();
        content.repaint();
    }

    // -------------------------------------------------------------- 渲染

    private void render(LichessUser u) {
        if (u == null) {
            renderError(new IllegalStateException("服务端返回空资料"));
            return;
        }
        boolean self = targetName.length() == 0
                || u.username.equalsIgnoreCase(DesktopPrefs.get().getUsername());

        String title = u.title.length() > 0 ? u.title + " " : "";
        nameText.setText(title + u.username + (self ? "  （你）" : ""));

        StringBuilder sb = new StringBuilder();
        sb.append("注册 ").append(LichessUser.formatDate(u.createdAt));
        if (u.patron) {
            sb.append("  · 赞助者");
        }
        if (u.verified) {
            sb.append("  · 已认证");
        }
        if (u.streamer) {
            sb.append("  · 主播");
        }
        subText.setText(sb.toString());

        // 看别人的主页才能挑战；看自己的没必要
        challengeButton.setVisible(!self);

        content.removeAll();

        // ---- 等级分（排位分）
        content.add(section("等级分（排位分）"));
        int shown = 0;
        for (int i = 0; i < LichessUser.PROFILE_ORDER.length; i++) {
            String key = LichessUser.PROFILE_ORDER[i];
            LichessUser.Perf p = u.perf(key);
            if (p == null || !p.hasRating()) {
                continue;
            }
            addKv(LichessUser.displayName(key),
                    p.rating + (p.isProvisional() ? "?" : ""),
                    p.games + " 局",
                    p.isProvisional() ? DesktopTheme.TEXT_DIM : DesktopTheme.GREEN_LIGHT);
            shown++;
        }
        if (shown == 0) {
            addNote("还没有下过棋，没有等级分。");
        } else {
            addNote("带 ? 的分数是临时分（局数少，rd 高，还不准）");
        }

        // ---- 战绩
        content.add(section("战绩"));
        addKv("总对局", String.valueOf(u.countAll), "其中排位 " + u.countRated,
                DesktopTheme.TEXT_BRIGHT);
        addKv("胜 / 负 / 和",
                u.countWin + " / " + u.countLoss + " / " + u.countDraw,
                "", DesktopTheme.TEXT_BRIGHT);
        int wr = u.winRate();
        addKv("胜率", wr < 0 ? "—" : wr + "%", "", DesktopTheme.TEXT_BRIGHT);
        if (u.countPlaying > 0) {
            addKv("进行中", String.valueOf(u.countPlaying), "局", DesktopTheme.GREEN_LIGHT);
        }

        // ---- 其他
        content.add(section("其他"));
        addKv("在线时长", LichessUser.formatPlayTime(u.playTimeTotal),
                "观战 " + LichessUser.formatPlayTime(u.playTimeTv), DesktopTheme.TEXT);
        if (u.seenAt > 0) {
            addKv("最后在线", LichessUser.formatDate(u.seenAt), "", DesktopTheme.TEXT);
        }
        if (u.country.length() > 0) {
            addKv("国家/地区", u.country, "", DesktopTheme.TEXT);
        }
        if (u.realName.length() > 0) {
            addKv("真名", u.realName, "", DesktopTheme.TEXT);
        }
        if (u.bio.length() > 0) {
            JLabel bio = DesktopTheme.label("<html><body style='width:520px'>"
                    + escape(u.bio) + "</body></html>", 12, DesktopTheme.TEXT);
            bio.setAlignmentX(LEFT_ALIGNMENT);
            bio.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            content.add(bio);
        }
        if (u.url.length() > 0) {
            addNote(u.url);
        }

        content.revalidate();
        content.repaint();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private JLabel section(String text) {
        JLabel l = DesktopTheme.label(text, 12, DesktopTheme.TEXT_DIM);
        l.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    /** 一行 "左边名称 / 右边数值"，右侧还可以带一小段灰字说明。 */
    private void addKv(String key, String value, String note, java.awt.Color valueColor) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(DesktopTheme.PANEL);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(LEFT_ALIGNMENT);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        row.add(DesktopTheme.label(key, 13, DesktopTheme.TEXT_DIM), c);

        if (note != null && note.length() > 0) {
            c.weightx = 0;
            row.add(DesktopTheme.label(note, 11, DesktopTheme.TEXT_DIM), c);
        }

        c.weightx = 0;
        JLabel v = DesktopTheme.bold(value, 15, valueColor);
        row.add(v, c);

        content.add(row);
        content.add(Box.createVerticalStrut(2));
    }

    private void addNote(String text) {
        JLabel t = DesktopTheme.label(text, 11, DesktopTheme.TEXT_DIM);
        t.setAlignmentX(LEFT_ALIGNMENT);
        t.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        content.add(t);
    }
}
