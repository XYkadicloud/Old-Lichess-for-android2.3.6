# 04-BUGS — Bug 反馈与修复记录

> 用户报 bug 用下面模板；Agent 每条 bug 都必须在本文档留一条记录。

---

## 给用户的 Bug 报告模板（复制填写）

```
版本号：（App 设置页会显示，如 0.6.0）
问题一句话：
第几项测试：（对应交付说明里的测试清单编号）
操作步骤：
1.
2.
3.
实际发生了什么：
你期望发生什么：
是否每次都出现：（每次 / 偶尔 / 只有一次）
当时网络：（Wi-Fi / 断网）
日志：（见下）
截图/拍照：（有则附上）
```

### 怎么拿到日志（二选一）

1. **手机上直接看**：App → `运行日志` → 滚动查看，拍照发我。
2. **保存成文件**：App → `运行日志` → 点 `保存到存储卡` → 生成 `/sdcard/LichessOld/log.txt`
   → 用数据线连电脑（选「USB 存储」模式）→ 把 `LichessOld` 文件夹整个拷出来发我。

**网络出问题时**：先去 `网络诊断` 点「开始诊断」，那一页会逐步骤显示卡在哪一步，拍照最有价值。

**闪退时**：App 下次打开会自己弹「上次崩溃报告」，直接拍照或保存后发我。

> 日志已做脱敏，`Bearer` 之后的令牌内容会显示成 `***`。但为稳妥，发我之前自己扫一眼，
> 看到 `lip_` 开头的字符串就删掉那一行。

---

## 用户报过的 Bug

| # | 版本 | 报告日期 | 现象 | 根因 | 修复内容 | 验证方式 | 状态 |
|---|---|---|---|---|---|---|---|
| U1 | 0.5.0 | 2026-09-29 | 设置页显示「已登录: XYKadi」，主页面却显示「未登录（去「设置」填令牌）」 | 主菜单的账号行只在 `onCreate` 里读一次 `Prefs.getUsername()`；`onResume` 只刷新了调试开关，**没有刷新账号行文字**。而用户名是「测试连接」成功后才写进本地的，用户在设置页操作完返回时，主菜单还是老画面 | ① `Theme.rowRef()` 把行里的两个 TextView 交出来；`MainActivity` 持有 `accountRow`，在 `onResume` 里重刷；② 有令牌但没用户名时自动请求 `/api/account` 补齐；③ 设置页「保存令牌」后自动跑一次测试连接（以前要用户再点一次） | `SettingsActivity` 保存令牌 → 返回主菜单，账号行立刻显示用户名 | ✅ 已修（0.7.0） |
| U2 | 0.5.0 | 2026-09-29 | 对战大厅「寻找真人对手」随便选哪个时间都报 `寻找对手失败: 流式请求失败:HTTP 400 {"time": ["无效值"],"error":{"time":["无效值"]}}` | **单位搞错**。`POST /api/board/seek` 的 `time` 是**分钟**（Double，0~180），代码传的是**秒**。`TIME_VALUES` 里 300/600/900/1800 全部超过上限 180 → 服务端直接判 `time` 无效。9 个档位里 6 个必然失败，而弹窗默认高亮的正好是失败的 `10+0`（600） | ① `LichessApi.seek()` 改传分钟，新增 `formatMinutes()`；② 新增纯 Java 的 `net/SeekOptions.java` 承载时间档 + lila 校验规则镜像；③ **另一个坑**：个人令牌用 Board API 时服务端只允许 **Rapid 及更慢**（`isBoardCompatible` = `Speed >= Rapid`，即 `limit + 40*inc >= 480` 秒），所以档位表整体上移到 8+0 起 | `tests/SeekTest.java` 51 项断言，逐档按 lila 的 `validateTime` / `isBoardCompatible` 验一遍；含「0.5.0 的旧秒值有 6 个会被拒」的回归用例 | ✅ 已修（0.7.0） |
| U3 | 0.5.0 | 2026-09-29 | 在线对战时无法正常下子 | 同 U2 表里的自查 #2，但**还有第二个原因**：`/api/challenge/ai` 的响应里颜色字段叫 **`player`**，0.5.0 读的是 `color`（该端点没有这个字段），于是拿不到颜色 → 默认白方 → 执黑时 `isMyTurn()` 恒为假 → 一步都走不了 | ① `LichessApi.myColorFromChallenge()` 优先读 `player`，兼容 `color`；② `GameActivity.resolveMyColor()` 三级推断：Intent 颜色 → 用户名比对 → **AI 对局反推**（我们一定不是 AI 那一边）；③ 用户名缺失时后台补拉 `/api/account` 再重判；④ 在线对局改成「服务端权威」，本地不再擅自把 `gameOver` 置真（避免本地三次重复判定误锁棋盘）；⑤ 新增「重新同步」按钮，重连对局流即整体重放局面 | `tests/SeekTest.java` 覆盖 `player`/`color`/非法值/null 四种解析路径；真机需实测 | ✅ 已修（0.7.0） |

