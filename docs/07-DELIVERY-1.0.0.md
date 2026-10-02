# 07-DELIVERY-1.0.0 — 1.0 交付说明

交付日期：2026-10-01
构建机：机器 B（HP 15-fd0xxx / Core 5 120U / 16GB，档位 `high`）

---

## 1. 产物

| 项目 | 值 |
|---|---|
| APK | `dist/LichessOld-1.0.apk` |
| versionCode | `10000` |
| versionName | `1.0` |
| 体积 | 1,290,781 字节（1.23 MB） |
| SHA-256 | `3518ef7d0fa206d59ef1c5bc2d94dc133a24c22cc4516781f2974dbbc3543f19` |
| 签名 | 与 0.8.0 同一把 key（`8720d04f…101e2`），**可直接覆盖安装，不用卸载** |
| 静态校验 | **15/15 通过**（方法引用 17,057 / 上限 65,536；无 `.so`；单 dex） |

自动测试：**113 项全部通过，0 失败**（JsonTest / ChessTest / AiTest / SeekTest / SmokeTest）。

---

## 2. 本次做了什么

### 2.1 修掉两个已经"死掉"的接口（这是最要紧的一条）

排查新功能时实测发现，项目里原本封装的两个棋谱导出端点**都已经失效了**：

| 方法 | 原来打的端点 | 实测结果 |
|---|---|---|
| `LichessApi.gamesPgn()` | `GET /api/games/user/{name}` | **404** |
| `LichessApi.gamePgn(id)` | `GET /game/export/{id}.pgn` | **404** |

这两个方法一直**没有界面在调用**，所以问题从没暴露出来 —— 桌面 README 里还写着
"`gamesPgn()` 已经写好了，只是还没接界面"，实际上就算接上界面也是拿不到数据的。

改法：

- 新增 `LichessApi.lastGamePgn(name)`，改用依然有效的
  `GET /api/user/{name}/current-game`（免登录，返回标准 PGN）
- `gamesPgn()` 保留旧签名做兼容，内部转发到新方法
- `gamePgn(id)` 不再假装能做到 —— 按 ID 精确取棋谱的
  `/api/games/user/{ids}` 同样已失效，所以直接返回空串并写明原因

### 2.2 新增功能一：锦标赛

用户点名要的。lichess 的锦标赛就是 Arena（固定时长、随时加入退出、赢 2 分）。

| 页面 | 数据源 | 免登录 |
|---|---|---|
| `TournamentsActivity` | `GET /api/tournament` | ✅ |
| `TournamentActivity` | `GET /api/tournament/{id}` | ✅ |

- 列表按**正在进行 / 即将开始 / 刚结束**分三段，每条显示赛制（如 `1+0`）、人数、排位还是休闲
- 点进去看赛制详情 + **排行榜**（带翻页，每页 15 行）+ **焦点对局**
- 焦点对局可以直接点进观战页

**为什么不做"报名参赛"**：`POST /api/tournament/{id}/join` 需要 OAuth 的
`tournament:write` 权限，本项目用的是手填个人令牌，拿不到那个 scope。
所以 1.0 的定位是**看比赛**，不是参赛。这一点在代码注释里也写清楚了。

### 2.3 新增功能二：最近战绩

`ActivityActivity`，数据源 `GET /api/user/{name}/activity`（免登录）。

把接口按天分桶的数据压成一张紧凑表：每天一行，显示 `13胜 43负 3和`
加各分项等级分涨跌（`Blitz +50`）以及当天锦标赛最佳名次。

### 2.4 新增功能三：棋谱查看与导出

`PgnActivity`，数据源就是 2.1 修好的 `current-game`。

- 显示对局摘要（双方、赛制、开局名、结果、步数）
- 显示棋谱正文（截前 40 行，小屏幕上可控）
- **「存到存储卡」**：把完整 PGN 写到 `LichessOld/games/`，用户能直接用读卡器拷出来

顺带给 `log/Storage.java` 加了通用的 `writeText()`：
优先外置存储、不可用退回内部目录、文件名做安全化（防路径穿越）、失败返回 `null` 不抛异常。

### 2.5 顺手修的

- **观战页支持指定对局**：从锦标赛焦点对局点进 `TvActivity` 时传 `EXTRA_GAME_ID`，
  原来的焦点流里出现别的对局不会再把棋盘抢走（不传 extra 时仍是原来的行为）
