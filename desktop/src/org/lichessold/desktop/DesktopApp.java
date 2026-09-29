package org.lichessold.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import org.lichessold.util.Log;

/**
 * 桌面版入口。对应 Android 版的 {@code App} + 各 Activity 的导航。
 *
 * 界面结构：
 *   ┌─────────────────────────────────────────────┐
 *   │            当前页面（CardLayout 换页）           │
 *   ├─────────────────────────────────────────────┤
 *   │ 状态栏：临时提示（相当于 Android 的 Toast）        │
 *   └─────────────────────────────────────────────┘
 *
 * 导航用一个简单的页面栈实现，对应 Android 的 startActivity / finish：
 *   push(页面)  ≈ startActivity      back() ≈ finish
 * 页面离开**栈**时才 dispose（停网络流/定时器），只是被盖住时不 dispose ——
 * 这样从对局页点进对手主页，对局流仍然在跑，回来时局面是同步的。
 */
public final class DesktopApp {

    private static JFrame frame;
    private static JPanel content;
    private static JLabel statusBar;

    private static JComponent currentScreen;
    private static String currentTitle = "";
    private static final Deque<Entry> history = new ArrayDeque<Entry>();

    private static Timer statusTimer;

    private static final class Entry {
        final JComponent comp;
        final String title;

        Entry(JComponent comp, String title) {
            this.comp = comp;
            this.title = title;
        }
    }

    private DesktopApp() {
    }

    // ------------------------------------------------------------------ 入口

