# 08-MACHINE-PROFILES — 两台开发机（必读，尤其是 AI）

最后更新：2026-09-29

---

## 0. 一句话

**这个项目在两台配置差 4 倍的电脑上开发。JVM 内存参数必须跟着机器走，
参数不写在脚本里，由 `scripts/machine-profile.sh` 按物理内存自动判定。**

搞反了两头都出事：

| 情况 | 后果 |
|---|---|
| 低配机沿用了高配机的参数 | `d8` 直接崩：`Native memory allocation (malloc) failed to allocate ... for Chunk::new`（见 `docs/02-BUILD.md` 2.5 节） |
| 高配机沿用了低配机的参数 | 不会崩，但**白白慢好几倍** —— 低配参数为了活命关掉了 C2（`-XX:TieredStopAtLevel=1`），高配机根本不需要这个限制 |

---

## 1. 两台机器分别是什么

| | **机器 A：低配机** | **机器 B：本机（高性能机）** |
|---|---|---|
| 机型 | Intel N3060 平台（Atom 级） | HP Laptop 15-fd0xxx |
| CPU | Intel Celeron N3060，**2 核 2 线程**，Braswell/Airmont（family 6 model 76） | Intel Core 5 120U，**10 核 12 线程**（2P + 8E），基频 1.4GHz |
| 物理内存 | **4 GB**（实测 4,135,064 KB ≈ 3.94 GB） | **16 GB**（实测 16,409,276 KB ≈ 15.6 GB） |
| 可用内存 | 空闲常低于 400 MB，交换文件基本用尽 | 空闲常 3 GB 以上 |
| 系统 | Windows 10 64 位，Build 19041 | Windows 11 Home Single Language，Build 26200 |
| 档位判定 | `low` | `high` |

> 机器 A 的数据来源：项目根目录那份 `hs_err_pid4548.log`
> （`physical 4135064k(333748k free)`、`CPU: total 2 ... model 76`、`Windows 10 Build 19041`），
> 以及 `README.md` / `docs/02-BUILD.md` 里的记录。
>
> 机器 B 的数据来源：本机实测（`/proc/meminfo` + `nproc` + `Win32_Processor`）。

### 项目目录

项目放在一块**移动硬盘**上，在两台机器之间搬。所以：

- **盘符会变**。`E:` 曾经变成 `F:`（插上手机后 Windows 会重排盘符）。
  `scripts/build.config.sh` 从脚本自身位置反推项目根，**不写死盘符**。
- 机器 B 上当前路径是 `E:\android 2.3.6 lichess dev`。
- 换机器前，**把项目从盘上拔下来之前先跑完构建**，别让中间产物跨机器。

---

## 2. 自动识别（不用记，也不用判断）

```bash
bash scripts/machine-profile.sh          # 直接跑：打印完整报告
source scripts/build.config.sh           # 构建/测试脚本会自动 source，顺带打印一行档位
```

输出示例（机器 B）：

```
[machine] 档位=high  内存=16025MB  核数=12  高性能机：全速构建，C2 已启用
```

判定规则：

| 物理内存 | 档位 | 参数倾向 |
|---|---|---|
| ≥ 12 GB | `high` | 放开堆，**不限制 `TieredStopAtLevel`**（启用 C2，构建明显更快） |
| 6 ~ 12 GB | `mid` | 常规参数，留余量给浏览器 |
| < 6 GB | `low` | 小堆 + `-XX:TieredStopAtLevel=1`（只用 C1，禁用 C2） |

强制覆盖（一般不需要）：

```bash
export LICHESSOLD_PROFILE=low      # 或 mid / high
export LICHESSOLD_QUIET=1          # 不打印那一行档位提示
```

### 各档位的实际参数

| 用途 | low（机器 A） | high（机器 B） |
|---|---|---|
| `javac` / `d8`（构建） | `-Xms32m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1` | `-Xms256m -Xmx2g -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=256m` |
| `apksigner` | `-Xms16m -Xmx256m ... -XX:TieredStopAtLevel=1` | `-Xms64m -Xmx512m ...` |
| 桌面测试 JVM | `-Xms32m -Xmx512m ... -XX:TieredStopAtLevel=1` | `-Xms128m -Xmx2g ...` |

**参数只有一份来源**：`scripts/machine-profile.sh`。
`scripts/build.config.sh` 里的 `run_d8` / `run_dx` / `run_apksigner` / `run_java`
都只是引用 `$JVM_BUILD` / `$JVM_SIGN` / `$JVM_DESKTOP`，没有自己的硬编码值。

---

## 3. 给 AI 的硬性规则

> 这一节是写给接手这个项目的 AI 的。用户是分两台电脑开发的，所以：

1. **先判断机器，再决定参数。** 动手构建/测试之前先跑
   `bash scripts/machine-profile.sh`（或看 `build.config.sh` 输出里那一行 `[machine]`）。
2. **不要把机器 A 的内存参数带到机器 B。** 看到 `-Xmx320m`、`TieredStopAtLevel=1`
   这类值出现在**机器 B** 的命令行里，那是错的 —— 检查是不是绕过了
   `machine-profile.sh` 自己写了 `java -Xmx...`。
3. **也不要把机器 B 的参数带到机器 A。** 机器 A 上用 `-Xmx2g` 会让 d8 崩。
4. **不要把这些参数写进脚本、文档、代码注释里当"标准值"。** 它们属于机器，不属于项目。
   唯一的判定逻辑在 `machine-profile.sh`。
5. **遇到"本机内存只有 4GB"这类描述，先确认说的是哪台机器。**
   下面第 4 节列了哪些文档只适用于机器 A。
6. **换机器后第一次构建，先跑 `bash scripts/machine-profile.sh` 确认档位**，
   再跑 `bash scripts/build.sh <版本>`。

---

## 4. 哪些旧记录只适用于机器 A

`docs/` 里这些内容写于机器 A（4GB / N3060），**在机器 B 上不成立**，不要照搬：

| 位置 | 内容 | 在机器 B 上 |
|---|---|---|
| `README.md` 快速开始 | "⚠️ 本机只有 4GB 内存…不要改回 `-Xmx1g`" | 机器 B 是 16GB，`-Xmx2g` 才是正常值 |
| `docs/02-BUILD.md` §2.5 | 整节"内存约束" | 仅机器 A；机器 B 由 `machine-profile.sh` 自动给 `high` 档 |
| `docs/02-BUILD.md` §8 | "构建前先关掉浏览器" | 仅机器 A |
| `docs/03-PROGRESS.md` | "本机只有 4GB" | 指机器 A |
| `scripts/test.sh` 顶部注释 | "这台机器内存极小（4GB）" | 指机器 A |

**仍然适用于两台机器的**（与机器配置无关）：

- `minSdkVersion=10` / `targetSdkVersion=10`，`-bootclasspath` 指向真 API 10 的 `android.jar`
- Java 语言级别 1.7，不用 AndroidX / AppCompat / Material / Fragment
- 不引入任何 `.so`（ARMv6 只能用 `armeabi`，所以走纯 Java）
- 构建临时文件不落 C 盘
- 沙箱里"删除"会被批量删除保护拦下 → 构建流程是**只增不删**，
  每次写进 `build/work-<时间戳>/`；回收空间用 `bash scripts/clean.sh` 分批删

---

## 5. 快速自检

```bash
bash scripts/machine-profile.sh    # 看档位对不对
```

如果输出的档位和实际内存不符，八成是 `/proc/meminfo` 读不到（非 Git Bash 环境），
这时用 `LICHESSOLD_PROFILE` 手工指定。
