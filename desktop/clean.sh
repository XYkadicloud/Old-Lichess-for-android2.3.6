#!/usr/bin/env bash
# =============================================================================
# 回收 desktop/build/ 里没用的东西。
#
# 现在构建脚本已经把中间产物收敛到**一个** build/classes/ 目录
# （javac 原地覆盖、从不删除、依赖流式合并不落盘），所以平时根本不需要清理。
# 这个脚本主要是用来收拾历史遗留：
#   build/work-*/     旧版构建脚本留下的时间戳目录
#   build/deps/       旧版解压 spongycastle 留下的目录（1460 个文件！）
#   build/deps-stage/ 没有 python 时的解压兜底目录
#
# ⚠️ 为什么用 cmd 的 rmdir 而不是 rm -rf
# ---------------------------------------------------------------------------
# 本项目的开发盘是 **exFAT**（307GB 卷，簇 128KB），且是 USB 2.0 机械盘。
# 用 bash 的 `rm` 删 4000 个文件要起 4000 次进程，实测要十几分钟；
# `cmd /c rmdir /s /q` 是**一次系统调用**，快得多。
# 而且 exFAT 上每个小文件占一整个 128KB 簇，删掉这些目录能直接回收几百 MB。
#
# 用法：
#   bash desktop/clean.sh          清理历史遗留（保留 build/classes）
#   bash desktop/clean.sh --all    连 build/classes 一起清（下次构建会全量重编）
#   bash desktop/clean.sh --dry    只看会删什么，不动手
# =============================================================================
set -uo pipefail

# ⚠️ 变量名不能叫 _here：build.config.sh 内部也用这个名字，会把我们覆盖掉。
DESKTOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DESKTOP_DIR/../scripts/build.config.sh"

BUILD_ROOT="$DESKTOP_DIR/build"

MODE="legacy"
for arg in "$@"; do
    case "$arg" in
        --all) MODE="all" ;;
        --dry) MODE="dry" ;;
        *) echo "未知参数: $arg" >&2; exit 2 ;;
    esac
done

if [ ! -d "$BUILD_ROOT" ]; then
    echo "没有 build 目录，无需清理"
    exit 0
fi

# ---- 找出清理目标
TARGETS=()
while IFS= read -r d; do
    [ -n "$d" ] && TARGETS+=("$d")
done < <(find "$BUILD_ROOT" -maxdepth 1 -type d -name 'work-*' | sort)
for extra in deps deps-stage; do
    [ -d "$BUILD_ROOT/$extra" ] && TARGETS+=("$BUILD_ROOT/$extra")
done
if [ "$MODE" = "all" ] && [ -d "$BUILD_ROOT/classes" ]; then
    TARGETS+=("$BUILD_ROOT/classes")
fi

if [ "${#TARGETS[@]}" -eq 0 ]; then
    echo "没有需要清理的目录"
    echo "（当前构建脚本把中间产物收敛在 build/classes/ 里，原地覆盖，不会堆积）"
    du -sh "$BUILD_ROOT" 2>/dev/null | sed 's/^/  当前占用: /'
    exit 0
fi

# ---- 统计
TOTAL_FILES=0
for t in "${TARGETS[@]}"; do
    n=$(find "$t" -type f 2>/dev/null | wc -l | tr -d ' ')
    TOTAL_FILES=$((TOTAL_FILES + n))
done
SIZE=$(du -sh "${TARGETS[@]}" 2>/dev/null | tail -1 | cut -f1)

echo "将删除 ${#TARGETS[@]} 个目录 / $TOTAL_FILES 个文件 / 占用 $SIZE："
for t in "${TARGETS[@]}"; do
    n=$(find "$t" -type f 2>/dev/null | wc -l | tr -d ' ')
    echo "  $n 个文件  $t"
done

if [ "$MODE" = "dry" ]; then
    echo "（--dry，未实际删除）"
    exit 0
fi

if [ "$TOTAL_FILES" -eq 0 ]; then
    echo "没有文件需要删除"
    exit 0
fi

echo ""
echo "开始删除（exFAT + USB2.0 上可能要几分钟，请耐心等）…"
START=$(date +%s)

for t in "${TARGETS[@]}"; do
    # 转成 Windows 路径给 cmd 用。
    #
    # ⚠️ 参数必须写成 //s //q 而不是 /s /q：
    #    Git Bash 会把单个字母的 /x 参数当成盘符路径转换（/q -> Q:\），
    #    cmd 就会报 'Parameter format not correct - "Q:"'。
    #    写成 //x 让 MSYS 先转成 /x，cmd 才认。
    WIN_PATH=$(cygpath -w "$t" 2>/dev/null || echo "$t")
    echo "  删除 $t"
    cmd //c rmdir //s //q "$WIN_PATH" 2>/dev/null || true
done

ELAPSED=$(( $(date +%s) - START ))
echo ""
echo "清理完成，耗时 ${ELAPSED}s"

REMAIN=$(find "$BUILD_ROOT" -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')
echo "剩余 $REMAIN 个子目录："
find "$BUILD_ROOT" -maxdepth 1 -type d 2>/dev/null | sort | while IFS= read -r d; do
    echo "  $d"
done
du -sh "$BUILD_ROOT" 2>/dev/null | sed 's/^/  当前占用: /'
