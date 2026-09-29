#!/usr/bin/env bash
# =============================================================================
# lichess desktop 构建脚本
#
# 产物：desktop/dist/LichessOldDesktop-<版本>.jar（单文件，双击即运行）
#
# 设计要点
# --------
# 1. **复用 Android 项目的纯 Java 内核**：chess/ json/ net/ util/ 四个包
#    零 Android 依赖，直接拿 app/src 下的源码编译，一行都不改。
#    这样"桌面版跑得对"就等于"手机版跑得对"，是同一个引擎。
# 2. **只复用 FileLogSink 一个 log 包文件**：它是纯 Java 的，其余 log/*.java
#    依赖 Context，不参与编译。
# 3. 机器档位 / 临时目录 / 盘符反推全部沿用 scripts/build.config.sh，
#    不写死盘符、不往 C 盘写临时文件。
#
# ⚠️ 磁盘友好（本项目的开发盘是 exFAT，簇 128KB，每个文件都吃掉一整个簇）
# ---------------------------------------------------------------------------
# 在这块盘上「文件个数」比「文件大小」贵得多：写 1460 个小文件 = 187 MB，
# 而且删除同样慢（USB 2.0 机械盘）。所以：
#   - 编译输出固定用 build/classes/，javac 原地覆盖，**从不删除、从不重建目录**
#   - 依赖 jar 用 tools/merge_jar.py **流式**合并，不落盘解压
#   - jar 用 jar cfm 整体覆盖，不先 rm
# 一次增量构建只写 209 个 class + 1 个 jar（约 30 MB 簇占用）。
#
# 用法：
#   bash desktop/build.sh              # 构建 + 自检 + 离线界面冒烟测试
#   bash desktop/build.sh --regen      # 先重新生成棋子矢量数据再构建
#   bash desktop/build.sh --smoke      # 连联网页面一起冒烟测试（慢，需要网络）
#   bash desktop/build.sh --run        # 构建完直接启动
#   bash desktop/build.sh --full       # 连类文件一起重建（源码删过文件时才需要）
# =============================================================================
set -euo pipefail

DESKTOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=../scripts/build.config.sh
source "$DESKTOP_DIR/../scripts/build.config.sh"

DESKTOP="$ROOT/desktop"
SRC="$DESKTOP/src"
BUILD_ROOT="$DESKTOP/build"
CLASSES="$BUILD_ROOT/classes"
DIST="$DESKTOP/dist"
ASSETS="$DESKTOP/assets"
TOOLS="$DESKTOP/tools"
JAR_NAME="LichessOldDesktop-$(sed -n 's/.*VERSION *= *"\([^"]*\)".*/\1/p' \
    "$SRC/org/lichessold/desktop/DesktopVersion.java" | head -1)"
JAR="$DIST/$JAR_NAME.jar"

REGEN=0
RUN=0
SMOKE=0
FULL=0
for arg in "$@"; do
    case "$arg" in
        --regen) REGEN=1 ;;
        --run)   RUN=1 ;;
        --smoke) SMOKE=1 ;;
        --full)  FULL=1 ;;
        *) echo "未知参数: $arg" >&2; exit 2 ;;
    esac
done

echo "=================================================================="
echo " lichess desktop 构建"
echo "  项目根   : $ROOT"
echo "  机器档位 : $MACHINE_PROFILE ($MACHINE_RAM_MB MB / $MACHINE_CPUS 核) — $MACHINE_NOTE"
echo "  输出     : $JAR"
echo "=================================================================="

# ---------------------------------------------------------------------------
# 0. 依赖检查
# ---------------------------------------------------------------------------
if [ ! -f "$SCJAR" ]; then
    echo "!! 缺少依赖 $SCJAR" >&2
    echo "   先跑 bash scripts/setup.sh" >&2
    exit 1