    public static void main(String[] args) {
        boolean smoke = false;
        boolean smokeNet = false;
        for (int i = 0; i < args.length; i++) {
            if ("--smoke".equals(args[i])) {
                smoke = true;
            } else if ("--smoke-net".equals(args[i])) {
                smokeNet = true;
            }
        }
        if (smoke) {
            System.exit(smokeTest(smokeNet) ? 0 : 1);
        }

        // 日志最先起来，后面任何一步出问题都有现场
        DesktopLog.init();
        Log.i("App", "================ lichess desktop ================");
        Log.i("App", "version   = " + DesktopVersion.full());
        Log.i("App", "java      = " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        Log.i("App", "os        = " + System.getProperty("os.name")
                + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        Log.i("App", "heapMax   = " + (Runtime.getRuntime().maxMemory() / 1024L / 1024L) + " MB");
        Log.i("App", DesktopPaths.describe());
        Log.i("App", "logFile   = " + (DesktopLog.logFile() == null ? "?" :
                DesktopLog.logFile().getAbsolutePath()));

        final DesktopPrefs prefs = DesktopPrefs.get();
        Log.i("App", "token saved = " + (prefs.hasToken() ? "yes" : "no")
                + ", debug = " + prefs.isDebugMode()
                + ", aiLevel = " + prefs.getAiLevel());

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                startUi();
            }
        });
    }

    private static void startUi() {
        installLookAndFeel();
        // 棋子路径提前构建，避免第一帧卡顿
        Pieces2D.warmUp();

        frame = new JFrame("lichess desktop " + DesktopVersion.VERSION);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(880, 660));
        frame.setSize(1040, 780);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(DesktopTheme.BG);

        content = new JPanel(new BorderLayout());
        content.setBackground(DesktopTheme.BG);

        statusBar = new JLabel(" ");
        statusBar.setFont(DesktopTheme.ui(12));
        statusBar.setForeground(DesktopTheme.TEXT_DIM);
        statusBar.setOpaque(true);
        statusBar.setBackground(DesktopTheme.PANEL);
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));

        frame.getContentPane().add(content, BorderLayout.CENTER);
        frame.getContentPane().add(statusBar, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        home();
        frame.setVisible(true);

        // 有令牌但用户名未知时，让主菜单自己去补 —— 它在构造后第一帧就会跑
        Log.i("App", "startup complete");
    }

    // ------------------------------------------------------- 界面冒烟测试

    /**
     * 把每个页面都构造、布局、离屏绘制一遍，然后销毁。
     *
     * 为什么需要它：这些页面里有大量"点进去才会跑"的代码（构造时拉数据、
     * 起事件流、算布局）。只看程序能不能启动是覆盖不到的 —— 真要发现问题
     * 得手动把每个菜单项点一遍。这里一次性过一遍，任何一页抛异常都算失败。
     *
     * 分两组：
     *   离线页面（主菜单/设置/双人/人机/诊断/日志）—— 一定会跑，构建脚本默认调它
     *   联网页面（谜题/观战/主页/大厅/在线对局）—— 加 --smoke-net 才跑，
     *   因为它们在构造时就会发请求，没网的环境下要等连接超时，会把构建拖住
     *
     * 无显示环境（headless）下跳过并返回成功。
     */
    private static boolean smokeTest(boolean includeNetwork) {
        DesktopLog.init();
        Log.i("App", "==== 界面冒烟测试开始（联网页面=" + includeNetwork + "）====");

        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.out.println("lichess desktop 界面冒烟测试：无显示环境，跳过");
            return true;
        }

        installLookAndFeel();
        Pieces2D.warmUp();

        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        java.util.ArrayList<JComponent> screens = new java.util.ArrayList<JComponent>();

        // ---- 离线页面：不联网也能完整走完
        names.add("主菜单");
        screens.add(new MainMenuPanel());
        names.add("设置");
        screens.add(new SettingsPanel());
        names.add("离线双人对战");
        screens.add(new LocalGamePanel());
        names.add("离线人机对战");
        screens.add(new AiGamePanel());
        names.add("网络诊断");
        screens.add(new DiagPanel());
        names.add("运行日志");
        screens.add(new LogPanel());

        // ---- 联网页面：构造时就会发请求
        if (includeNetwork) {
            names.add("谜题训练");
            screens.add(new PuzzlePanel());
            names.add("观战");
            screens.add(new TvPanel());
            names.add("个人主页");
            screens.add(new ProfilePanel("thibault"));
            names.add("在线对局大厅");
            screens.add(new LobbyPanel());
            names.add("在线对局");
            screens.add(new OnlineGamePanel("smoketest00", "white"));
        }

        int ok = 0;
        int bad = 0;
        for (int i = 0; i < screens.size(); i++) {
            // 先打印再测：万一某一页把进程卡住，日志里能看出卡在哪一页
            System.out.println("  ... 正在检查界面 " + names.get(i));
            System.out.flush();
            if (tryScreen(names.get(i), screens.get(i))) {
                ok++;
            } else {
                bad++;
            }
        }

        System.out.println("---------------------------------------------");
        System.out.println("界面冒烟测试：通过 " + ok + " 项，失败 " + bad + " 项"
                + (includeNetwork ? "" : "（未含联网页面）"));
        Log.i("App", "==== 界面冒烟测试结束：通过 " + ok + "，失败 " + bad + " ====");

        DesktopNet.close();
        DesktopLog.flush(800);
        // 有些页面（大厅/观战/对局）起了守护线程，直接退出
        return bad == 0;
    }

    private static boolean tryScreen(String name, JComponent screen) {
        try {
            JFrame f = new JFrame();
            f.getContentPane().setBackground(DesktopTheme.BG);
            f.getContentPane().add(screen);
            f.setSize(1040, 780);
            f.pack();
            f.validate();

            // 离屏画一遍：布局代码没问题不代表绘制代码没问题
            screen.setSize(1040, 780);
            screen.doLayout();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    1040, 780, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            screen.paint(g);
            g.dispose();
            f.dispose();

            System.out.println("  [通过] 界面 " + name);
            return true;
        } catch (Throwable t) {
            System.out.println("  [失败] 界面 " + name + "  —— "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.e("App", "界面 " + name + " 冒烟失败", t);
            return false;
        } finally {
            disposeOf(screen);
        }
    }

    private static void shutdown() {
        Log.i("App", "退出，正在收尾");
        if (currentScreen != null) {
            disposeOf(currentScreen);
        }
        while (!history.isEmpty()) {
            disposeOf(history.pop().comp);
        }
        DesktopNet.close();
        DesktopLog.flush(1500);
        frame.dispose();
        System.exit(0);
    }

    // ---------------------------------------------------------- Look and Feel

    /**
     * 把 Swing 默认外观改成 lichess 的深色风格。
     *
     * 不换第三方 L&F（那要额外依赖，和本项目"零外部依赖"的取向冲突），
     * 只覆盖 UIManager 里需要的键 —— 弹出对话框、下拉框、滚动条都会跟着变。
     */
    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Throwable ignored) {
        }

        Font base = DesktopTheme.ui(13);
        FontUIResource fontRes = new FontUIResource(base);
        for (Enumeration<Object> keys = UIManager.getDefaults().keys(); keys.hasMoreElements(); ) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                UIManager.put(key, fontRes);
            }
        }

        UIManager.put("Panel.background", DesktopTheme.PANEL);
        UIManager.put("OptionPane.background", DesktopTheme.PANEL);
        UIManager.put("OptionPane.messageForeground", DesktopTheme.TEXT);
        UIManager.put("Label.foreground", DesktopTheme.TEXT);
        UIManager.put("Button.background", DesktopTheme.PANEL_ALT);
        UIManager.put("Button.foreground", DesktopTheme.TEXT);
        UIManager.put("TextField.background", new Color(0x1D1B19));
        UIManager.put("TextField.foreground", DesktopTheme.TEXT_BRIGHT);
        UIManager.put("TextField.caretForeground", DesktopTheme.TEXT_BRIGHT);
        UIManager.put("PasswordField.background", new Color(0x1D1B19));
        UIManager.put("PasswordField.foreground", DesktopTheme.TEXT_BRIGHT);
        UIManager.put("List.background", DesktopTheme.PANEL);
        UIManager.put("List.foreground", DesktopTheme.TEXT);
        UIManager.put("List.selectionBackground", DesktopTheme.GREEN);
        UIManager.put("List.selectionForeground", DesktopTheme.TEXT_BRIGHT);
        UIManager.put("ComboBox.background", DesktopTheme.PANEL_ALT);
        UIManager.put("ComboBox.foreground", DesktopTheme.TEXT);
        UIManager.put("CheckBox.background", DesktopTheme.BG);
        UIManager.put("CheckBox.foreground", DesktopTheme.TEXT);
        UIManager.put("ScrollBar.background", DesktopTheme.PANEL);
        UIManager.put("ScrollBar.thumb", DesktopTheme.BORDER);
        UIManager.put("ScrollBar.track", DesktopTheme.BG);
        UIManager.put("ToolTip.background", DesktopTheme.PANEL_ALT);
        UIManager.put("ToolTip.foreground", DesktopTheme.TEXT);
        UIManager.put("separator.background", DesktopTheme.BORDER);
    }

    // -------------------------------------------------------------- 导航

    public static void home() {
        if (currentScreen != null) {
            disposeOf(currentScreen);
        }
        while (!history.isEmpty()) {
            disposeOf(history.pop().comp);
        }
        MainMenuPanel menu = new MainMenuPanel();
        setScreen(menu, "主菜单");
        menu.onShow();
    }

    /** 打开一个新页面，当前页面压栈（不销毁）。 */
    private static void push(JComponent screen, String title) {
        if (currentScreen != null) {
            history.push(new Entry(currentScreen, currentTitle));
        }
        setScreen(screen, title);
    }

    /** 回到上一页。当前页面被销毁。 */
    public static void back() {
        if (history.isEmpty()) {
            home();
            return;
        }
        if (currentScreen != null) {
            disposeOf(currentScreen);
        }
        Entry e = history.pop();
        setScreen(e.comp, e.title);
    }

    private static void setScreen(JComponent screen, String title) {
        currentScreen = screen;
        currentTitle = title;
        content.removeAll();
        content.add(screen, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
        if (frame != null) {
            frame.setTitle(title + " — lichess desktop " + DesktopVersion.VERSION);
        }
        if (screen instanceof MainMenuPanel) {
            ((MainMenuPanel) screen).onShow();
        }
    }

    /** 页面真正离开栈时才会走到这里 —— 停网络流、停定时器。 */
    private static void disposeOf(JComponent c) {
        if (c instanceof GamePanel) {
            ((GamePanel) c).dispose();
        } else if (c instanceof LobbyPanel) {
            ((LobbyPanel) c).dispose();
        } else if (c instanceof ProfilePanel) {
            ((ProfilePanel) c).dispose();
        }
    }

    // ------------------------------------------------------------ 打开各页

    public static void openLobby() {
        push(new LobbyPanel(), "在线对局");
    }

    public static void openOnlineGame(String gameId, String myColor) {
        push(new OnlineGamePanel(gameId, myColor), "在线对局");
    }

    public static void openAiGame() {
        push(new AiGamePanel(), "离线人机对战");
    }

    public static void openLocalGame() {
        push(new LocalGamePanel(), "离线双人对战");
    }

    public static void openPuzzle() {
        push(new PuzzlePanel(), "谜题训练");
    }

    public static void openTv() {
        push(new TvPanel(), "观战");
    }

    public static void openSettings() {
        push(new SettingsPanel(), "设置");
    }

    public static void openDiag() {
        push(new DiagPanel(), "网络诊断");
    }

    public static void openLog() {
        push(new LogPanel(), "运行日志");
    }

    public static void openProfile(String username) {
        push(new ProfilePanel(username), "主页");
    }

    /** 设置页保存令牌后，如果主菜单还在栈里，刷新它的账号行。 */
    public static void refreshAccountLabel() {
        for (Entry e : history) {
            if (e.comp instanceof MainMenuPanel) {
                ((MainMenuPanel) e.comp).refreshAccountLabel();
            }
        }
    }

    /** 用系统默认程序打开一个目录或文件。 */
    public static void openPath(File path) {
        try {
            if (path == null || !path.exists()) {
                toast(null, "路径不存在: " + path);
                return;
            }
            if (!java.awt.Desktop.isDesktopSupported()) {
                toast(null, "当前环境不支持打开文件管理器，路径：\n" + path.getAbsolutePath());
                return;
            }
            java.awt.Desktop.getDesktop().open(path);
        } catch (Throwable t) {
            toast(null, "打开失败: " + t + "\n路径：" + path);
        }
    }

    // -------------------------------------------------------------- 对话框

    /**
     * 状态栏提示（相当于 Android 的 Toast）。
     *
     * 为什么不用弹窗：这个程序里提示非常频繁（"已选中 e2"、"走子发送中"…），
     * 每次弹窗都要点确定会烦死人。状态栏显示 4 秒后自动消失。
     */
    public static void toast(Component parent, final String msg) {
        if (statusBar == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                statusBar.setForeground(DesktopTheme.GREEN_LIGHT);
                statusBar.setText(" " + msg.replace('\n', ' '));
                if (statusTimer != null) {
                    statusTimer.stop();
                }
                statusTimer = new Timer(4000, new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        statusTimer.stop();
                        statusBar.setText(" ");
                        statusBar.setForeground(DesktopTheme.TEXT_DIM);
                    }
                });
                statusTimer.setRepeats(false);
                statusTimer.start();
            }
        });
    }

    public static boolean confirm(Component parent, String title, String msg) {
        int r = JOptionPane.showConfirmDialog(parent, msg, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return r == JOptionPane.OK_OPTION;
    }

    public static String prompt(Component parent, String title, String label, String initial) {
        Object v = JOptionPane.showInputDialog(parent, label, title,
                JOptionPane.QUESTION_MESSAGE, null, null, initial);
        return v == null ? null : String.valueOf(v);
    }

    /** 单选对话框。取消返回 null。 */
    public static Object choose(Component parent, String title, String msg,
                                Object[] options, Object initial) {
        return JOptionPane.showInputDialog(parent, msg, title,
                JOptionPane.QUESTION_MESSAGE, null, options, initial);
    }
}
