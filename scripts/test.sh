#!/usr/bin/env bash
# =============================================================================
# 桌面静态测试 + 真实网络测试。
#
# 把 util/ json/ net/ chess/ 这几个**纯 Java 包**编译到桌面 JVM 上跑，
# 用的就是手机上跑的那份代码。因为这台机器内存极小（4GB，空闲常 <400MB），
# 所有 JVM 参数都按最小内存配。
#
#   用法： bash scripts/test.sh            跑全部测试
#          bash scripts/test.sh SmokeTest  只跑某一个
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build.config.sh
source "$HERE/build.config.sh"

OUT="$BUILD/test-classes"
mkdir -p "$OUT" "$TMP"

JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"

# 这台机器上必须的省内存参数
LOWJAVAC=(-J-Xms24m -J-Xmx192m -J-XX:+UseSerialGC -J-Xss512k
          -J-XX:MaxMetaspaceSize=96m -J-XX:ReservedCodeCacheSize=24m
          -J-XX:CompressedClassSpaceSize=32m -J-XX:-UsePerfData
          -J-XX:TieredStopAtLevel=1)

LOWRUN=(-Xms24m -Xmx256m -XX:+UseSerialGC -Xss512k
        -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=24m
        -XX:CompressedClassSpaceSize=32m -XX:-UsePerfData
        -XX:TieredStopAtLevel=1 "-XX:ErrorFile=$TMP/hs_err_%p.log"
        "-Djava.io.tmpdir=$TMP")

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

rm -rf "$OUT"
mkdir -p "$OUT"

if [ "${#CORE_SRC[@]}" -gt 0 ]; then
    "$JAVAC" "${LOWJAVAC[@]}" -encoding UTF-8 -Xlint:-options \
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
    "$JAVAC" "${LOWJAVAC[@]}" -encoding UTF-8 -Xlint:-options \
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
    "$JAVA" "${LOWRUN[@]}" \
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