---

## 自查发现的 Bug（2026-09-29 第二轮，交付 0.7.0 之前）

这一轮是用户反馈「要有排位和休闲、要看主页和排位分」时顺手复查出来的。

| # | 严重度 | 位置 | 问题 | 后果 | 修复 |
|---|---|---|---|---|---|
| 11 | 🔴 高 | `LichessApi.seek` | `time` 单位传错（秒当分钟） | 见 U2 | 见 U2 |
| 12 | 🔴 高 | `GamesActivity.challengeAi` | 读 `color` 而不是 `player` | 见 U3 | 见 U3 |
| 13 | 🟠 中 | `BoardGameActivity.updateStatus` | 在线对局也用**本地**的 `GameStatus.of` / 三次重复判定来置 `gameOver` | 本地判定一旦与服务端不一致，界面会直接锁死不让走子，而用户看不到任何原因。在线对局的权威状态在 `gameState.status` 里 | 新增 `serverAuthoritative()` 钩子，`GameActivity` 返回 `true`，跳过本地结束判定 |
| 14 | 🟠 中 | `GamesActivity.startSeek` / `onDestroy` | seek 流和事件流共用同一个 `cancelFlag` | 想「停止寻找」就会把事件流一起杀掉，之后收不到 `gameStart`/`challenge`；反过来页面销毁也停不干净 | 拆出独立的 `currentSeek` 标志；新增 `LichessApi.cancelSeek()`（`DELETE /api/board/seek`，立即生效，不用等空行心跳） |
| 15 | 🟡 低 | `GamesActivity` | 没有令牌时直接 `return`，之后在设置页填完令牌返回，页面仍是死的（事件流从没挂上） | 用户以为功能坏了 | 抽出 `startWorking()`，`onResume` 里发现「还没启动 + 现在有令牌」就补启动 |
| 16 | 🟡 低 | `GameActivity.onDestroy` | `cancelFlag` 是 `final`，`重新同步` 需要换一个标志 | 无法实现重连 | 改成 `volatile streamFlag`，每次 `startStream()` 新建一个，`streamLoop(flag)` 接收参数 |
| 17 | 🟡 低 | `LichessApi.explain` | HTTP 400 只输出 `HTTP 400 Bad Request` + 原文，没有解释 | 排查困难（这次的 400 就吃了这个亏） | 加 400/429 的友好说明，并把服务端原文带出来 |

---

## 自查发现的 Bug（2026-09-29 全量复查）

这一轮把全部源码逐文件过了一遍，查出 10 个问题。**其中 4 个是会直接毁掉功能体验的硬伤。**
全部在交付 0.6.0 之前修掉。

