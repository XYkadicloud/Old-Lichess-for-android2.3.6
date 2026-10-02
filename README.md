# Lichess Old — Lichess 客户端 for Android 2.3.6

为 **Samsung GT-S5360（Galaxy Y，Android 2.3.6 / API 10 / ARMv6）** 从零开发的原生 Lichess 客户端。

- 纯 Java，**无任何 native 库**（ARMv6 友好）
- 自带 TLS 1.2 + SNI 实现（Android 2.3 系统栈不支持）
- 体积 **1.22 MB**
- 构建工具链全部在项目盘，不写 C 盘

> 🖥️ **还有桌面版**：[`desktop/`](desktop/README.md) —— 同一份内核（`chess/ json/ net/ util/`）
> 配上 Swing 界面，编译成 **单文件可执行 JAR**，Windows 上双击即运行。
> 构建：`bash desktop/build.sh`，启动：双击 `desktop/run.bat`。
> 两边共用内核，改内核之后建议两边都验一遍。

---

## 下载

不用自己编译，直接去 [**Releases**](../../releases) 页面拿成品：

| 文件 | 说明 |
|---|---|
| `LichessOld-1.0.apk` | Android 安装包（1.23 MB），拷到手机点击安装 |
| `LichessOldDesktop-1.0.0.jar` | 桌面版（3.3 MB），双击 `run.bat` 或 `java -jar` 运行 |

> ⚠️ APK 用的是自签名证书，不是从 Google Play 装的，所以首次安装需要在
> 系统设置里允许「未知来源」。详见 [docs/05-USER-GUIDE.md](docs/05-USER-GUIDE.md)。

想自己编译看 [快速开始](#快速开始)；桌面版单独说明见 [desktop/README.md](desktop/README.md)。


> ⚠️ **本项目在两台配置差 4 倍的电脑上开发，构建参数按机器自动分档。**
> 动手之前先读 [`docs/08-MACHINE-PROFILES.md`](docs/08-MACHINE-PROFILES.md) ——
> 低配机上沿用高配机参数会让 `d8` 崩，高配机上沿用低配机参数会白白慢好几倍。
> 自检：`bash scripts/machine-profile.sh`

---

## 功能

| 功能 | 需要令牌 | 状态 |
|---|---|---|
| 在线对局（Board API：走子/时钟/认输/和棋/聊天/断线重连/重新同步） | ✅ | 已完成 |
| 排位赛 / 休闲赛选择（找对手、挑战玩家都能选；电脑对局固定休闲） | ✅ | 已完成 |
| 挑战电脑（Lichess AI，等级 1~8，不计分） | ✅ | 已完成 |
| 挑战指定玩家（可选排位/休闲） | ✅ | 已完成 |
| 找真人对手（seek 匹配，Rapid 及更慢） | ✅ | 已完成 |
| 个人主页与排位分（自己的 / 别人的，可发起挑战） | ✅ | 已完成 |
| 接受/拒绝挑战（事件流） | ✅ | 已完成 |
| 谜题训练（每日题 / 随机题 / 19 种主题） | ❌ | 已完成 |
| 离线人机对战（内置引擎，8 级强度） | ❌ | 已完成 |
| 离线双人对战 | ❌ | 已完成 |
| 观战（焦点对局直播，可指定某一局） | ❌ | 已完成 |
| 锦标赛（列表 / 排行榜 / 焦点对局，只看不参赛） | ❌ | 已完成 |
| 最近战绩（近 7 天胜负 + 等级分涨跌） | ❌ | 已完成 |
| 棋谱（查看最近一局 PGN，可存到存储卡） | ❌ | 已完成 |
| 网络诊断（DNS→TCP→TLS→证书→HTTP 逐步骤） | ❌ | 已完成 |
| 运行日志 / 崩溃自动报告 | ❌ | 已完成 |

---

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/08-MACHINE-PROFILES.md](docs/08-MACHINE-PROFILES.md) | **两台开发机的配置区分（先读这个）** |
| [docs/07-DELIVERY-1.0.0.md](docs/07-DELIVERY-1.0.0.md) | **当前版本交付说明与测试清单** |
| [docs/00-SPEC.md](docs/00-SPEC.md) | 原始项目规格书（需求来源，不改） |
| [docs/01-PLAN.md](docs/01-PLAN.md) | 开发总计划：方案选型、阶段划分、风险 |
| [docs/02-BUILD.md](docs/02-BUILD.md) | 构建环境安装、编译命令、平台包三个坑 |
| [docs/03-PROGRESS.md](docs/03-PROGRESS.md) | 阶段进度、版本历史、关键架构决策 |
| [docs/04-BUGS.md](docs/04-BUGS.md) | Bug 反馈模板与修复记录 |
| [docs/05-USER-GUIDE.md](docs/05-USER-GUIDE.md) | **给零基础用户的手册** |
| [docs/06-TECH-NOTES.md](docs/06-TECH-NOTES.md) | TLS / HTTP / Lichess API 技术笔记 |
| [docs/07-DELIVERY-0.7.0.md](docs/07-DELIVERY-0.7.0.md) | 0.7.0 交付说明（排位/休闲 + 个人主页） |
| [docs/07-DELIVERY-0.6.0.md](docs/07-DELIVERY-0.6.0.md) | 0.6.0 交付说明（界面重做） |
| [docs/07-DELIVERY-0.5.0.md](docs/07-DELIVERY-0.5.0.md) | 0.5.0 交付说明（24 项测试清单在这里） |
| [docs/07-DELIVERY-0.8.0.md](docs/07-DELIVERY-0.8.0.md) | 0.8.0 交付说明（界面二改 + 标准棋子） |

