#!/usr/bin/env bash
# =============================================================================
# 桌面静态测试 + 真实网络测试。
#
# 把 util/ json/ net/ chess/ 这几个**纯 Java 包**编译到桌面 JVM 上跑，
# 用的就是手机上跑的那份代码。
#
# JVM 参数**不在这里写死**，取自 machine-profile.sh 按物理内存判定的档位。
# 两台开发机内存差 4 倍，写死一套参数必然有一台出事。
#
# 输出目录带时间戳且**只增不删**：某些受限环境（沙箱 / 杀软）会拦住批量删除，
# 原来那句 `rm -rf "$OUT"` 会让整个脚本在第 3 步就死掉。
#
#   用法： bash scripts/test.sh            跑全部测试
#          bash scripts/test.sh SmokeTest  只跑某一个
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build.config.sh
source "$HERE/build.config.sh"

# 带时间戳的输出目录，天然避免「必须先删再建」
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT="$BUILD/test-classes/$RUN_ID"
mkdir -p "$OUT" "$TMP"
# 方便下次直接看最近一次结果，也便于用户手工清理
ln -sfn "$RUN_ID" "$BUILD/test-classes/latest" 2>/dev/null || true

JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"

# 桌面 JVM 参数：来自档位判定（high 全速 / low 省内存）
# shellcheck disable=SC2206
JAVAC_OPTS=(${JVM_DESKTOP:-})
# shellcheck disable=SC2206
RUN_OPTS=(${JVM_DESKTOP:-})
RUN_OPTS+=("-XX:ErrorFile=$TMP/hs_err_%p.log" "-Djava.io.tmpdir=$TMP")

echo "[test] 档位=$MACHINE_PROFILE  内存=${MACHINE_RAM_MB}MB  核数=${MACHINE_CPUS:-?}"

step() { echo; echo "======== $* ========"; }

# --------------------------------------------------------------- 1. 核心包
step "编译纯 Java 核心（util / json / net / chess）"
CORE_SRC=()
for pkg in util json net chess; do
    d="$APP/src/org/lichessold/$pkg"
    if [ -d "$d" ]; then
        while IFS= read -r f; do CORE_SRC+=("$f"); done < <(find "$d" -name '*.java')
    fi
done
echo "核心源文件: ${#CORE_SRC[@]} 个"

if [ "${#CORE_SRC[@]}" -gt 0 ]; then
    "$JAVAC" "${JAVAC_OPTS[@]/#/-J}" -encoding UTF-8 -Xlint:-options \
        -cp "$SCJAR" -d "$OUT" "${CORE_SRC[@]}" || {
        echo "核心编译失败"; exit 1; }
fi
echo "核心 class: $(find "$OUT" -name '*.class' | wc -l) 个"

# --------------------------------------------------------------- 2. 测试类
step "编译测试类"
TEST_SRC=()
while IFS= read -r f; do TEST_SRC+=("$f"); done < <(find "$TESTS" -maxdepth 1 -name '*.java')
if [ "${#TEST_SRC[@]}" -gt 0 ]; then
    # 只依赖核心 class，不加载 sc-core.jar 的索引（省内存）
    "$JAVAC" "${JAVAC_OPTS[@]/#/-J}" -encoding UTF-8 -Xlint:-options \
        -cp "$OUT" -d "$OUT" "${TEST_SRC[@]}" || { echo "测试编译失败"; exit 1; }
fi
echo "测试类: $(find "$OUT" -maxdepth 1 -name '*.class' | wc -l) 个"

# --------------------------------------------------------------- 3. 运行
RUN_ONE="${1:-}"
FAILED=0

run_class() {
    local cls="$1"; shift
    echo
    echo "############################################################"
    echo "#  $cls"
    echo "############################################################"
    "$JAVA" "${RUN_OPTS[@]}" \
        -cp "$OUT;$SCJAR" "$cls" "$@" || FAILED=1
}

ANCHOR="$APP/assets/cacerts.pem"
FIX1="$TESTS/fixtures/lichess-intermediate.pem"
FIX2="$TESTS/fixtures/lichess-root.pem"

if [ -n "$RUN_ONE" ]; then
    run_class "$RUN_ONE" "$ANCHOR" "$FIX1" "$FIX2"
else
    [ -f "$OUT/JsonTest.class" ]    && run_class JsonTest
    [ -f "$OUT/ChessTest.class" ]   && run_class ChessTest
    [ -f "$OUT/AiTest.class" ]      && run_class AiTest
    [ -f "$OUT/SeekTest.class" ]    && run_class SeekTest
    [ -f "$OUT/SmokeTest.class" ]   && run_class SmokeTest "$ANCHOR" "$FIX1" "$FIX2"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "########## 全部测试通过 ##########"
else
    echo "########## 有测试失败 ##########"
fi
exit "$FAILED"