fi
if [ ! -f "$ASSETS/cacerts.pem" ]; then
    if [ -f "$APP/assets/cacerts.pem" ]; then
        mkdir -p "$ASSETS"
        cp "$APP/assets/cacerts.pem" "$ASSETS/cacerts.pem"
        echo "-- 已从 app/assets 复制根证书包"
    else
        echo "!! 缺少 $ASSETS/cacerts.pem（信任锚，没有它连不上 lichess）" >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# 1. 生成棋子矢量数据（从 Android 版的 PieceArt.java 转换）
# ---------------------------------------------------------------------------
if [ "$REGEN" = "1" ] || [ ! -f "$SRC/org/lichessold/desktop/PieceArt2D.java" ]; then
    if [ -n "$PY" ]; then
        echo "-- 生成 PieceArt2D.java"
        "$PY" "$TOOLS/make_pieceart2d.py"
    else
        echo "!! 没找到 python，且 PieceArt2D.java 不存在，无法继续" >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# 2. 生成构建时间戳
# ---------------------------------------------------------------------------
BUILD_TIME="$(date '+%Y-%m-%d %H:%M:%S')"
cat > "$SRC/org/lichessold/desktop/DesktopBuildInfo.java" <<EOF
package org.lichessold.desktop;

/**
 * 构建信息 —— **本文件由 desktop/build.sh 自动生成，请勿手改**。
 *
 * 对应 Android 版构建时注入的 BuildInfo（那边从 PackageManager 读版本）。
 */
public final class DesktopBuildInfo {

    /** 构建时刻（本地时间）。 */
    public static final String BUILD_TIME = "$BUILD_TIME";

    private DesktopBuildInfo() {
    }
}
EOF
echo "-- 构建时间 $BUILD_TIME"

# ---------------------------------------------------------------------------
# 3. 收集源文件
# ---------------------------------------------------------------------------
# 纯 Java 内核：零 Android 依赖，直接复用 app/src 下的源码
CORE_SRC=()
for pkg in chess json net util; do
    while IFS= read -r f; do CORE_SRC+=("$f"); done \
        < <(find "$APP/src/org/lichessold/$pkg" -name '*.java' | sort)
done
# FileLogSink 是 log 包里唯一一个纯 Java 文件（其余依赖 Context）
CORE_SRC+=("$APP/src/org/lichessold/log/FileLogSink.java")

DESKTOP_SRC=()
while IFS= read -r f; do DESKTOP_SRC+=("$f"); done \
    < <(find "$SRC/org/lichessold/desktop" -name '*.java' | sort)

echo "-- 核心源码 ${#CORE_SRC[@]} 个文件（复用自 app/src）"
echo "-- 桌面源码 ${#DESKTOP_SRC[@]} 个文件"

# ---------------------------------------------------------------------------
# 4. 编译（原地覆盖 build/classes，不删目录）
# ---------------------------------------------------------------------------
mkdir -p "$CLASSES" "$DIST" "$TMP"

# --full：源码里删过文件时用，先把上次的 class 清掉。
# 平时不要用 —— 在 exFAT 上删 200 个文件比重新编译还慢。
if [ "$FULL" = "1" ]; then
    echo "-- --full：清理上次的 class"
    while IFS= read -r f; do rm -f "$f"; done \
        < <(find "$CLASSES" -name '*.class' 2>/dev/null | head -n 200)
fi

echo "-- javac 编译中…"
"$JAVA_HOME/bin/javac.exe" \
    -encoding UTF-8 \
    -J-Xmx1g \
    -cp "$SCJAR" \
    -d "$CLASSES" \
    "${CORE_SRC[@]}" "${DESKTOP_SRC[@]}"

CLASS_COUNT="$(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ')"
echo "-- 编译完成，$CLASS_COUNT 个 class"

# ---------------------------------------------------------------------------
# 5. 打包成单文件可执行 JAR
# ---------------------------------------------------------------------------
echo "-- 打包 JAR…"
mkdir -p "$CLASSES/assets"
cp "$ASSETS/cacerts.pem" "$CLASSES/assets/cacerts.pem"

cat > "$BUILD_ROOT/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Main-Class: org.lichessold.desktop.DesktopApp
Implementation-Title: lichess desktop
Implementation-Vendor: lichessold
EOF

