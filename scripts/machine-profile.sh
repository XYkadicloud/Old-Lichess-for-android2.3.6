#!/usr/bin/env bash
# =============================================================================
# 开发机档位识别 —— 决定用哪套 JVM 内存参数。
#
# 为什么必须自动
# --------------
# 这个项目在两台配置差 4 倍的电脑上开发过。JVM 参数必须跟着机器走，搞反了都出事：
#   低配机沿用高配机参数 → d8 直接崩：
#       Native memory allocation (malloc) failed to allocate ... for Chunk::new
#   高配机沿用低配机参数 → 白白慢好几倍（还压着 C2 不让用，构建时间翻倍）
# 所以这里**自动识别**，不靠人记、也不靠读文档。
#
# 用法
# ----
#   被 build.config.sh 自动 source（默认，静默输出一行档位信息）
#   bash scripts/machine-profile.sh        直接跑，打印完整报告
#   export LICHESSOLD_PROFILE=low|mid|high 强制指定档位
#   export LICHESSOLD_QUIET=1              不打印那行提示
# =============================================================================

# 物理内存。Git Bash 的 /proc/meminfo 报的是**真实的 Windows 物理内存**，
# 比 wmic（Win11 已移除）可靠，也不用起 PowerShell（很慢）。
_mp_mem_kb="$(awk '/^MemTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null)"
case "$_mp_mem_kb" in
    ''|*[!0-9]*) _mp_mem_kb=0 ;;
esac

_mp_cpus="$(nproc 2>/dev/null)"
case "$_mp_cpus" in
    ''|*[!0-9]*) _mp_cpus=1 ;;
esac

MACHINE_RAM_MB=$(( _mp_mem_kb / 1024 ))
MACHINE_CPUS="$_mp_cpus"
MACHINE_OS="$(uname -s 2>/dev/null) $(uname -r 2>/dev/null)"

if [ -n "${LICHESSOLD_PROFILE:-}" ]; then
    MACHINE_PROFILE="$LICHESSOLD_PROFILE"
    MACHINE_PROFILE_FORCED=1
elif [ "$MACHINE_RAM_MB" -ge 12288 ]; then
    MACHINE_PROFILE="high"
elif [ "$MACHINE_RAM_MB" -ge 6144 ]; then
    MACHINE_PROFILE="mid"
else
    MACHINE_PROFILE="low"
fi
MACHINE_PROFILE_FORCED="${MACHINE_PROFILE_FORCED:-0}"

case "$MACHINE_PROFILE" in
    high)
        # ≥12GB：放开跑。**不限制 TieredStopAtLevel**，让 C2 参与，
        # javac / d8 都明显更快。原生内存有的是，不会再撞 Chunk::new。
        JVM_BUILD="-Xms256m -Xmx2g -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=256m"
        JVM_SIGN="-Xms64m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m"
        JVM_DESKTOP="-Xms128m -Xmx2g -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m"
        MACHINE_NOTE="高性能机：全速构建，C2 已启用"
        ;;
    mid)
        # 6~12GB：中庸，留够余量给浏览器。
        JVM_BUILD="-Xms64m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m"
        JVM_SIGN="-Xms32m -Xmx384m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m"
        JVM_DESKTOP="-Xms64m -Xmx1g -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m"
        MACHINE_NOTE="中配机：常规参数"
        ;;
    *)
        # 低配机（Intel N3060 / 4GB）：空闲常低于 400MB，交换文件基本用尽。
        #   -Xmx320m                 堆必须小。**不要改成 -Xmx1g**，会把原生内存挤爆
        #   -XX:TieredStopAtLevel=1  只用 C1、彻底不启用 C2。C2 的 Chunk::new
        #                            就是之前崩在 "Native memory allocation failed" 的地方
        MACHINE_PROFILE="low"
        JVM_BUILD="-Xms32m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1"
        JVM_SIGN="-Xms16m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1"
        JVM_DESKTOP="-Xms32m -Xmx512m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1"
        MACHINE_NOTE="低配机：紧缩内存 + 禁用 C2，构建前请关掉浏览器"
        ;;
esac

export MACHINE_PROFILE MACHINE_PROFILE_FORCED MACHINE_RAM_MB MACHINE_CPUS MACHINE_OS MACHINE_NOTE
export JVM_BUILD JVM_SIGN JVM_DESKTOP

# 兼容旧变量名（早期脚本里叫 JVM_LOWMEM）
export JVM_LOWMEM="$JVM_BUILD"

if [ -z "${LICHESSOLD_QUIET:-}" ]; then
    echo "[machine] 档位=$MACHINE_PROFILE  内存=${MACHINE_RAM_MB}MB  核数=$MACHINE_CPUS  $MACHINE_NOTE" >&2
    if [ "$MACHINE_PROFILE_FORCED" = "1" ]; then
        echo "[machine] （档位由 LICHESSOLD_PROFILE 强制指定，未按内存自动判定）" >&2
    fi
fi

# 直接执行时打印完整报告
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    echo "=============== 开发机档位报告 ==============="
    echo "系统        : $MACHINE_OS"
    echo "物理内存    : ${MACHINE_RAM_MB} MB"
    echo "CPU 核数    : $MACHINE_CPUS"
    echo "判定档位    : $MACHINE_PROFILE$([ "$MACHINE_PROFILE_FORCED" = "1" ] && echo "（强制指定）")"
    echo "说明        : $MACHINE_NOTE"
    echo
    echo "构建（javac / d8）: $JVM_BUILD"
    echo "签名（apksigner）  : $JVM_SIGN"
    echo "桌面测试           : $JVM_DESKTOP"
    echo
    echo "判定规则：≥12GB → high，6~12GB → mid，<6GB → low"
    echo "覆盖方式：export LICHESSOLD_PROFILE=low|mid|high"
    echo "=============================================="
fi
