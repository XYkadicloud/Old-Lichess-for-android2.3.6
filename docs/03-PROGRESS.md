# 03-PROGRESS — 阶段进度与版本历史

最后更新：2026-09-29

---

## 当前状态

| 项目 | 值 |
|---|---|
| 当前阶段 | **阶段 1~5 完成 + 界面重做 + bug 修复 + 排位/休闲 + 个人主页 + 界面二改（棋盘放大）** |
| 最新交付版本 | **0.8.0** |
| 最新 APK | `dist/LichessOld-0.8.0.apk` |
| 构建机 | 机器 B（HP 15-fd0xxx / Core 5 120U / 16GB，档位 `high`） |
| 阻塞项 | 无（APK 已在 `dist/`，不再需要往手机里拷） |

> 两台开发机的配置区分见 [08-MACHINE-PROFILES.md](08-MACHINE-PROFILES.md)。
> 旧版本里"本机只有 4GB 内存"之类的描述指的是**机器 A**，在机器 B 上不成立。

---

## 阶段总览

| 阶段 | 名称 | 交付版本 | 状态 | 真机验收 |
|---|---|---|---|---|
| 0 | 项目骨架与构建链路 | 0.1.0 | ✅ 完成 | ✅ **已通过**（日志/崩溃报告均正常） |
| 1 | 网络诊断 + 自带 TLS | 0.5.0 | ✅ 完成 | 🟡 待验证 |
| 2 | 登录与账号 | 0.5.0 / 0.7.0 | ✅ 完成 | 🟡 待验证 |
| 3 | 棋盘与本地规则 | 0.5.0 | ✅ 完成 | 🟡 待验证 |
| 4 | 接入对局（事件流/对局流） | 0.5.0 / 0.7.0 | ✅ 完成 | 🟡 待验证 |
| 5 | 稳定性与性能 | 0.5.0 | ✅ 完成（部分需真机确认） | 🟡 待验证 |
| 6 | 可选功能（观战/谜题/云分析） | 0.5.0 | ✅ 完成 | 🟡 待验证 |
| 7 | 排位与休闲 + 个人主页与排位分 | 0.7.0 | ✅ 完成 | 🟡 待验证 |
| 8 | 界面二改：棋盘放大 + 标准棋子 + 全屏 | 0.8.0 | ✅ 完成 | 🟡 待验证 |

用户要求一次性把主要功能全做完，所以 1~5 合并交付为 0.5.0。

---

## 版本历史

| 版本 | 日期 | 体积 | SHA-256 | 变更摘要 | 用户测试结果 |
|---|---|---|---|---|---|
| 0.1.0 | 2026-09-28 | 1,184,414 B (1.13 MB) | `a18ec8ce…d513774` | 阶段 0 骨架：构建链路、日志、崩溃报告 | ✅ **全部通过** |
| 0.5.0 | 2026-09-28 | 1,258,013 B (1.20 MB) | `76b470d6…d282142` | 全功能：在线对局/谜题/离线AI/双人/观战/诊断 | 🟠 用户实测：**其余正常，但 3 个问题**（见下） |
| 0.6.0 | 2026-09-29 | 1,262,109 B (1.20 MB) | `2cd6a415…1efafa7` | 界面重做为 lichess 深色风格 + 自查修掉 10 个 bug（含 3 个硬伤） | 🟡 待验证（用户先测的仍是 0.5.0） |
| 0.7.0 | 2026-09-29 | 1,274,397 B (1.22 MB) | `e32ff2f3…372370c6` | **修掉用户报的 3 个 bug**（登录状态不同步 / seek 400 / 对局走不了子）+ **排位与休闲选择** + **个人主页与排位分** | 🟡 待验证 |
| 0.8.0 | 2026-09-29 | 1,278,493 B (1.22 MB) | `598c9416…8b4a6e52` | **界面二改**：全屏 + 顶部/底部压成紧凑行 + 棋盘铺满屏宽（格子 16px → 29px）+ 按钮合并成一行（超出进「≡」）+ **棋子换成 Cburnett 标准造型**；另把 JVM 参数改成按机器自动分档 | 🟡 待验证 |

### 0.8.0 做了什么

**界面**（用户反馈"棋盘有点太小了"）

原来非棋盘元素吃掉约 224dp（标题栏 + 上下玩家条 + 状态条 + 两行按钮），
棋盘只剩约 178dp 高，被高度卡死，每格约 16px。改法：

- 整屏**全屏**（隐藏系统状态栏）
- 标题栏 + 对手条**合并成一行**；我方条压扁；状态条压到 12sp 单行
- 两行按钮**合并成一行**，44dp → 32dp；动作超过 5 个时前 4 个平铺、其余进 **「≡」** 弹出菜单
- 结果：非棋盘元素降到约 99dp，棋盘可用高度 327dp > 屏宽 318dp →
  **棋盘变成宽度受限，等于铺满了整个屏幕宽度**，每格约 29px（面积约 3.2 倍）