# jar cfm 会整体覆盖同名文件，不需要先 rm
"$JAVA_HOME/bin/jar.exe" cfm "$JAR" "$BUILD_ROOT/MANIFEST.MF" -C "$CLASSES" .

# 把依赖（spongycastle）合并进来，做成真正的单文件。
# 用 Python 流式合并，不解压到磁盘 —— 见 tools/merge_jar.py 的说明。
echo "-- 合并依赖（spongycastle）…"
if [ -n "$PY" ] && [ -f "$TOOLS/merge_jar.py" ]; then
    "$PY" "$TOOLS/merge_jar.py" "$JAR" "$SCJAR"
else
    # 没有 python 时的兜底：解压到临时目录再加回去（慢，且吃簇）
    echo "   （没有 python，退回解压方式）"
    DEP_STAGE="$BUILD_ROOT/deps-stage"
    mkdir -p "$DEP_STAGE"
    ( cd "$DEP_STAGE" && "$JAVA_HOME/bin/jar.exe" xf "$SCJAR" ) 2>/dev/null || true
    if [ -d "$DEP_STAGE/org" ]; then
        "$JAVA_HOME/bin/jar.exe" uf "$JAR" -C "$DEP_STAGE" org
    fi
fi

# 记录依赖清单，方便日后核对
cat > "$DIST/$JAR_NAME.deps.txt" <<EOF
lichess desktop $JAR_NAME
构建时间: $BUILD_TIME
机器档位: $MACHINE_PROFILE ($MACHINE_RAM_MB MB / $MACHINE_CPUS 核)

打包内容:
  org/lichessold/desktop/*      桌面版界面与平台层（本目录源码）
  org/lichessold/{chess,json,net,util}/*   复用自 app/src 的纯 Java 内核
  org/lichessold/log/FileLogSink.java      复用自 app/src（纯 Java）
  org/spongycastle/**           来自 toolchain/libs/sc-core-1.58.0.0.jar
  assets/cacerts.pem            信任锚（Mozilla CA bundle）

运行: java -jar $JAR_NAME.jar
EOF

JAR_BYTES="$(stat -c%s "$JAR" 2>/dev/null || wc -c < "$JAR")"
JAR_KB=$(( JAR_BYTES / 1024 ))

echo ""
echo "=================================================================="
echo " 构建完成"
echo "   $JAR"
echo "   大小 $JAR_KB KB"
echo ""
echo " 运行方式（任选其一）："
echo "   双击   desktop/run.bat"
echo "   命令行 $JAVA_HOME/bin/java.exe -jar \"$JAR\""
echo "=================================================================="

# ---------------------------------------------------------------------------
# 6. 自检：棋规 / 棋子数据 / 时间档 / 信任锚，任何一项失败就构建失败
# ---------------------------------------------------------------------------
echo "-- 自检中…"
echo ""
"$JAVA_HOME/bin/java.exe" -cp "$JAR" org.lichessold.desktop.SelfCheck
echo ""

# ---------------------------------------------------------------------------
# 7. 界面冒烟测试
#    --smoke      连联网页面一起测（构造时就会发请求，慢）
#    默认         只测离线页面（快，headless 环境自动跳过）
# ---------------------------------------------------------------------------
echo "-- 界面冒烟测试"
if [ "$SMOKE" = "1" ]; then
    "$JAVA_HOME/bin/java.exe" -cp "$JAR" org.lichessold.desktop.DesktopApp --smoke --smoke-net || {
        echo "!! 界面冒烟测试失败" >&2
        exit 1
    }
else
    "$JAVA_HOME/bin/java.exe" -cp "$JAR" org.lichessold.desktop.DesktopApp --smoke || {
        echo "!! 界面冒烟测试失败" >&2
        exit 1
    }
fi
echo ""

if [ "$RUN" = "1" ]; then
    echo "-- 启动界面"
    "$JAVA_HOME/bin/java.exe" $JVM_DESKTOP -jar "$JAR"
fi
