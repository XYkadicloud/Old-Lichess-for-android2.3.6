# lichess desktop — Lichess 客户端桌面版

把 `app/` 里的 Android 版（**Lichess Old 0.8.0**，为 Samsung GT-S5360 / Android 2.3.6 开发）
移植成一个**双击就能跑的 Windows 桌面 Java 程序**。

- 单文件可执行 JAR，**3.4 MB**，自带全部依赖（含 TLS 库和 121 个根证书）
- 纯 Java + Swing，**零第三方 UI 依赖**
- 内核（棋规 / JSON / TLS / HTTP）**与手机版是同一份源码，一行没改**
- 构建工具链全部在项目盘，不写 C 盘

```
产物： desktop/dist/LichessOldDesktop-1.0.0.jar
启动： 双击 desktop/run.bat
```

---

## 快速开始

```bash
bash desktop/build.sh          # 构建 + 自检 + 界面冒烟测试（全量约 25 秒，增量约 18 秒）
bash desktop/build.sh --run    # 构建完直接启动
bash desktop/build.sh --regen  # 先重新生成棋子矢量数据再构建
bash desktop/build.sh --smoke  # 连联网页面一起冒烟测试（慢，需要网络）
bash desktop/build.sh --full   # 源码里删过文件时用，重建全部 class
bash desktop/clean.sh          # 回收没用的中间目录（16 秒）
```

双击 `desktop/run.bat` 即可运行。启动失败想看到报错就用 `run-console.bat`。

**首次使用要先填令牌**：主菜单 →「设置」→ 粘贴 Lichess API 令牌（在
<https://lichess.org/account/oauth/token> 创建，勾选 `board:play`）→「保存令牌」。
不填也能用离线人机、离线双人、观战和网络诊断。

### 磁盘占用

本项目在 **exFAT** 盘上开发，exFAT 的簇很大（307GB 卷默认 **128 KB**），
**每个文件不管多小都吃掉一整个簇**。所以这里的构建脚本对「文件个数」很敏感：

| | 文件数 | 实际字节 | 占盘 |
|---|---|---|---|
| 构建缓存 `desktop/build/` | 211 | 0.7 MB | **27 MB** |
| 产物 `desktop/dist/` | 4 | 3.3 MB | 3.5 MB |

设计上做了三件事来压住文件数（详见下面「踩过的坑」第 4 条）：

1. 编译输出固定在 **一个** `build/classes/`，javac 原地覆盖，从不重建目录
2. 依赖 jar 用 `tools/merge_jar.py` **流式**合并进产物，**不解压到磁盘**
3. 产物 jar 用 `jar cfm` 整体覆盖，不先 `rm`

---

## 功能对照

与 Android 版 0.8.0 功能一一对应：

| 功能 | 需要令牌 | 桌面版入口 |
|---|---|---|
| 在线对局（走子/时钟/认输/和棋/聊天/断线重连/重新同步） | ✅ | 在线对局 |
| 挑战电脑（等级 1~8，不计分） | ✅ | 在线对局 → 挑战电脑 |
| 挑战指定玩家（可选排位/休闲） | ✅ | 在线对局 → 挑战玩家 |
| 找真人对手（seek 匹配，Rapid 及更慢） | ✅ | 在线对局 → 找真人对手 |
| 接受 / 拒绝挑战（事件流） | ✅ | 在线对局（自动出现） |
| 个人主页与排位分（可发起挑战） | ✅ | 我的主页 / 对局里点对手名 |
| 离线人机对战（内置引擎，8 级） | ❌ | 离线人机对战 |
| 离线双人对战 | ❌ | 离线双人对战 |
| 谜题训练（每日题 / 随机题 / 19 种主题） | ❌ | 谜题训练 |
| 观战（焦点对局直播） | ❌ | 观战 |
| 网络诊断（DNS→TCP→TLS→证书→HTTP 逐步骤） | ❌ | 网络诊断 |
| 运行日志 / 令牌脱敏 | ❌ | 运行日志 |

桌面版比手机版多出来的：

- **走子记录栏**：对局界面右侧实时显示 `1. e4 e5 / 2. Nf3 …`，手机屏幕塞不下
- **状态栏提示**：替代 Android 的 Toast，不打断操作
- **设置页显示数据目录、日志路径、根证书来源**，方便排查
- **引擎强得多**：桌面 CPU 比 832MHz 的 ARM11 快两个数量级，等级 8 会明显更强

---

## 目录结构