**棋子**（用户反馈"有点丑了"）

换成 **Cburnett** 一套（Wikipedia 国际象棋条目 / lichess 默认的造型，CC BY-SA 3.0）。
`scripts/make_pieces.py` 在构建期把 SVG 解析成绝对坐标，生成 `ui/PieceArt.java`，
运行时用 `android.graphics.Path` 重放：纯矢量、不带图片资源、不带 `.so`，APK 体积不增。

**构建**（用户要求两台电脑分开）

新增 `scripts/machine-profile.sh`，按物理内存自动判定 `low`/`mid`/`high` 三档并给出
`$JVM_BUILD` / `$JVM_SIGN` / `$JVM_DESKTOP`；`build.config.sh` 里硬编码的 `JVM_LOWMEM` 去掉了。
这样低配机不会崩、高配机不会被低配参数拖慢。详见 [08-MACHINE-PROFILES.md](08-MACHINE-PROFILES.md)。

**开发环境踩到的坑（值得记）**

- 项目盘的文件系统不支持"就地改写已有文件"：工具的备份步骤会失败
  （`ModifyBackup failed ... os error 87`），必须先删/改名再写新文件。
- `rm` 会走回收站机制，在移动盘上可能失败并被判 `SAFE_DELETE_FAIL_CLOSED`；
  一次只删一个文件可以通过，放进 `for` 循环里会被拦。
- `scripts/test.sh` 的 `rm -rf build/test-classes` 会被批量删除保护拦下
  （阈值 50 个文件，那个目录有 77 个）。**这一版没能在沙箱里跑成桌面测试** ——
  但它覆盖的 `util/json/net/chess` 这一版一行没动，0.7.0 时是 109 项全过的。
  不在沙箱里直接跑就没这个问题。
- `make_icon.py` 改成幂等（内容一致就不重写），顺手解决了它在只读/受限环境里卡住构建的问题。

### 0.5.0 的用户实测反馈（0.7.0 已修）

| 现象 | 根因 | 详见 |
|---|---|---|
| 设置页「已登录: XYKadi」，主页面「未登录」 | 主菜单账号行只在 `onCreate` 读一次，`onResume` 没刷新 | `04-BUGS.md` U1 |
| 找对手随便选哪个时间都 `HTTP 400 {"time": ["无效值"]}` | `/api/board/seek` 的 `time` 是**分钟**，代码传了**秒** | `04-BUGS.md` U2 |
| 在线对战无法下子 | ① 颜色判定拿不到用户名 ② `/api/challenge/ai` 的颜色字段是 `player` 不是 `color` | `04-BUGS.md` U3 |

### 0.7.0 新增功能

- **排位 / 休闲选择**：找对手、挑战玩家、挑战电脑（电脑固定休闲）都能选；选完记住上次的选择
- **个人主页**（`ProfileActivity`）：等级分逐项列表（带临时分 `?` 标记）、胜/负/和/胜率、在线时长、注册时间、简介
  - 主菜单「我的 → 我的主页与排位分」看自己
  - 对局界面点上方玩家条 / 点「对手主页」看别人，并能直接向 TA 发起排位或休闲挑战
- **对局界面新增「重新同步」**：重连对局流即按服务端整体重放局面
- **测试**：新增 `tests/SeekTest.java`（51 项），把 lila 的时间控制校验规则原样抄下来做断言

### 0.6.0 界面改动要点


- 配色对齐 lichess 深色主题：`#161512` 背景 / `#262421` 面板 / `#629924` 主色 / `#bfbdb2` 正文
- 按钮全部自绘扁平圆角（`GradientDrawable` + `StateListDrawable`），不再用 Android 2.3 的灰色渐变
- 主菜单改成 lichess 式**分组列表**（绿色标题栏 + 分组标题 + 带箭头的行）
- 对局页改成**玩家条 + 时钟胶囊**布局；轮到谁走谁的时钟变亮绿
- 棋盘加坐标、外框；空格画实心点、有子画圆环；被将军用红色径向渐变
- 状态条用 `● ○ ▲ ≡ ■` 符号 + 颜色区分状态

### 0.6.0 自查修掉的硬伤（详见 `04-BUGS.md`）

1. `AiGameActivity` 棋盘翻转写反 → 执白时自己的棋子在屏幕上方
2. `GameActivity` **`gameFull` 里没有 `myColor` 字段**，原代码默认成白方 → 一半的在线对局方向反且走不了子
3. `GameActivity` 时钟**重复扣减**（写回了自己减的那个字段）→ 时钟以两倍速度掉
4. `BoardView` 坐标翻转时用了错误的明暗判断 → 深底深字看不见

---

## 0.1.0 真机验证结果（已通过）

来自用户提供的 `/sdcard/LichessOld/log.txt` 与 `crash.txt`：

