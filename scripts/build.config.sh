#!/usr/bin/env bash
# =============================================================================
# LichessOld 构建配置。
#
# 重要：本机盘符会变（插上手机后 Windows 会重排盘符，E: 曾经变成 F:），
# 所以这里**不写死盘符**，而是从本脚本所在位置反推项目根目录。
# 需要强制指定时： export LICHESSOLD_ROOT="X:/path/to/project"
#
# 唯一硬性约束：构建过程中产生的临时文件不能落到 C 盘。
#
# JVM 内存参数**不在这里写死**，由 scripts/machine-profile.sh 按物理内存自动判定。
# 本项目在两台配置差 4 倍的电脑上开发过，参数搞反了两头都出事，见那个脚本的注释。
# =============================================================================

if [ -n "${LICHESSOLD_ROOT:-}" ]; then
    ROOT="$LICHESSOLD_ROOT"
else
    _here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    _root_posix="$(cd "$_here/.." && pwd)"
    # pwd -W 把 /f/xxx 转成 F:/xxx（Windows 原生工具只认后者）
    ROOT="$(cd "$_root_posix" && pwd -W 2>/dev/null || echo "$_root_posix")"
fi
export ROOT
export ROOT_DRIVE="${ROOT%%:*}"

export TOOLCHAIN="$ROOT/toolchain"
export DOWNLOADS="$TOOLCHAIN/downloads"
export JAVA_HOME="$TOOLCHAIN/jdk8"
export BT="$TOOLCHAIN/android-build-tools/28.0.3"
export AJAR="$TOOLCHAIN/libs/android-2.3.3.jar"
export SCJAR="$TOOLCHAIN/libs/sc-core-1.58.0.0.jar"

export APP="$ROOT/app"
export TESTS="$ROOT/tests"
export BUILD="$ROOT/build"
export DIST="$ROOT/dist"
export LOGS="$ROOT/logs"
export KEYSTORE="$ROOT/keystore/lichessold.jks"
export KEY_ALIAS="lichessold"

# 签名口令。自用项目，和 Android 的 debug.keystore 一样属于「公开的固定口令」，
# 作用只是保证升级时签名一致，不是安全边界。令牌的安全边界在手机本地。
export STORE_PASS="lichessold2011"

# 下载源
export URL_JDK="https://cdn.azul.com/zulu/bin/zulu8.84.0.15-ca-jdk8.0.442-win_x64.zip"
export URL_JDK_ALT="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.zip"
export URL_BT="https://dl.google.com/android/repository/build-tools_r28.0.3-windows.zip"
# 真正的 API 10 android.jar（含 java.* / javax.* 存根，2767 个类）。
# Google 仓库已下架 platforms;android-10；Maven Central 的
# com.google.android:android:2.3.3 只有 android.* 没有 java.*，不能当 javac 的
# -bootclasspath 用（会报 "Unable to find package java.lang"）。
export URL_AJAR="https://raw.githubusercontent.com/Sable/android-platforms/master/android-10/android.jar"
export URL_SC="https://repo1.maven.org/maven2/com/madgag/spongycastle/core/1.58.0.0/core-1.58.0.0.jar"
export URL_CACERT="https://curl.se/ca/cacert.pem"
export URL_OPENAPI="https://lichess.org/api/openapi.yaml"

# ---------------------------------------------------------------------------
# 环境准备：PATH 和临时目录全部指向项目所在盘（绝不落 C 盘）
# ---------------------------------------------------------------------------
export PATH="$JAVA_HOME/bin:$BT:$PATH"
export TMP="$BUILD/tmp"
export TEMP="$TMP"
export TMPDIR="$TMP"

mkdir -p "$BUILD" "$DIST" "$LOGS" "$TMP" "$ROOT/keystore" "$TESTS" 2>/dev/null || true

if command -v python >/dev/null 2>&1; then
    export PY=python
elif command -v python3 >/dev/null 2>&1; then
    export PY=python3
else
    export PY=""
fi

# ---------------------------------------------------------------------------
# 机器档位：按物理内存自动选 JVM 参数（low / mid / high）
#   低配机沿用高配机参数 → d8 崩在 "malloc failed ... Chunk::new"
#   高配机沿用低配机参数 → 白白慢好几倍
# ---------------------------------------------------------------------------
# shellcheck source=machine-profile.sh
source "$ROOT/scripts/machine-profile.sh"

# ---------------------------------------------------------------------------
# 直接调用 java -cp 跑 d8 / dx / apksigner，避免 .bat 在 git bash 下的执行问题。
# ---------------------------------------------------------------------------
run_d8() {
    "$JAVA_HOME/bin/java.exe" $JVM_BUILD \
        -XX:ErrorFile="$BUILD/hs_err_%p.log" \
        -Djava.io.tmpdir="$TMP" \
        -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"
}

# dx 是 d8 的前身，jar 只有 1MB，内存占用低得多。d8 失败时的兜底。
run_dx() {
    "$JAVA_HOME/bin/java.exe" $JVM_BUILD \
        -XX:ErrorFile="$BUILD/hs_err_%p.log" \
        -Djava.io.tmpdir="$TMP" \
        -cp "$BT/lib/dx.jar" com.android.dx.command.Main \
        --dex --min-sdk-version=10 --output="$BUILD/dex" "$@"
}

run_apksigner() {
    "$JAVA_HOME/bin/java.exe" $JVM_SIGN \
        -XX:ErrorFile="$BUILD/hs_err_%p.log" \
        -Djava.io.tmpdir="$TMP" \
        -jar "$BT/lib/apksigner.jar" "$@"
}

# 桌面 JVM（跑静态测试用，与 Android 无关）
run_java() {
    "$JAVA_HOME/bin/java.exe" $JVM_DESKTOP \
        -XX:ErrorFile="$BUILD/hs_err_%p.log" \
        -Djava.io.tmpdir="$TMP" "$@"
}