```
desktop/
├── src/org/lichessold/desktop/     27 个 Java 文件
│   ├── DesktopApp.java             入口 + 窗口 + 页面栈 + 对话框
│   ├── DesktopTheme.java           配色 / 字体 / 自绘按钮（对齐 lichess 深色主题）
│   ├── DesktopPaths.java           数据目录解析（jar 同级 data/）
│   ├── DesktopPrefs.java           设置项（settings.properties）
│   ├── DesktopTrust.java           信任锚加载（jar 内置 / 磁盘覆盖）
│   ├── DesktopLog.java             日志初始化
│   ├── DesktopNet.java             全局 API 客户端
│   ├── DesktopAsync.java           后台任务 → EDT
│   ├── DesktopVersion.java         版本信息
│   ├── PieceArt2D.java             ★ 生成：棋子矢量数据（勿手改）
│   ├── DesktopBuildInfo.java       ★ 生成：构建时间（勿手改）
│   ├── Pieces2D.java               棋子绘制
│   ├── BoardPanel.java             棋盘控件（点选走子 / 高亮 / 坐标）
│   ├── GamePanel.java              对局界面基类
│   ├── LocalGamePanel.java         离线双人
│   ├── AiGamePanel.java            离线人机
│   ├── OnlineGamePanel.java        在线对局
│   ├── PuzzlePanel.java            谜题
│   ├── TvPanel.java                观战
│   ├── LobbyPanel.java             在线对局大厅
│   ├── ChallengeFlow.java          挑战流程
│   ├── SettingsPanel.java          设置
│   ├── ProfilePanel.java           个人主页
│   ├── DiagPanel.java              网络诊断
│   ├── LogPanel.java               运行日志
│   └── SelfCheck.java              无界面自检
├── tools/make_pieceart2d.py        从 Android 版 PieceArt.java 转换棋子数据
├── tools/merge_jar.py              把依赖 jar 流式合并进产物（不落盘）
├── assets/cacerts.pem              信任锚（Mozilla CA bundle，121 个根证书）
├── build.sh                        构建脚本
├── clean.sh                        回收没用的中间目录
├── run.bat / run-console.bat       启动器
├── build/classes/                  编译输出（固定一个目录，javac 原地覆盖）
└── dist/                           交付物 + 运行期数据（data/）
```

---

## 复用了什么，重写了什么

这是本次移植最关键的一条：**内核一行没改，只有界面层是新写的。**

### 直接复用（`app/src/` 下的源码，编译时原样参与）

| 包 | 内容 | 为什么能直接复用 |
|---|---|---|
| `org.lichessold.chess` | 0x88 棋盘、走子生成、SAN/PGN、离线 AI 引擎 | 纯 Java，零 Android 依赖 |
| `org.lichessold.json` | JSON 解析 | 同上 |
| `org.lichessold.net` | 自带 TLS 1.2 + SNI、HTTP 客户端、证书链校验、Lichess API 封装 | 同上（依赖纯 Java 的 spongycastle） |
| `org.lichessold.util` | 日志环形缓冲 + 令牌脱敏 | 同上 |
| `org.lichessold.log.FileLogSink` | 日志落盘 + 512KB 轮转 | 这个文件恰好也是纯 Java |

编译时用 `find app/src/...` 直接收集这些文件，**没有复制、没有修改**。
所以"桌面版跑得对"和"手机版跑得对"是同一件事。

### 重写（Android 专属，无法复用）

| Android 版 | 桌面版 | 说明 |
|---|---|---|
| `ui/*Activity`（18 个） | `*Panel`（11 个） | Activity + LinearLayout → JPanel + BorderLayout/BoxLayout |
| `ui/BoardView`（Canvas） | `BoardPanel`（Graphics2D） | 交互逻辑逐条对照移植 |
| `ui/Pieces` + `PieceArt` | `Pieces2D` + `PieceArt2D` | 造型数据由脚本转换，绘制改 Java2D |
| `ui/Theme`（GradientDrawable） | `DesktopTheme`（自绘按钮） | 配色完全一致 |
| `ui/Ui`（Toast/AlertDialog） | `DesktopApp`（状态栏/JOptionPane） | |
| `store/Prefs`（SharedPreferences） | `DesktopPrefs`（Properties 文件） | 键名保持一致 |
| `platform/AndroidTrust` | `DesktopTrust` | 从 assets 读 → 从 jar 资源或磁盘读 |
| `platform/Async`（Handler） | `DesktopAsync`（SwingUtilities） | 语义一致 |
| `log/Logging` / `Storage` / `CrashHandler` | `DesktopLog` + JVM 自身异常 | 桌面不需要崩溃上报 |
| `ui/ChallengeFlow` | `ChallengeFlow` | 同样复用 `net/ChallengeOptions` |

---

## 棋子矢量数据是怎么来的

