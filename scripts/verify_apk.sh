#!/usr/bin/env bash
# =============================================================================
# APK 静态校验。构建完之后跑一遍，能在装机之前抓出大部分低级错误。
#
#   用法： bash scripts/verify_apk.sh [APK路径]
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build.config.sh
source "$HERE/build.config.sh"

APK="${1:-}"
if [ -z "$APK" ]; then
    APK="$(ls -t "$DIST"/*.apk 2>/dev/null | head -1)"
fi
if [ ! -f "$APK" ]; then
    echo "找不到 APK: $APK"
    exit 2
fi

PASS=0
FAIL=0

ok()   { PASS=$((PASS+1)); echo "[PASS] $1"; [ -n "${2:-}" ] && echo "       $2"; }
bad()  { FAIL=$((FAIL+1)); echo "[FAIL] $1"; [ -n "${2:-}" ] && echo "       $2"; }
check(){ if [ "$2" = "1" ]; then ok "$1" "${3:-}"; else bad "$1" "${3:-}"; fi; }

echo "校验: $APK"
echo "体积: $(stat -c%s "$APK") 字节"
echo

# ---------------------------------------------------------------- 1. badging
BADGING="$("$BT/aapt.exe" dump badging "$APK" 2>/dev/null)"

check "1. minSdkVersion = 10" \
    "$(echo "$BADGING" | grep -c "sdkVersion:'10'")" \
    "$(echo "$BADGING" | grep -E "sdkVersion|targetSdkVersion" | tr '\n' ' ')"

check "2. targetSdkVersion = 10" \
    "$(echo "$BADGING" | grep -c "targetSdkVersion:'10'")" ""

check "3. 包名 = org.lichessold" \
    "$(echo "$BADGING" | grep -c "package: name='org.lichessold'")" \
    "$(echo "$BADGING" | grep '^package:' | head -1)"

check "4. 有启动 Activity" \
    "$(echo "$BADGING" | grep -c 'launchable-activity')" \
    "$(echo "$BADGING" | grep 'launchable-activity' | head -1)"

check "5. 有应用图标" \
    "$(echo "$BADGING" | grep -c "application-icon")" \
    "$(echo "$BADGING" | grep 'application-icon' | head -1)"

# ---------------------------------------------------------------- 2. 无 native
SO_COUNT="$(unzip -l "$APK" | grep -c '\.so$' || true)"
check "6. 不含 native 库（ARMv6 可直接跑）" \
    "$([ "$SO_COUNT" -eq 0 ] && echo 1 || echo 0)" \
    "找到 $SO_COUNT 个 .so"

# ---------------------------------------------------------------- 3. assets
check "7. 内置根证书已打包" \
    "$(unzip -l "$APK" | grep -c 'assets/cacerts.pem')" \
    "$(unzip -l "$APK" | grep 'cacerts.pem' | head -1)"

# ---------------------------------------------------------------- 4. dex
DEX_TMP="$BUILD/verify-dex"
rm -rf "$DEX_TMP"; mkdir -p "$DEX_TMP"
unzip -o -q "$APK" 'classes*.dex' -d "$DEX_TMP" 2>/dev/null

DEX_COUNT="$(ls "$DEX_TMP"/classes*.dex 2>/dev/null | wc -l)"
check "8. 只有 1 个 dex（API 10 不支持 multidex）" \
    "$([ "$DEX_COUNT" -eq 1 ] && echo 1 || echo 0)" "找到 $DEX_COUNT 个 dex"

