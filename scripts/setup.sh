#!/usr/bin/env bash
# =============================================================================
# 一次性安装构建工具链（幂等，可重复执行）。
#   用法：bash scripts/setup.sh
# 全部落在 E 盘。C 盘不写任何文件。
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build.config.sh
source "$HERE/build.config.sh"

step() { echo; echo "======== $* ========"; }

mkdir -p "$DOWNLOADS" "$TOOLCHAIN/libs" "$APP/libs" "$APP/assets"

# ---------------------------------------------------------------- 1. JDK 8
step "1/5 JDK 8"
if [ -x "$JAVA_HOME/bin/javac.exe" ]; then
    echo "已安装，跳过"
else
    JDK_ZIP="$DOWNLOADS/$(basename "$URL_JDK")"
    if [ ! -s "$JDK_ZIP" ]; then
        echo "下载 $URL_JDK"
        curl -L --retry 5 --retry-delay 3 -o "$JDK_ZIP" "$URL_JDK" || {
            echo "主源失败，改用备用源 $URL_JDK_ALT"
            JDK_ZIP="$DOWNLOADS/$(basename "$URL_JDK_ALT")"
            curl -L --retry 5 --retry-delay 3 -o "$JDK_ZIP" "$URL_JDK_ALT"
        }
    fi
    echo "校验压缩包..."
    unzip -t "$JDK_ZIP" >/dev/null || { echo "压缩包损坏，请删除后重试: $JDK_ZIP"; exit 1; }
    echo "解包到 $JAVA_HOME"
    rm -rf "$TOOLCHAIN/_jdk_tmp"
    mkdir -p "$TOOLCHAIN/_jdk_tmp"
    unzip -q "$JDK_ZIP" -d "$TOOLCHAIN/_jdk_tmp"
    INNER="$(ls -d "$TOOLCHAIN"/_jdk_tmp/*/ | head -1)"
    rm -rf "$JAVA_HOME"
    mv "$INNER" "$JAVA_HOME"
    rmdir "$TOOLCHAIN/_jdk_tmp" 2>/dev/null || true
fi

echo "javac: $("$JAVA_HOME/bin/javac.exe" -version 2>&1)"
echo "java : $("$JAVA_HOME/bin/java.exe" -version 2>&1 | head -1)"

# ---------------------------------------------------- 2. Android build-tools
step "2/5 Android build-tools 28.0.3"
if [ -x "$BT/aapt.exe" ]; then
    echo "已安装，跳过"
else
    BT_ZIP="$DOWNLOADS/$(basename "$URL_BT")"
    if [ ! -s "$BT_ZIP" ]; then
        echo "下载 $URL_BT"
        curl -L --retry 5 --retry-delay 3 -o "$BT_ZIP" "$URL_BT"
    fi
    unzip -t "$BT_ZIP" >/dev/null || { echo "压缩包损坏: $BT_ZIP"; exit 1; }
    rm -rf "$TOOLCHAIN/android-build-tools/_tmp"
    mkdir -p "$TOOLCHAIN/android-build-tools/_tmp"
    unzip -q "$BT_ZIP" -d "$TOOLCHAIN/android-build-tools/_tmp"
    # build-tools 的压缩包内层目录名是平台代号（28.0.3 是 android-9），要重命名
    INNER="$(ls -d "$TOOLCHAIN"/android-build-tools/_tmp/*/ | head -1)"
    rm -rf "$BT"
    mv "$INNER" "$BT"
    rmdir "$TOOLCHAIN/android-build-tools/_tmp" 2>/dev/null || true
fi

# ----------------------------------------------- 3. API 10 平台包 + TLS 库
step "3/5 API 10 平台包与 Spongy Castle"
if [ ! -s "$AJAR" ]; then
    echo "下载 API 10 android.jar（含 java.* 存根）"
    curl -L --retry 5 --retry-delay 3 -o "$AJAR" "$URL_AJAR"
fi
if [ ! -s "$SCJAR" ]; then
    echo "下载 spongycastle core 1.58.0.0"
    curl -L --retry 5 --retry-delay 3 -o "$SCJAR" "$URL_SC"
fi
cp -f "$SCJAR" "$APP/libs/sc-core-1.58.0.0.jar"

# 关键自检：没有 java/lang/Object.class 就说明这个 jar 不能当 bootclasspath，
# 编译会在 "Unable to find package java.lang" 处失败。早失败比晚失败好。
if ! unzip -l "$AJAR" | grep -q 'java/lang/Object.class'; then
    echo "!! $AJAR 缺少 java.lang 存根，无法用作 -bootclasspath。"
    echo "!! 请删除该文件后重新运行 setup.sh"
    exit 1
fi
echo "android.jar 类数  : $(unzip -l "$AJAR" | grep -c '\.class')"
echo "android.jar java.*: $(unzip -l "$AJAR" | awk '{print $4}' | grep -cE '^java/')"
echo "sc-core jar       : $(stat -c%s "$SCJAR") bytes"

# ------------------------------------------------------------- 4. 根证书
step "4/5 Mozilla CA bundle"
if [ ! -s "$APP/assets/cacerts.pem" ]; then
    curl -L --retry 5 --retry-delay 3 -o "$APP/assets/cacerts.pem" "$URL_CACERT"
fi
echo "cacerts.pem: $(stat -c%s "$APP/assets/cacerts.pem") bytes, $(grep -c 'BEGIN CERTIFICATE' "$APP/assets/cacerts.pem") 个根证书"

# ------------------------------------------------------- 5. 签名密钥 + 图标
step "5/5 签名密钥与应用图标"
if [ -f "$KEYSTORE" ]; then
    echo "keystore 已存在，跳过（切勿删除）"
else
    "$JAVA_HOME/bin/keytool.exe" -genkeypair \
        -keystore "$KEYSTORE" -storetype JKS \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
        -dname "CN=LichessOld, OU=Dev, O=LichessOld, L=NA, ST=NA, C=CN"
    echo "已生成 $KEYSTORE"
fi

if [ -n "$PY" ]; then
    "$PY" "$ROOT/scripts/make_icon.py" "$APP/res/drawable/ic_launcher.png"
else
    echo "警告：找不到 python，跳过图标生成"
fi

step "完成"
echo "工具链就绪："
echo "  JDK      : $JAVA_HOME"
echo "  build-tools: $BT"
echo "  平台包   : $AJAR"
echo "  TLS 库   : $SCJAR"
echo
echo "下一步： bash scripts/build.sh 0.1.0"