`PieceArt2D.java` **不是手写的**，由 `tools/make_pieceart2d.py` 从
`app/src/org/lichessold/ui/PieceArt.java` 转换而来：

- 原样搬运 `COUNT / FIRST / OFFSET / OPS` 四张表（12 枚棋子 / 70 个子路径 / 2058 个浮点数）
- 把 `STYLE` 的字符串在**生成期**展开成 6 张数值表，运行期不再做字符串解析
- `android.graphics.Path` → `java.awt.geom.Path2D.Float`
- `Paint.Cap/Join` 的 ordinal → `BasicStroke.CAP_* / JOIN_*` 数值

为什么不重新解析 SVG：造型数据本身与平台无关，重新生成只会引入新的出错机会。

脚本里带**自检**：生成前先模拟一遍 Java 侧的 Path 构建，确认每条子路径都以
`moveTo` 开头、命令流不越界。这条检查是有必要的 —— 见下面「踩过的坑」。

---

## 验证

### 构建时自动跑

`build.sh` 最后会跑两组检查，任何一项失败都会让构建失败：

**1. 无界面自检**（`org.lichessold.desktop.SelfCheck`，16 项）

```
[通过] 棋子矢量数据    12 枚 / 70 子路径 / 坐标范围 [0.038, 0.962]
[通过] 棋盘离屏渲染    480x480 像素采样：浅格 21046 / 深格 21061 / 深色棋子 7133 / 浅色棋子 2956
[通过] 走子生成 perft  perft(1)=20  perft(2)=400  perft(3)=8902  perft(4)=197281
[通过] FEN 往返        4 条局面全部一致
[通过] SAN 生成        西班牙开局 6 步记谱正确
[通过] SAN 解析        6 步全部还原成正确 UCI
[通过] PGN 重放        重放 10 步，局面与预期一致
[通过] PGN 转 UCI      4 步正确
[通过] 将死判定        白方将死获胜
[通过] 逼和判定        逼和（和棋）
[通过] 子力不足判定    王+象 vs 王 判为和棋
[通过] 离线引擎        开局走 b1c3，深度 4，约 20 万节点/秒
[通过] 引擎找杀        找到 Ra8#
[通过] 找对手时间档    6 档全部合法（均 >= 480 秒）
[通过] 挑战时间档      9 档全部合法
[通过] 信任锚          121 个根证书
```

其中**棋盘离屏渲染**是特意加的：棋子是"生成的数据 + 手写的绘制代码"，
数据错了只会表现为一片空白，而界面照样能打开、日志照样干净。所以直接数像素，
必须同时出现浅格色、深格色、近黑、近白四种颜色。

**2. 界面冒烟测试**（默认只测离线页面，加 `--smoke` 连联网页面一起测）

把每个页面都构造、布局、离屏绘制一遍，然后销毁。这一项抓到了真实的空指针
（见下面「踩过的坑」）。

```bash
# 联网页面单独跑（构造时就会发请求，需要网络）
toolchain/jdk8/bin/java.exe -cp desktop/dist/LichessOldDesktop-1.0.0.jar \
    org.lichessold.desktop.DesktopApp --smoke --smoke-net
```

### 项目自带的桌面测试

```bash
bash scripts/test.sh
```

跑的是**同一份内核源码**，含真实网络测试（TLS 握手、证书链校验、
`/api/account` 401、`/api/puzzle/daily`、NDJSON 流式读取）。全部通过。

---

## 数据与设置放在哪

```
desktop/dist/data/
├── settings.properties    设置（含 API 令牌，只存本机）
├── logs/log.txt           运行日志（512KB 轮转）
├── logs/log.1.txt         上一份日志
└── diag.txt               网络诊断结果（点「保存结果」时写）
```

数据目录跟着 **jar 走**（`jar 所在目录/data/`），不是用户主目录。这样整个
`dist/` 目录拷到哪都能用，删掉 `data/` 就等于恢复出厂。

设置页里直接显示了实际路径，不用猜。

### 令牌安全

与 Android 版同一条约束：

- 令牌只存本机 `settings.properties`，只用于发 `Authorization` 头
- 进日志之前一律过 `util.Log.redact()`，`Bearer xxx` / `lip_xxx` / `"token": "xxx"`
  全部替换成 `***`
- 不上传、不发给任何人、不写进崩溃报告

---

## 踩过的坑（移植过程中真实发生的）

### 1. 分块切片越界，棋子数据错位

`PieceArt2D` 的命令流分两块写（避免单个方法字节码超限）。第一版按
`range(0, len//2, 8)` 分组，最后一组 `ops[1024:1032]` 跨过了中点，
把 3 个元素重复写进了两块里 → 整个命令流错位 → Java 侧报
`IllegalPathStateException: missing initial moveto in path definition`。

