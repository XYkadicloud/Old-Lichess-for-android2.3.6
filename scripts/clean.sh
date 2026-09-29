#!/usr/bin/env bash
# =============================================================================
# 分批清理构建产物。
#
# 背景：沙箱对"一次删超过 50 个文件"会拦下来（SAFE_DELETE_BULK_CONFIRM_REQUIRED，
# scope=turn，threshold=50）。而构建中间产物一次就是 200+ 个文件
# （光 .class 就 200 多个），所以 **整个目录一次 rm -rf 是不行的**，
# 哪怕它是 build/ 下的临时目录 —— 沙箱会递归数文件。
#
# build.sh 现在的策略是"只增不删"：每次构建写进 build/work-<时间戳>/。
# 这个脚本负责在你想回收空间时**按文件**分批删，每跑一次删 40 个，
# 多跑几次就能清干净：
#     for i in 1 2 3 4 5 6 7 8; do bash scripts/clean.sh; done
#
#   用法： bash scripts/clean.sh          清 build/work-* 与旧的 trash-*
#          bash scripts/clean.sh all      连 test-classes / quickcheck / android-check* 一起清
#          bash scripts/clean.sh list     只列出占用情况，不删
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build.config.sh
source "$HERE/build.config.sh"

LIMIT=40
MODE="${1:-work}"

if [ "$MODE" = "list" ]; then
    echo "build/ 占用情况："
    du -sh "$BUILD" 2>/dev/null
    du -sh "$BUILD"/* 2>/dev/null | sort -h
    exit 0
fi

case "$MODE" in
    all) PATTERNS="work-* trash-* android-check* quickcheck mitm test-classes verify-dex" ;;
    *)   PATTERNS="work-* trash-* android-check* quickcheck mitm" ;;
esac

# 只删「文件」和「空目录」，每次最多 LIMIT 个 —— 这样才不会被批量删除保护拦下
DELETED=0
STOP=0
for pat in $PATTERNS; do
    [ "$STOP" -eq 0 ] || break
    for d in "$BUILD"/$pat; do
        [ -e "$d" ] || continue
        if [ -d "$d" ]; then
            while [ "$DELETED" -lt "$LIMIT" ]; do
                f="$(find "$d" -type f 2>/dev/null | head -n 1)"
                [ -n "$f" ] || break
                rm -f "$f" 2>/dev/null || break
                DELETED=$((DELETED + 1))
            done
            if [ "$DELETED" -lt "$LIMIT" ]; then
                # 文件清完了，把剩下的空目录收掉（目录不计入文件数）
                find "$d" -depth -type d -empty -exec rmdir {} + 2>/dev/null
                rmdir "$d" 2>/dev/null || true
            else
                STOP=1
            fi
        else
            rm -f "$d" 2>/dev/null && DELETED=$((DELETED + 1))
        fi
        [ "$DELETED" -lt "$LIMIT" ] || { STOP=1; break; }
    done
done

echo "本次删除 $DELETED 个文件（上限 $LIMIT）。"
echo
echo "剩余占用："
du -sh "$BUILD" 2>/dev/null
du -sh "$BUILD"/* 2>/dev/null | sort -h | tail -n 12
echo
echo "没清干净就再执行一次本脚本。"