- **修复 `test.sh` 在受限环境跑不起来**：原来第 49 行 `rm -rf "$OUT"` 会撞上
  批量删除保护，导致整个脚本在第 3 步就死掉。改成带时间戳的输出目录（只增不删），
  并建立 `latest` 软链接方便找最近一次结果
- **`test.sh` 里的 JVM 参数不再硬编码**：原来写死了"4GB 小内存机"的那套参数，
  违反了机器分档原则。改成引用 `machine-profile.sh` 产出的 `$JVM_DESKTOP`，
  两台电脑各自跑各自的档位

---

## 3. 测试

### 3.1 自动测试（113 项全过）

```
bash scripts/test.sh
```

桌面 JVM 上跑**和手机完全相同的纯 Java 代码**，其中 SmokeTest 会真实连上 lichess.org。

新增的 4 条（G7~G10），都是先手工真连确认过结构才写进测试的：

| 用例 | 验证内容 | 实测输出 |
|---|---|---|
| G7 | `/api/tournament` 列表 | 进行中=14 即将开始=103 已结束=20 |
| G8 | `/api/tournament/{id}` 详情+排行榜 | 首位=yonatankhut(1851) |
| G9 | `/api/user/{u}/activity` 战绩 | 7 天，合计 885 局 |
| G10 | `/api/user/{u}/current-game` 棋谱 | 标签齐全，走了 85 步 |

**一条被否掉的测试（记录一下，免得下次又走一遍）**

本来想加一条"旧棋谱端点已死"的回归测试。写完发现它**不可靠**：反复探活会撞上
lichess 的速率限制（HTTP 429），甚至偶尔拿到 `200 + HTML 错误页`，于是同一条
测试时过时不过，变成噪音。已删掉，端点状态改为记在代码注释里。

### 3.2 真机清单（请用户在 GT-S5360 上过一遍）

新增的部分重点看：

1. **锦标赛** → 列表能出三段；点进去有排行榜；翻页按钮正常；焦点对局能点进观战
2. **最近战绩** → 显示近 7 天胜负；等级分涨跌有正负号；锦标赛名次能显示
3. **棋谱** → 能显示摘要和正文；「存到存储卡」后到 `LichessOld/games/` 确认文件存在
4. **观战**（从锦标赛进）→ 只有指定的那一局，不会跳走
5. 老功能回归：在线对局 / 谜题 / 离线 AI / 双人 / 设置 / 诊断 / 日志

已知的服务端行为（不是 bug）：

- 正在下的棋，`current-game` 会**延迟 3 步**才吐出来（防作弊）
- 对方最近没下棋时，`current-game` 返回 404，界面上显示成"没有棋谱"

---

## 4. 已知限制

| 限制 | 原因 | 影响 |
|---|---|---|
| 不能报名锦标赛 | 需要 OAuth `tournament:write`，个人令牌拿不到 | 只能看，不能参赛 |
| 不能按对局 ID 精确取棋谱 | `/api/games/user/{ids}` 已失效 | 只能取"最近一局" |
| 战绩只有近 7 天 | 接口默认返回量 | 看不到更久以前的 |
| 排行榜一页 15 行 | 320×240 屏幕 | 需翻页 |
| 棋谱正文只显示前 40 行 | 屏幕太小 | 完整内容存到存储卡看 |

---

## 5. 改动文件清单

**新增**

```
app/src/org/lichessold/ui/TournamentsActivity.java
app/src/org/lichessold/ui/TournamentActivity.java
app/src/org/lichessold/ui/ActivityActivity.java
app/src/org/lichessold/ui/PgnActivity.java
docs/07-DELIVERY-1.0.0.md
```

**修改**

```
app/src/org/lichessold/net/LichessApi.java     修死端点 + 新增 4 个方法
app/src/org/lichessold/log/Storage.java        新增 writeText() / safeName()
app/src/org/lichessold/ui/TvActivity.java      支持 EXTRA_GAME_ID
app/src/org/lichessold/ui/MainActivity.java    新增 3 个入口
app/AndroidManifest.xml                        注册 4 个新 Activity
app/res/values/strings.xml                     4 个新标题
tests/SmokeTest.java                           G7~G10
scripts/test.sh                                只增不删 + 按机器档位取参数
```

**未改动**：`chess/` `json/` `util/` `store/` `platform/` `log/`（除 Storage）
—— 这四个包是"零 Android 依赖"的核心，本轮没碰，桌面测试才继续有效。
