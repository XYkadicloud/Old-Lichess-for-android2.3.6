#!/usr/bin/env bash
# =============================================================================
# 把最新的 APK 复制到手机 SD 卡。
#
# 手机会以「可移动磁盘」的形式出现在某个盘符上（以前是 D:，但**盘符会变**）。
# 这个脚本自动扫描所有盘符，找到带 LichessOld 文件夹的那个（App 运行时会建），
# 或者带 Android 目录的那个，然后把 APK 复制进去。
#
#   用法： bash scripts/copy_to_phone.sh           复制 dist 里最新的 APK
#          bash scripts/copy_to_phone.sh 0.7.0     复制指定版本
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build.config.sh
source "$HERE/build.config.sh"

WANT="${1:-}"

# ---- 1. 挑出要复制的 APK
if [ -n "$WANT" ]; then
    APK="$DIST/LichessOld-$WANT.apk"
else
    APK="$(ls -1t "$DIST"/LichessOld-*.apk 2>/dev/null | head -n 1)"
fi

if [ -z "${APK:-}" ] || [ ! -f "$APK" ]; then
    echo "找不到 APK。先跑： bash scripts/build.sh 0.7.0"
    exit 1
fi
echo "要复制的 APK： $APK"
echo "体积： $(stat -c%s "$APK") 字节"
echo

# ---- 2. 扫描盘符，找手机
# 判定依据（任一即可）：
#   a) 盘根有 LichessOld 文件夹（App 自己建的，最可靠）
#   b) 盘根有 Android 文件夹（SD 卡常见结构）
find_phone() {
    for d in c d e f g h i j k l m n o p q r s t u v w x y z; do
        [ -d "/$d" ] || continue
        [ "/$d" = "/c" ] && continue          # 跳过系统盘
        [ "$d" = "$ROOT_DRIVE" ] && continue  # 跳过项目盘
        if [ -d "/$d/LichessOld" ] || [ -d "/$d/Android" ]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

DRIVE="$(find_phone || true)"
if [ -z "$DRIVE" ]; then
    echo "!! 没找到手机。"
    echo
    echo "请确认："
    echo "  1) 数据线已经插上手机和电脑"
    echo "  2) 手机屏幕下拉通知栏 → 点「USB 已连接」→ 选「USB 存储」或「传输文件(MTP)」"
    echo "  3) 等电脑弹出「可移动磁盘」窗口后再跑一次本脚本"
    echo
    echo "当前能看到的盘符："
    for d in c d e f g h i j; do
        [ -d "/$d" ] && echo "  /$d"
    done
    exit 1
fi

echo "找到手机： /$DRIVE"

# ---- 3. 复制
DEST_DIR="/$DRIVE/lichess"
mkdir -p "$DEST_DIR" 2>/dev/null || true

NAME="$(basename "$APK")"
if cp -f "$APK" "$DEST_DIR/$NAME" 2>/dev/null; then
    echo "已复制到： $DEST_DIR/$NAME"
else
    echo "!! 复制失败，试试手动拖过去。目标目录：$DEST_DIR"
    exit 1
fi

# 顺便把旧的测试版留着不动，只提示一下
echo
echo "手机上的 APK 列表："
ls -1 "$DEST_DIR"/*.apk 2>/dev/null || true
echo
echo "接下来在手机上："
echo "  1) 打开「文件管理」→ 进入 sdcard → lichess 文件夹"
echo "  2) 点 $NAME 安装（要先允许「未知来源」）"
echo "  3) 装完打开 App，照 docs/07-DELIVERY-0.7.0.md 的清单测"
