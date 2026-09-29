# 开发总计划 — Lichess for Android 2.3.6 (Samsung GT-S5360)

版本：v1.0 ｜ 制定日期：2026-09-28 ｜ 状态：待用户确认后执行阶段 0

---

## 0. 一句话结论

**不装 Gradle、不装 Android Studio、不依赖 CI。** 用「JDK 8 + Android build-tools 28.0.3 + 命令行 aapt/javac/d8/apksigner」这套最原始但最可控的工具链，全部装在 `E:\android 2.3.6 lichess dev\toolchain\`，一次构建约 10~20 秒，产出 < 5MB 的纯 Java（无任何 native 库）APK，ARMv6 上天然可跑。

---

## 1. 本机环境实测结果（2026-09-28）

| 项目 | 实测结果 | 影响 |
|---|---|---|
| JDK | **不存在**（`java`/`javac` 均 command not found） | 必须下载 JDK 8 |
| Android SDK | **不存在** | 必须下载 build-tools |
| Gradle | **不存在** | 不走 Gradle 路线 |
| adb | **不存在** | 与规格书一致：只能靠 App 内日志 + 用户反馈调试 |
| git | 有（2.55.0） | 可用于版本管理 |
| curl / unzip | 有 | 用于下载与解包 |
| Python | 有（3.13.12 managed） | 用于构建脚本辅助（zip 注入等） |
| C 盘剩余 | **5.6 GB**（已用 91%） | **严禁往 C 盘装任何东西** |
| E 盘剩余 | **55 GB**（已用 83%） | 项目与工具链全部落 E 盘 |

### 网络探测结果

| 目标 | 结果 |
|---|---|
| `dl.google.com`（build-tools） | ✅ 通 |
| `repo1.maven.org`（Maven Central） | ✅ 通 |
| `cdn.azul.com`（Zulu JDK 8 zip） | ✅ 通 |
| `mirrors.tuna.tsinghua.edu.cn`（Temurin 8） | ✅ 通 |
| `repo.huaweicloud.com`（JDK 8 安装包） | ✅ 通 |
| `api.adoptium.net`（官方 API） | ❌ 不通（超时）→ 改用镜像 |
| `lichess.org` | ✅ 通，**TLS 1.2 握手成功，无令牌返回 401**（符合预期） |

> ✅ **重要更正（2026-09-28 实测推翻）**：最初我探测 lichess.org 拿到 `CN=YE1 / CN=Root YE`，
> 以为本机出网被 TLS 中间人劫持。**用自写的 Spongy Castle TLS 客户端跑完整校验后证明这是错的**：
> 那条链是 Lichess 真实的 Let's Encrypt 链，用**未经修改的 Mozilla CA bundle 就能验证通过**
> （锚落在真实的 `ISRG Root X2` 上）。`YE1` / `Root YE` 是 Let's Encrypt 新的 ECDSA 层级名称。
>
> 教训：**不要用 curl/openssl 的中间结论下判断**，要用真正要部署的那套代码去验证。
> 这个误判差点让我把「内置完整 CA bundle」当成"绕开中间人"的手段，
> 而实际上它本来就是正确做法（抗 CA 变更）。
>
> 保留完整 Mozilla CA bundle（121 个根）的决定不变，理由更充分了：
> Lichess 的链随时可能换 CA，内置全量根才能长期不用改代码。

### 已完成的可行性验证（不是猜测）

| 验证项 | 结果 |
|---|---|
| `com.madgag.spongycastle:core:1.58.0.0` 是否含 TLS 实现 | ✅ **含** `org.spongycastle.crypto.tls.*` 共 176 个类，包含 `TlsClientProtocol`、`TlsClient`、`DefaultTlsClient`、`TlsUtils` |
| Spongy Castle 是否需要额外 `tls`/`pkix` 构件 | ❌ 不需要（Maven 上无 `tls`/`pkix` 1.58.0.0，TLS 就在 core 里） |
| `com.google.android:android:2.3.3`（Maven Central） | ⚠️ 已下载 5.88MB，含 resources.arsc 和 1249 个类，但 **一个 `java/*` 类都没有** → 只能当 aapt 的 `-I`，**不能当 javac 的 bootclasspath**（会报 `Unable to find package java.lang`）。**这是个坑，别再用它。** |
| `Sable/android-platforms` 的 API 10 `android.jar` | ✅ **采用这个**。9.9MB、2767 个类、864 个 `java/*`、209 个 `javax/*`、含 `resources.arsc` → 一份文件同时满足 javac 的 `-bootclasspath` 和 aapt 的 `-I` |
| `build-tools_r28.0.3-windows.zip` | ✅ 可下载 |
| `platforms;android-10` 官方 zip | ❌ 已从 Google 仓库下架（`android-10_r0*.zip` 全部 404），现存最老是 `android-14_r04` → 正是上面用 Sable 那份替代的原因 |

**这两条验证把规格书里最大的两个不确定性（TLS 库从哪来、API 10 平台包从哪来）都消除了。**

---

## 2. 构建方案选型

### 采用：纯命令行工具链（aapt + javac + d8 + apksigner）

理由：
1. **零联网构建**。Gradle 每次都要去 Maven 拉 AGP 及上百个传递依赖（几百 MB），且旧版 AGP 在 JDK 8 上的版本组合极易踩坑；命令行方案构建时完全不联网。
2. **占用最小**。C 盘只剩 5.6GB，Gradle 缓存（`~/.gradle`）默认写 C 盘，即使重定向也有风险；命令行方案总占用约 700MB，全在 E 盘。
3. **完全可控**。规格书第 B.4 条明确允许这条路线。
4. **本项目源码量小**（预计 25~35 个 `.java`），javac 全量编译仅需几秒，不需要增量构建。
5. **APK 结构完全透明**，便于排查「dex 方法数」「assets 是否被打进去」这类问题。

### 不采用（及原因）

| 方案 | 不采用原因 |
|---|---|
| Android Studio | 下载 > 3GB，默认装 C 盘，本机 C 盘放不下 |
| Gradle + AGP 3.6.4 | 依赖解析需联网 + 缓存写 C 盘 + 旧版组合易踩坑 |
| GitHub Actions CI | 需要 GitHub 账号与仓库，且令牌/日志隐私面更大。**保留为兜底方案**：若本地工具链在真机验证阶段发现系统性问题，再启用 |

---

## 3. 工具链清单（全部落盘 E 盘）

| 组件 | 版本 | 下载源 | 大小 | 落盘位置 |
|---|---|---|---|---|
| JDK | Azul Zulu **8.84.0.15 (8u442)**（x64 zip） | `cdn.azul.com/zulu/bin/zulu8.84.0.15-ca-jdk8.0.442-win_x64.zip` | ~104 MB | `toolchain/jdk8/` |
| JDK 备选源 | Temurin 8u504 | 清华镜像 `mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/` | ~105 MB | 同上 |
| Android build-tools | **28.0.3** | `dl.google.com/android/repository/build-tools_r28.0.3-windows.zip` | ~52 MB | `toolchain/android-build-tools/28.0.3/` |
| Android 平台包（API 10） | Sable/android-platforms 的 `android-10/android.jar` | GitHub raw | 9.9 MB | `toolchain/libs/android-2.3.3.jar` |
| TLS 库 | **Spongy Castle core 1.58.0.0** | Maven Central `com.madgag.spongycastle:core:1.58.0.0` | 3.1 MB | `toolchain/libs/sc-core-1.58.0.0.jar` |
| 根证书 | **Mozilla CA bundle (cacert.pem)** | `curl.se/ca/cacert.pem` | ~230 KB | `app/assets/cacerts.pem` |
| 签名工具 | keytool（随 JDK） + apksigner（随 build-tools） | — | — | — |

**总计下载量约 170 MB，磁盘占用约 400 MB（含解包）。** 全部在 E 盘，C 盘零写入。

> 补充说明：`build-tools 28.0.3` 里的 `d8` 支持 `--min-api 10`；若 d8 在 API 10 上出现异常，同一目录下的 `dx.bat` 作为备选（`dx --dex --min-sdk-version=10`）。

---

## 4. 目录结构（E:\android 2.3.6 lichess dev\）

```
E:\android 2.3.6 lichess dev\
├─ README.md                  项目总入口
├─ docs\                      所有文档
│   ├─ 00-SPEC.md             原始规格书（归档，不改）
│   ├─ 01-PLAN.md             本文件：开发总计划
│   ├─ 02-BUILD.md            构建环境与命令（怎么编译出 APK）
│   ├─ 03-PROGRESS.md         阶段进度 / 版本历史 / 每版变更
│   ├─ 04-BUGS.md             用户反馈的 bug 与修复记录
│   ├─ 05-USER-GUIDE.md       给零基础用户的傻瓜手册
│   └─ 06-TECH-NOTES.md       TLS / HTTP / Lichess API 技术笔记
├─ toolchain\                 【所有依赖】
│   ├─ downloads\             原始安装包（保留以便重装）
│   ├─ jdk8\                  JDK 8（解包后）
│   ├─ android-build-tools\28.0.3\   aapt / d8 / apksigner / zipalign
│   └─ libs\                  android-2.3.3.jar / sc-core-1.58.0.0.jar
├─ app\                       【源码】
│   ├─ AndroidManifest.xml
│   ├─ res\                   layout / values / drawable
│   ├─ assets\cacerts.pem     内置根证书
│   ├─ libs\                  sc-core-1.58.0.0.jar（编译期引用）
│   └─ src\org\lichessold\
│       ├─ App.java           入口，初始化日志与崩溃捕获
│       ├─ ui\                MainActivity / SettingsActivity / LogActivity
│       │                     / DiagActivity / GameActivity / BoardView
│       ├─ net\               TlsSocketFactory / CertVerifier / SimpleHttp
│       │                     / LichessApi / NdjsonReader
│       ├─ chess\             Board / MoveGen / Uci / Fen / GameState
│       ├─ log\               Log（环形缓冲+文件） / CrashHandler
│       └─ store\             Prefs（SharedPreferences 封装）
├─ build\                     编译中间产物（classes / dex / unsigned apk）
├─ dist\                      最终交付 APK（用户从这里拷到手机）
├─ keystore\                  签名密钥（固定不变，保证覆盖安装）
├─ scripts\                   build.sh（一键构建） / setup.sh（装工具链）
└─ logs\                      构建日志
```

**关键约定：所有脚本启动时都会设置**
```
JAVA_HOME=<E盘>\toolchain\jdk8
PATH=<E盘>\toolchain\jdk8\bin;<E盘>\toolchain\android-build-tools\28.0.3;<原PATH>
TMP / TEMP=<E盘>\build\tmp     ← 防止 javac/d8 往 C 盘临时目录写东西
```

---

## 5. 构建流程（脚本化，一键执行）

```
scripts/build.sh <版本号>
  1. aapt package -f -m -J build/gen -M app/AndroidManifest.xml -S app/res
                 -A app/assets -I toolchain/libs/android-2.3.3.jar
                 -F build/app-unsigned.apk --min-sdk-version 10 --target-sdk-version 10
  2. javac -source 1.7 -target 1.7 -encoding UTF-8
           -bootclasspath toolchain/libs/android-2.3.3.jar
           -cp app/libs/sc-core-1.58.0.0.jar
           -d build/classes  build/gen/**/*.java  app/src/**/*.java
  3. jar cf build/classes.jar -C build/classes .
  4. d8 --min-api 10 --release --lib toolchain/libs/android-2.3.3.jar
        --output build/dex  build/classes.jar  app/libs/sc-core-1.58.0.0.jar
  5. 把 build/dex/classes.dex 注入 build/app-unsigned.apk
  6. zipalign -f -p 4 build/app-unsigned.apk build/app-aligned.apk
  7. apksigner sign --ks keystore/lichessold.jks --min-sdk-version 10
        --v1-signing-enabled true  build/app-aligned.apk
  8. 复制到 dist/LichessOld-<版本号>-<构建时间>.apk，并打印 SHA-256 与体积