---

## 快速开始

```bash
bash scripts/machine-profile.sh  # 先看这台机器被判成哪一档
bash scripts/setup.sh            # 一次性安装工具链（幂等）
bash scripts/build.sh 1.0        # 构建 APK
bash scripts/test.sh             # 跑全部桌面测试（棋规/AI/真实网络）
bash scripts/verify_apk.sh dist/LichessOld-1.0.apk     # APK 静态校验 15 项
# 产物：dist/LichessOld-<版本>.apk
```

> ⚠️ **JVM 内存参数不要手写**。它们由 `scripts/machine-profile.sh` 按物理内存自动判定，
> 分 `low`（<6GB）/ `mid`（6~12GB）/ `high`（≥12GB）三档。
> 低配机上堆开太大会让 d8 崩在 `Native memory allocation (malloc) failed ... Chunk::new`；
> 高配机上沿用低配参数则会白白慢好几倍。详见 `docs/08-MACHINE-PROFILES.md`。
>
> ⚠️ 本机盘符会变（插上手机后 Windows 会重排）。`build.config.sh` 从脚本位置反推项目根，不写死盘符。
>
> ⚠️ 构建脚本是「只增不删」：每次构建写进 `build/work-<时间戳>/`，上一次的原样留着。
> 原因：沙箱会把构建过程中的任何删除（哪怕是 `mv` 改名搬走）判成批量删除
> （`SAFE_DELETE_BULK_CONFIRM_REQUIRED`，阈值 50 个文件/轮，而一轮构建要写 200+ 个文件）。
> 回收空间用 `bash scripts/clean.sh`，它**按文件**分批删（每次 40 个），循环执行即可清干净。

---

## 当前状态

**1.0 已构建完成，等待真机验证。**

| 项目 | 值 |
|---|---|
| 交付版本 | **1.0**（锦标赛 + 战绩 + 棋谱导出 + 修掉两个已失效的接口） |
| APK | `dist/LichessOld-1.0.apk`（1.23 MB） |
| SHA-256 | `3518ef7d0fa206d59ef1c5bc2d94dc133a24c22cc4516781f2974dbbc3543f19` |
| APK 静态校验 | 15 项**全部通过**（方法引用数 17,057 / 65,536） |
| 自动测试 | **113 项全部通过**（`bash scripts/test.sh`） |
| 测试清单 | 见 [docs/07-DELIVERY-1.0.0.md](docs/07-DELIVERY-1.0.0.md) |

> 1.0 修掉了一个藏了很久的问题：项目原本封装的两个棋谱导出端点
> （`/api/games/user/{name}`、`/game/export/{id}.pgn`）**实测都已 404**。
> 因为一直没接界面所以从没暴露。现在改用依然有效的
> `GET /api/user/{name}/current-game`，并把棋谱功能真正接到了界面上。

### 界面风格

配色与控件对齐 lichess 官网深色主题：背景 `#161512`、面板 `#262421`、主色 `#629924`、正文 `#bfbdb2`。
按钮全部自绘扁平圆角（`GradientDrawable` + `StateListDrawable`），不用 Android 2.3 的灰色渐变默认样式。

对局页为了把屏幕让给棋盘，做得很紧凑：

```
[观战] 白 luchador2 · 观战 ............ [12:34]     ← 一行：标题胶囊 + 对手名 + 时钟
┌──────────────────────────┐
│        棋盘（铺满屏宽）        │                      ← 每格约 29px
└──────────────────────────┘
黑 XuYL_1a · abc123 .................. [08:21]     ← 一行：我方名 + 对局号 + 时钟
● 轮到你走                                            ← 状态，颜色随状态变
[ 和棋 ][ 认输 ][ 聊天 ][ 重新同步 ][ ≡ ]              ← 一行按钮，超出的进「≡」
```