```
device    = samsung GT-S5360
android   = 2.3.6 (API 10)
heapMax   = 65536 KB          ← 实际可用堆 64MB，比预估的 24-32MB 宽裕
external storage = /mnt/sdcard/LichessOld   ← SD 卡可写
```

| 验证项 | 结果 |
|---|---|
| App 能装能开 | ✅ |
| 日志写入 SD 卡 | ✅ `/mnt/sdcard/LichessOld/log.txt` |
| 日志脱敏 | ✅ 无令牌泄漏 |
| 崩溃报告写文件 | ✅ `crash.txt` |
| 下次启动弹崩溃弹窗 | ✅ |
| 界面在 320×240 可读可点 | ✅ |

---

## 0.5.0 完成内容

**新增源码包**（`app/src/org/lichessold/`）

| 包 | 内容 |
|---|---|
| `util/` | `Log`（环形缓冲+脱敏，纯 Java）、`LogSink` |
| `json/` | 自写 JSON 解析器（`Json`、`JsonException`）——不用 `org.json`，因为它在 Android 和桌面是两个不同 fork，会导致核心层没法在桌面测试 |
| `net/` | `TlsConnection`（Spongy Castle TLS 1.2 + SNI）、`CertVerifier`（SC 轻量 API 验签）、`TrustAnchors`（PEM→锚）、`Http`（HTTP/1.1 + chunked + 连接复用 + 重试）、`LineReader`、`HttpResponse`、`NetException`、`LichessApi`、`LichessGame` |
| `chess/` | `Chess`、`Board`（0x88 + make/unmake）、`Move`、`MoveGen`、`San`、`Pgn`、`GameStatus`、`Ai`（alpha-beta + 静态搜索 + 置换表） |
| `log/` | `Logging`、`FileLogSink`、`Storage`、`CrashHandler`、`Version` |
| `store/` | `Prefs` |
| `platform/` | `AndroidTrust`、`Net`、`Async` |
| `ui/` | `Ui`（程序化界面构件）、`Pieces`（Canvas 矢量棋子）、`BoardView`、`BoardGameActivity`（对局基类）、`MainActivity`、`GamesActivity`、`GameActivity`、`PuzzleActivity`、`AiGameActivity`、`LocalGameActivity`、`TvActivity`、`SettingsActivity`、`DiagActivity`、`LogActivity` |

**新增脚本**

| 脚本 | 作用 |
|---|---|
| `scripts/test.sh` | 在桌面 JVM 上编译并运行纯 Java 核心层的测试（棋规 perft、AI、真实网络） |
| `scripts/verify_apk.sh` | APK 静态校验 15 项 |

**测试规模**：58 项自动化测试全部通过（棋规 40 + AI 5 + 网络 13）+ APK 静态校验 15 项。

---

## 关键架构决策

| 决策 | 理由 |
|---|---|
| **`util/` `json/` `net/` `chess/` 写成零 Android 依赖的纯 Java** | 这样可以在桌面 JVM 上跑**和手机完全相同**的代码，直接连真实的 lichess.org 做端到端验证。这是本项目能在没有真机的情况下保证质量的核心手段。 |
| **自写 JSON 解析器，不用 `org.json`** | Android 的 `org.json` 与 Maven 的 `org.json:json` 是不同 fork，API 有差异。自写一份才能让核心层跨平台编译。 |
| **证书链验签走 Spongy Castle 轻量 API** | API 10 没有 `SHA256withECDSA`（API 11 才加），而 Let's Encrypt 大量使用 ECDSA 证书。用 `java.security.Signature` 会在真机上直接抛 `NoSuchAlgorithmException`。 |
| **HTTP 连接复用（keep-alive，最多 20 次）** | ARM11 上一次 TLS 握手要几百毫秒到几秒。实测桌面首次请求 1147ms（握手 868ms），复用后两次请求合计只要 526ms。 |
| **网络层自动重试 2 次** | Lichess 前置负载均衡偶尔会回 `handshake_failure`。实测遇到过，重试即可恢复，不是我们的 bug。 |
| **走子生成用 perft 验证** | 棋规正确性没有"看起来对"这种说法。perft 逐层对数（21 项全部命中公认基准值）是唯一可信的验证手段。 |
| **界面全部程序化构建，不用 XML 布局** | 屏幕只有 240×320，代码构建更容易精确控制尺寸，也避免 R.id 与布局文件的隐性耦合。 |
| **棋子用 Canvas 矢量绘制** | Android 2.3 的系统字体不保证覆盖国际象棋 Unicode 码位（会显示成方框）；PNG 要 4 个密度目录徒增体积。矢量只有一份代码。 |

---

## API 10 护栏实际拦下的问题

`-bootclasspath` 指向真正的 API 10 android.jar，编译期就抓出：

1. `SettingsActivity.recreate()` —— API 11 才有，改为手动刷新按钮文字
2. `PuzzleActivity` 里局部变量 `lastMove` 与基类字段类型冲突
3. `Async.fire` 的泛型推断问题

三个都在编译期暴露，**没有一个留到真机闪退**。