现在生成脚本里加了 `validate()`，生成前先模拟一遍 Path 构建，这类错误在
生成期就会被拦下。

### 2. 构造函数里刷界面 → 子类字段还是 null

`GamePanel` 构造函数末尾调了 `updateStatus()` → 回调子类的 `topText()`，
而子类的字段初始化（`puzzleId`、`game`、`white`）要等 `super()` 返回之后才跑。
Android 版没这个问题：那边是 Activity 生命周期回调（`onCreate`）在对象完全
构造好之后才触发。

现在改成子类构造函数**最后一步必须调 `start()`**，注释里写清了原因。
这个是**界面冒烟测试**抓到的，不是靠人点出来的。

### 3. 构建脚本在 exFAT 上制造了 943 MB 垃圾

第一版构建脚本每构建一次就新建一个 `build/work-<时间戳>/` 目录，
里面还有 `jar xf` 解压出来的 spongycastle（**1460 个文件**），而且从不清理。

在开发盘上跑三次构建之后：

```
desktop/build/   7,480 个文件   实际 23.7 MB   占盘 943 MB
```

**根因是文件系统**：这块盘是 **exFAT**，307 GB 的卷默认簇大小 **128 KB**。
exFAT 的簇只属于一个文件，所以哪怕一个 200 字节的 `.class` 也要吃掉 128 KB。
7480 × 128 KB ≈ 960 MB，和实测的 943 MB 对上了。

这也解释了删除为什么慢：USB 2.0 机械盘 + exFAT，删 4000 个文件要十几分钟
（用 bash 的 `rm` 更慢 —— 每个文件起一次进程，实测 12 分钟还没删完）。

**修法**（三条一起上，效果是 943 MB → 27 MB、12 分钟 → 16 秒）：

1. **一个固定的 `build/classes/`**，javac 原地覆盖，从不重建目录、从不删除
2. **依赖不解压**：新增 `tools/merge_jar.py`，直接用 `zipfile` 从源 jar 读条目、
   写进目标 jar，中间不落盘。省掉 1460 个文件 / 187 MB
3. **删除改用 `cmd //c rmdir //s //q`**：一次系统调用，而不是 4000 次 `rm`

> ⚠️ 写这个脚本时踩的坑：Git Bash 会把单字母参数 `/q` 当成盘符路径转换
> （`/q` → `Q:\`），cmd 就报 `Parameter format not correct - "Q:"`。
> 必须写成 `//q` 让 MSYS 先转成 `/q`。同理 `cmd //c` 而不是 `cmd /c`。

### 4. 变量名 `_here` 被 build.config.sh 覆盖

`scripts/build.config.sh` 内部也用 `_here` 这个变量名，source 之后会把调用方
的值覆盖掉。clean.sh 里改用 `DESKTOP_DIR` 才正常。**给这个项目写新脚本时注意。**

### 5. 构建脚本不能批量删文件

沙箱会把「一轮里删除超过 50 个文件」判成危险操作
（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`）。这和 Android 侧 `build.sh` 的约束是同一个。

不过第 3 条改完之后，正常构建路径已经**完全不需要删除**了 ——
`clean.sh` 只是用来收拾历史遗留。

---

## 硬性约束（沿用 Android 版）

1. 不引入任何 `.so` / 平台相关代码
2. **不得关闭 TLS 证书校验**
3. 令牌不写日志、不上传、不发给任何人
4. 任何让令牌离开本机的设计，必须先取得用户明确同意
5. 构建产物不落到 C 盘

---

## 已知限制

- **观战是只读的**：只能看焦点对局，不能选频道
- **在线对局只支持标准棋**：变体（Chess960 / 疯狂屋等）没有界面
- **找真人对手只有 Rapid 及更慢的档位**：Board API 用个人令牌时服务端限制
  （`limit + 40 × increment >= 480` 秒），这是协议约束不是 bug
- **没有棋谱导出**：`LichessApi.gamesPgn()` 已经写好了，只是还没接界面
- **界面尺寸按 1040×780 设计**，窗口可以缩放但棋盘最小 240px

---

## 与 Android 版的关系

两边**共用同一份内核**（`app/src/org/lichessold/{chess,json,net,util}`），
所以修内核的 bug 两边同时受益。改动内核之后建议两边都验一遍：

```bash
bash scripts/test.sh        # 桌面测试（棋规 / AI / 真实网络）
bash scripts/build.sh 0.8.0 # Android APK
bash desktop/build.sh       # 桌面版
```