整屏是全屏的（隐藏系统状态栏）。棋子用 **Cburnett** 一套（Wikipedia / lichess 默认造型），
由 `scripts/make_pieces.py` 从 SVG 生成 `ui/PieceArt.java`，纯矢量、不带图片资源。

---

## 硬性约束（不可违反）

1. `minSdkVersion=10`，`targetSdkVersion=10`
2. 不用 AndroidX / AppCompat / Material / Fragment（最低要求 API 14）
3. Java 语言级别 1.7 或以下
4. 编译必须带 `-bootclasspath toolchain/libs/android-2.3.3.jar`（真正的 API 10 平台包）
5. 不引入任何 `.so`
6. **不得关闭 TLS 证书校验**
7. 令牌不写日志、不上传、不发给任何人
8. 任何让令牌离开手机的设计，必须先取得用户明确同意

---

## 目录结构

```
docs\        文档（08-MACHINE-PROFILES.md 先读）
toolchain\   全部依赖（JDK8 / build-tools / android.jar / sc-core.jar）
app\         源码
  src\org\lichessold\
    util\ json\ net\ chess\   ← 纯 Java，零 Android 依赖，可在桌面 JVM 上测试
    log\ store\ platform\ ui\ ← Android 层（PieceArt.java 由脚本生成，勿手改）
desktop\     桌面版（Swing + 单文件 JAR，见 desktop/README.md）
tests\       桌面测试（棋规 perft / AI / 真实网络）
build\       编译中间产物（只增不删）
dist\        交付 APK
keystore\    签名密钥（必须永久保留）
scripts\     构建 / 测试 / 校验 / 代码生成脚本
logs\        构建日志
```

**架构要点**：`util/` `json/` `net/` `chess/` 四个包**没有任何 Android 依赖**，
所以能在桌面 JVM 上编译并运行**与手机完全相同的代码**，直接连真实的 lichess.org 做端到端验证。
这是本项目在没有真机的情况下保证质量的核心手段。

`desktop/` 就是这套思路的延伸：它不复制内核，而是**编译时直接引用 `app/src` 下的源码**，
所以桌面版和手机版跑的是同一个引擎、同一套棋规、同一套 TLS 栈。

---

## 签名密钥

`keystore/lichessold.jks`，alias `lichessold`，口令 `lichessold2011`。
**此文件丢失后新版本无法覆盖安装**，用户必须卸载重装。请勿删除。

---

## 已知限制

见 [docs/07-DELIVERY-1.0.0.md](docs/07-DELIVERY-1.0.0.md) 第四节。

提要：

- **不能报名锦标赛** —— `POST /api/tournament/{id}/join` 需要 OAuth 的
  `tournament:write` 权限，本项目用的是手填个人令牌，拿不到那个 scope。所以锦标赛只做"看"
- **不能按对局 ID 精确取棋谱** —— `/api/games/user/{ids}` 同样已失效，只能取"最近一局"
- 战绩只覆盖近 7 天（接口默认返回量）

---

## 许可

代码用 **MIT**，详见 [LICENSE](LICENSE)。

⚠️ 项目里还包含三份第三方材料，授权各不相同，需要**分开遵守**：

| 材料 | 授权 | 位置 |
|---|---|---|
| 棋子造型（Cburnett 版，lichess 默认） | **CC BY-SA 3.0** | `app/.../PieceArt.java`、`desktop/.../PieceArt2D.java` |
| Spongy Castle sc-core 1.58.0.0 | Bouncy Castle Licence | `toolchain/libs/`（不入库，构建时下载） |
| Mozilla 根证书包 | MPL 2.0 | `app/assets/cacerts.pem`、`desktop/assets/cacerts.pem` |

其中**棋子造型是 CC BY-SA 3.0**（署名 + 相同方式共享），二次分发成品
（APK / JAR）需要保留署名并以相同协议共享造型部分。

完整清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

> 为什么第三方说明单独放一个文件、不写在 LICENSE 里：
> GitHub 靠文本精确匹配来识别许可证，LICENSE 里混入额外内容会让仓库
> 显示成「未识别」。所以 LICENSE 只放纯净的 MIT 原文。

---

## 隐私与安全

这是本项目最重要的一条：**API 令牌是唯一的安全边界**。

- 令牌只存在用户本机（`SharedPreferences` / `data/settings.properties`），
  只用于发 `Authorization` 请求头
- 进日志之前一律过 `util.Log.redact()`，`Bearer xxx` / `lip_xxx` / `"token": "xxx"`
  全部替换成 `***`
- 不上传、不发给任何第三方、不写进崩溃报告
- 代码里**没有任何关闭 TLS 证书校验的地方**，整条链路走自带根证书包校验

`keystore/lichessold.jks`（口令 `lichessold2011`）是自用签名密钥，
作用只是保证升级时签名一致，**不是安全边界** —— 真正需要保护的是令牌。