```

`javac -source 1.7 -target 1.7 -bootclasspath android-2.3.3.jar` 这一行是**硬性护栏**：它会让编译器在代码里出现任何 API 11+ 的调用时**直接报错**（比如 `java.util.Objects`、`SHA256withECDSA`、`Fragment`），从根上防止「在电脑上能编译、在真机上 NoSuchMethodError」。

---

## 6. 技术方案要点

### 6.1 TLS（最大风险项）— 已确定实现路径

| 环节 | 方案 |
|---|---|
| TLS 协议栈 | Spongy Castle `org.spongycastle.crypto.tls.TlsClientProtocol`，显式声明 `TLSv1_2`（含 TLS 1.0/1.1 回退协商） |
| SNI | 在 `TlsClient.getClientExtensions()` 中通过 `TlsUtils.createExtension_server_name(host)` 显式加入 `server_name` 扩展（API 10 系统 SSL 正是缺这个） |
| 密码套件 | 显式限定为 `ECDHE-RSA-AES128-GCM-SHA256`、`ECDHE-RSA-AES128-SHA256`、`ECDHE-RSA-AES128-CBC-SHA`、`AES128-GCM-SHA256` 等，兼顾 Let's Encrypt ECDSA 与 RSA 链 |
| 证书校验 | **自建 `CertVerifier`**，用 SC 的轻量密码学 API（`ECDSASigner` / `RSADigestSigner`）验签，**绕开 API 10 缺失的 `SHA256withECDSA`**；校验链、有效期、SAN 主机名。**绝不关闭校验** |
| 信任锚 | assets 内置完整 Mozilla CA bundle + 预置 Let's Encrypt 中间证书（E5/E6/R10/R11、ISRG Root X1/X2），应对服务端不下发中间证书的情况 |
| 证书更新 | assets 里是纯文本 PEM，换证书只需替换文件重新打包，README 记录方法 |

### 6.2 HTTP 客户端

自己写一个极简 HTTP/1.1（约 300 行）：
- GET / POST，`Authorization: Bearer <token>`，自定义 `User-Agent: LichessOld/0.x (Android 2.3.6)`
- `Transfer-Encoding: chunked` 解码；无 `Content-Length` 时按连接关闭读取
- **逐行（NDJSON）流式读取**，用 `org.json.JSONObject` 逐行解析，绝不一次性读完整响应
- 超时、429 退避（≥60 秒）、断线重连（指数退避 1→2→4→8→15→30 秒封顶）
- 所有网络调用在自管 `Thread` 中，UI 回调用 `Handler`

### 6.3 棋盘与规则

- `BoardView extends View`，`onDraw` 用 `Canvas` 画 8×8（每格 30px，棋盘 240×240 居中）
- 棋子：运行时用 `Canvas` 画 12 个 30×30 `Bitmap` 缓存（共约 43KB），不用 PNG 资源，省体积
- 规则：自写走子生成（含王车易位、吃过路兵、升变、将军/将死/逼和检测），不引第三方棋库（体积与方法数都不可控）
- 走子交互：点选 → 高亮可落子格 → 点落子 → 生成 UCI 串

### 6.4 日志与崩溃报告（无 adb 调试的生命线）

- `Log`：500 行环形缓冲 + `/sdcard/LichessOld/log.txt`（512KB 自动轮转）
- 脱敏：正则把 `Bearer <token>` 与 `lip_[A-Za-z0-9]+` 一律替换成 `***`，**写入前过滤**
- `CrashHandler`：`Thread.setDefaultUncaughtExceptionHandler` 写 `/sdcard/LichessOld/crash.txt`，下次启动弹窗提示
- 「网络诊断」页：DNS → TCP → TLS 握手（协议版本 / 证书链 / 是否发 SNI）→ HTTP 状态码，逐步骤显示成功或失败原因，**用户拍照即可反馈**
- SD 卡缺失兜底：检测 `/sdcard` 可写性，不可写时降级为内存日志并在界面明确提示

---

## 7. 阶段计划与验收标准

> 原则不变：**一次一个阶段，用户真机验证通过再进下一阶段。** 每阶段交付 `dist/` 下的 APK + 版本号 + 测试清单 + `docs/03-PROGRESS.md` 更新。

| 阶段 | 内容 | 交付版本 | 真机验收标准 |
|---|---|---|---|
| **0** | 项目骨架：目录、构建脚本、工具链、签名密钥、日志雏形、崩溃捕获、版本号显示 | `0.1.0` | 能装能开；显示「版本号 + Hello」；点「日志」看到启动记录；故意触发一次崩溃后重开能看到崩溃提示 |
| **1** | 网络诊断页 + 自带 TLS 客户端 + HTTP/1.1 + 根证书 | `0.2.0` | 诊断页逐项显示：DNS 成功 / TCP 成功 / TLS 握手成功（显示协议版本）/ 证书链校验通过（显示签发者）/ 收到 **HTTP 401**。失败时必须显示具体是哪一步挂了 |
| **2** | 令牌输入保存 + 「测试连接」显示用户名 | `0.3.0` | 显示自己的 Lichess 用户名；令牌错误有中文提示；日志里搜不到令牌明文 |
| **3** | 棋盘绘制 + 走子 + 完整规则 + 离线双人模式 | `0.4.0` | 离线能走完一整局；非法走子被拒绝并提示；320×240 上不溢出、不遮挡 |
| **4** | 事件流 + 对局流 + 走子 + 时钟 + 认输/和棋 + 断线重连（先「挑战 AI」） | `0.5.0` | 能和 Lichess AI 下完一局；手机走的子在 lichess.org 网页上可见；锁屏恢复后状态正确 |
| **5** | 稳定性与性能：内存、电量、错误提示中文化、长局压测 | `0.6.0` | 连续 20~30 分钟对局不崩、不 OOM；Wi-Fi 断连重连能恢复 |
| **6**（可选） | 公开匹配 / 观战 / 历史 / 本地 Stockfish（armeabi） | 逐项确认 | 逐项验收 |

**阶段 1 是全局最大风险点。** 如果真机诊断页显示 TLS 握手失败且无法解决，才启用规格书 D 节的备用方案（中转服务），且**必须先取得你的明确同意**。

---

## 8. 风险清单（基于实测更新）

| 风险 | 现状 | 对策 |
|---|---|---|
| TLS 1.2 + SNI 在 API 10 自实现失败 | 库已确认存在（SC core 含 TLS），但真机未验证 | 诊断页逐步定位；备用中转方案（需你同意） |
| Lichess 使用 ECDSA 证书，API 10 无 `SHA256withECDSA` | **已知风险** | 用 SC 轻量 API 自实现验签，不走 `java.security.Signature` |
| 服务端不下发中间证书 | 可能 | assets 预置 LE 中间证书 + 完整 CA bundle |
| 旧版构建工具链配置困难 | 已解决：纯命令行方案跑通，APK 已产出 | 若 build-tools 28 有问题，回退 build-tools 19.1 或改用 dx |
| **本机内存只有 4GB，空闲 <400MB，交换文件耗尽** | **已踩过**：d8 崩在 `Chunk::new` 原生内存分配失败 | 固定 `-Xmx320m -XX:TieredStopAtLevel=1`；d8 失败自动退回 dx；构建前关掉浏览器 |
| **API 10 平台包难找** | **已踩过**：Google 已下架；Maven 的 `android:2.3.3` 无 `java.*` | 改用 Sable/android-platforms 的完整 android.jar；setup.sh 加了 `java/lang/Object.class` 自检 |
| 内存不足 / OOM | 堆约 24~32MB | 小位图、无大缓存、日志记录堆峰值；SC 类不预加载 |
| APK 体积 > 5MB | SC core 1812 个类 | 先测实际体积；超标则引入 ProGuard 只保留 TLS + 用到的密码算法 |
| Lichess API 将来变动 | 长期 | 日志记录 HTTP 状态与错误体；README 记录 API 依赖点 |
| 用户手机无 SD 卡 / `/sdcard` 不可写 | 未知 | 启动时检测并降级 + 界面提示 |
| 用户无法提供有效日志 | 未知 | 崩溃自动报告、日志一键保存、诊断页拍照即可 |
| 本机出网曾有 TLS 中间人误判 | 已推翻：是 Lichess 真实 Let's Encrypt 链，Mozilla bundle 可验过 | 用自写 TLS 客户端实测确认；完整 CA bundle 照旧内置 |

---

## 9. 已确认事项（2026-09-28 用户答复）

| # | 问题 | 答复 |
|---|---|---|
| 1 | 是否同意在 E 盘下载工具链并开始阶段 0 | ✅ 同意，已执行完毕 |
| 2 | App 名称与包名 | 显示名 **`lichess`**，包名 **`org.lichessold`** |
| 3 | 手机是否插了 microSD 卡 | ✅ 有卡，日志走 `/sdcard/LichessOld/` |
| 4 | 是否用 GitHub 管理/分发 | 之后再说，当前 APK 放 `dist/`，数据线拷进手机 |

另外提醒：**Lichess API 令牌不要发给 Agent，也不要在聊天里贴。** App 装好后在手机上的输入框里填，只存在手机本地。