| # | 严重度 | 位置 | 问题 | 后果 | 修复 |
|---|---|---|---|---|---|
| 1 | 🔴 高 | `AiGameActivity.onBoardReady` | 棋盘翻转写反：`flipped = !aiIsWhite` | 玩家执白时自己的棋子在屏幕**上方**，看着像对手的棋；人会以为自己执黑 | 改成 `flipped = aiIsWhite`（人类永远在下方）。「换边重开」按钮里的同样错误一并修掉 |
| 2 | 🔴 高 | `GameActivity.applyFull` | **gameFull 的 JSON 里根本没有 `myColor` 字段** —— 颜色来自事件流的 `gameStart` 或 `/api/account/playing`。原代码在拿不到颜色时默认成 "white" | 从「进行中的对局」列表点进去、或挑战被接受后进入时，**约一半的对局棋盘方向是反的，而且根本走不了子**（`canMoveNow` 依赖 `isMyTurn`，而 `isMyTurn` 依赖 `myColor`） | 新增 `LichessGame.detectMyColor(username)`：拿 `white.id`/`black.id`/`white.name`/`black.name` 和本地保存的用户名比对。判断不出来时记一条带完整上下文的 WARN 日志 |
| 3 | 🔴 高 | `GameActivity.tickClocks` | **时钟重复扣减**：每 500ms 从 `shownWtime` 减去"距上次同步的毫秒数"，然后把结果**写回** `shownWtime`。下一次 tick 又在已经扣过的值上再扣一遍 | 时钟以**两倍速度**掉。5+0 的对局会显示成 2.5 分钟 | 拆成 `baseWtime/baseBtime`（服务端权威值，绝不改写）和 `dispWtime/dispBtime`（显示值）。每次 tick 都从 base 减，不写回 |
| 4 | 🟠 中 | `BoardView.drawCoordinates` | 左边行号的对比色算错：判断格子明暗时用了 `flipped ? 0 : 7`，应该是 `flipped ? 7 : 0` | 棋盘翻转时，左侧的 1-8 数字会变成"深底深字"，**完全看不见** | 改成用实际文件号 `flipped ? 7 : 0` 参与奇偶判断，并加注释说明 |
| 5 | 🟠 中 | `BoardGameActivity.setMovesFromUci` | 整体重放走子后没有清掉视图里的选中状态 | 对手走子后重放局面时，之前选中的格子可能已经没有棋子了，界面上会残留一个高亮的空格 | 重放后调 `boardView.clearSelection()` |
| 6 | 🟡 低 | `BoardView.onTouchEvent` | `if (!ownPiece) { ... if (ownPiece) select(sq); }` —— 死代码，逻辑绕 | 功能上碰巧是对的（fall through 到下面的分支），但结构混乱，改一行就容易出错 | 整个触摸处理重写成分支清晰的四个 case：再点同一个子 / 点可落子格 / 点自己的另一个子 / 点非法目标 |
| 7 | 🟡 低 | `Ai.quiesce` | 和 `negamax` 共用 `scoreBuf` 排序缓冲 | 按当前调用顺序是安全的（外层循环只读 `moves` 不读分数），但这是"靠顺序保证的正确性"，加一层递归就可能踩坑 | 给静态搜索单独一份 `qScoreBuf` |
| 8 | 🟡 低 | `PuzzleActivity` | 对手自动应招用 `postDelayed(350ms)`，回调里没检查 Activity 是否还活着 | 玩家在这 350ms 内退出谜题页，回调仍会对已销毁的 Activity 操作 | 回调开头加 `isFinishing()` 判断 |
| 9 | 🟡 低 | `BoardGameActivity.styleClock` | 在线对局每 500ms 重建一次 `GradientDrawable` | 每秒产生 2 个对象，在 832MHz ARM11 上是没必要的 GC 压力 | 缓存上一次的高亮状态，只有真的变化时才换背景 |
| 10 | 🟢 提示 | `Ai` | `moveBuf` 字段声明了但从未使用 | 白占 1KB 堆 | 删掉 |

### 这一轮的教训

1. **从协议里"推断"字段是危险的。** 第 2 条 bug 的根源是我想当然地以为 `gameFull` 里会有"我执哪一方"。
   实际上 Lichess 的 Board API 里，颜色只出现在**事件流的 gameStart** 和 `/api/account/playing` 里。
   我是回去翻 `openapi.yaml` 的 `GameFullEvent` schema 才确认的 —— **写协议相关代码前必须先核对 schema，不能凭印象。**
2. **"每 N 毫秒重算一次"的代码要特别小心"写回自己"。** 第 3 条的 `shownWtime = fw` 一眼看上去很自然，
   实际是典型的累加错误。权威值和显示值必须分开两个字段。
3. **死代码是坏味道。** 第 6 条那个 `if (!ownPiece) { if (ownPiece) ... }` 功能上碰巧对，
   但它说明当时写的时候思路是乱的。看到就重写，别留着。

---

## 处理纪律（Agent 自用）

1. 收到反馈先**复述我理解的问题与推测原因**，再改代码。
2. 一次只改一个 bug（除非用户一次报了多个）。
3. 修复后必须说明：改了什么、怎么在手机上验证。
4. 无法在电脑上复现的问题（TLS、内存、锁屏、Wi-Fi 切换），**必须靠真机日志确认**，不假设「应该好了」。
5. **改了协议相关的代码，必须回去核对 `toolchain/downloads/lichess-openapi.yaml` 里的 schema。**