DEX="$DEX_TMP/classes.dex"
if [ -f "$DEX" ]; then
    DEXVER="$("$BT/dexdump.exe" -f "$DEX" 2>/dev/null | grep -oE "DEX version '[0-9]+'" | head -1)"
    check "9. dex 版本兼容 API 10" \
        "$(echo "$DEXVER" | grep -c "'035'")" "$DEXVER"

    METHODS="$("$BT/dexdump.exe" -f "$DEX" 2>/dev/null | grep -oE 'method_ids_size *: [0-9]+' | grep -oE '[0-9]+' | head -1)"
    if [ -n "$METHODS" ] && [ "$METHODS" -lt 65536 ]; then
        ok "10. 方法引用数未超限" "method_ids_size = $METHODS（上限 65536）"
    else
        bad "10. 方法引用数未超限" "method_ids_size = ${METHODS:-未知}"
    fi

    # 清单里的每个 Activity 都必须真的在 dex 里。
    # 从 APK 内的二进制清单里取（不是从源文件取），这样才能验证到真正打包进去的内容。
    MISSING=0
    MISSING_LIST=""
    ACTIVITIES="$("$BT/aapt.exe" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null \
        | grep -E 'A: android:name' \
        | grep -oE '"\.?[A-Za-z0-9_.]*ui\.[A-Za-z0-9_]+"' \
        | tr -d '"' | sort -u)"
    ACT_COUNT=0
    for a in $ACTIVITIES; do
        case "$a" in
            .*) FULL="org.lichessold$a" ;;
            *)  FULL="$a" ;;
        esac
        ACT_COUNT=$((ACT_COUNT+1))
        SLASH="$(echo "$FULL" | tr '.' '/')"
        if ! "$BT/dexdump.exe" "$DEX" 2>/dev/null | grep -q "L$SLASH;"; then
            MISSING=$((MISSING+1))
            MISSING_LIST="$MISSING_LIST $FULL"
        fi
    done
    check "11. 清单里所有 Activity 都在 dex 里" \
        "$([ "$MISSING" -eq 0 ] && [ "$ACT_COUNT" -ge 9 ] && echo 1 || echo 0)" \
        "$([ "$MISSING" -eq 0 ] && echo "共 $ACT_COUNT 个，全部存在" || echo "缺失:$MISSING_LIST")"

    # 关键类必须在
    MISSING_CORE=""
    for c in org/lichessold/App org/lichessold/util/Log org/lichessold/net/TlsConnection \
             org/lichessold/net/CertVerifier org/lichessold/net/Http org/lichessold/net/LichessApi \
             org/lichessold/chess/Board org/lichessold/chess/MoveGen org/lichessold/chess/Ai \
             org/lichessold/chess/San org/lichessold/chess/Pgn org/lichessold/json/Json \
             org/lichessold/ui/BoardView org/lichessold/ui/Pieces; do
        if ! "$BT/dexdump.exe" "$DEX" 2>/dev/null | grep -q "L$c;"; then
            MISSING_CORE="$MISSING_CORE $c"
        fi
    done
    check "12. 核心类都在 dex 里" \
        "$([ -z "$MISSING_CORE" ] && echo 1 || echo 0)" \
        "$([ -z "$MISSING_CORE" ] && echo "14 个关键类全部存在" || echo "缺失:$MISSING_CORE")"
fi

# ---------------------------------------------------------------- 5. 签名
if run_apksigner verify "$APK" >/dev/null 2>&1; then
    ok "13. 签名校验通过"
else
    bad "13. 签名校验通过" "apksigner verify 失败"
fi

if run_apksigner verify --print-certs "$APK" 2>/dev/null | grep -q "CN=LichessOld"; then
    ok "14. 签名者正确（与 keystore 一致，可覆盖安装）"
else
    bad "14. 签名者正确" "签名者不是 CN=LichessOld"
fi

# ---------------------------------------------------------------- 6. 体积
SIZE="$(stat -c%s "$APK")"
if [ "$SIZE" -lt 5242880 ]; then
    ok "15. 体积 < 5MB" "$(awk "BEGIN{printf \"%.2f MB\", $SIZE/1048576}")"
else
    bad "15. 体积 < 5MB" "$(awk "BEGIN{printf \"%.2f MB\", $SIZE/1048576}")"
fi

rm -rf "$DEX_TMP"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
[ "$FAIL" -eq 0 ] || exit 1
