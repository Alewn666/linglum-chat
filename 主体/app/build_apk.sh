#!/bin/sh
# 灵珑聊天 APK 构建（无 Gradle，纯命令行工具链）
#
# 性能优化（2026-11-01）：
#  ① 资源编译缓存：res/ + AndroidManifest 没变就跳过 aapt2（它在 ARM 上靠 qemu 模拟，最慢）
#  ② 第三方库预编译：libs/*.jar（如 zxing 600KB）只 dex 一次，产物缓存成 classes2.dex
#  ③ 只 dex 自己的 class，体积小、d8 快
#
# ⚠️ 2026-11-01 说明：曾经尝试引入 AndroidX / Material3 的 AAR 资源合并，
#    但 2GB 内存的 NAS 撑不住（qemu 版 aapt2 连续编译 29 个库资源会 OOM 重启），
#    该方案已**放弃并清理**，本脚本回到"只用自己的代码 + zxing"的干净状态。
set -e

cd "$(dirname "$0")"
TC="../toolchain"
JAVA_HOME="$TC/jdk17"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

SDK="$TC/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="$TC/keystore/debug.keystore"

[ -f "$ANDROID_JAR" ] || { echo "✗ 缺 android.jar"; exit 1; }

# ---------- ⓿ 内存护栏（2026-11-01 加）----------
# 这台 NAS 只有 2GB 内存 + 1GB zram 交换，编译（javac / qemu 版 aapt2 / d8）是唯一会把
# 整机拖进 OOM 的步骤：17:18 那次全局 OOM 把 agent 进程杀了、容器被重启。
# 规则：可用内存 < 600MB 就不许开编译；确实要硬来就 FORCE_BUILD=1。
# 更稳的跑法（进程被 cgroup 限制，超了只杀它自己，不动整机）：
#   sudo -n systemd-run --scope -p MemoryMax=600M -p MemorySwapMax=400M -- sh build_apk.sh
AVAIL_MB=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
if [ "$AVAIL_MB" -lt "${BUILD_MIN_MB:-600}" ] && [ "${FORCE_BUILD:-0}" != "1" ]; then
    echo "✗ 可用内存只有 ${AVAIL_MB}MB（要求 ≥ ${BUILD_MIN_MB:-600}MB），先不编译。"
    echo "  等内存空出来再跑；确实要强行编译：FORCE_BUILD=1 sh build_apk.sh"
    exit 3
fi
echo "== [0/6] 可用内存 ${AVAIL_MB}MB =="

EMU=""
if [ -x "$TC/qemu-x86_64-static" ]; then EMU="$TC/qemu-x86_64-static"
elif [ -x /usr/bin/qemu-x86_64-static ]; then EMU=/usr/bin/qemu-x86_64-static; fi
if [ -n "$EMU" ]; then export QEMU_LD_PREFIX="$TC/x86sys"; fi

mkdir -p build_tmp/classes build_tmp/dex out libs

# ---------- ① 资源（带缓存） ----------
RES_HASH=$(
  { find res -type f -print0 2>/dev/null | sort -z | xargs -0 md5sum 2>/dev/null
    md5sum AndroidManifest.xml; } | md5sum | cut -d' ' -f1
)
if [ -f build_tmp/base_res.apk ] && [ "$(cat build_tmp/res_hash 2>/dev/null)" = "$RES_HASH" ]; then
    echo "== [1/6] 资源链接（缓存命中，跳过 aapt2）=="
else
    echo "== [1/6] 资源链接 =="
    rm -rf build_tmp/res
    mkdir -p build_tmp/res
    RES_ARG=""
    if [ -d res ]; then
        $EMU "$BT/aapt2" compile --dir res -o build_tmp/res/res.zip
        RES_ARG="build_tmp/res/res.zip"
    fi
    $EMU "$BT/aapt2" link \
        --manifest AndroidManifest.xml \
        -I "$ANDROID_JAR" \
        $RES_ARG \
        -o build_tmp/base_res.apk
    echo "$RES_HASH" > build_tmp/res_hash
fi
cp build_tmp/base_res.apk build_tmp/base.apk

# ---------- ② 编译 Java ----------
echo "== [2/6] 编译 Java =="
rm -rf build_tmp/classes
mkdir -p build_tmp/classes
CP="$(ls libs/*.jar 2>/dev/null | tr '\n' ':')"
# ⚠️ -J-Xmx320m：qemu 里跑 JDK17，默认堆按物理内存 1/4 算（~480MB），
# 2026-11-01 曾把 2GB 的 NAS 压到 OOM 卡死重启 —— 给 JVM 加硬上限，别再犯。
javac -J-Xmx${JAVAC_HEAP:-320m} -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$CP" \
    -d build_tmp/classes \
    $(find src -name '*.java')

# ---------- ③ dex ----------
echo "== [3/6] 转 dex =="
rm -rf build_tmp/dex
mkdir -p build_tmp/dex
"$BT/d8" --lib "$ANDROID_JAR" --min-api 21 \
    --output build_tmp/dex \
    $(find build_tmp/classes -name '*.class')

for JAR in $(ls libs/*.jar 2>/dev/null); do
    NAME=$(basename "$JAR" .jar)
    CACHE="libs/.dex_$NAME.dex"
    if [ -f "$CACHE" ]; then
        echo "   复用已编译的库：$NAME"
    else
        echo "   预编译库（只需一次）：$NAME"
        rm -rf build_tmp/libdex && mkdir -p build_tmp/libdex
        "$BT/d8" --lib "$ANDROID_JAR" --min-api 21 --output build_tmp/libdex "$JAR"
        mv build_tmp/libdex/classes.dex "$CACHE"
    fi
done

# ---------- ④ 装进 APK ----------
echo "== [4/6] 装进 APK =="
(cd build_tmp/dex && zip -q ../base.apk classes.dex)
IDX=2
for JAR in $(ls libs/*.jar 2>/dev/null); do
    NAME=$(basename "$JAR" .jar)
    cp "libs/.dex_$NAME.dex" build_tmp/dex/classes$IDX.dex
    (cd build_tmp/dex && zip -q ../base.apk classes$IDX.dex)
    IDX=$((IDX + 1))
done

# ---------- ⑤ 对齐 ----------
echo "== [5/6] 对齐 =="
$EMU "$BT/zipalign" -f 4 build_tmp/base.apk build_tmp/aligned.apk

# ---------- ⑥ 签名 ----------
echo "== [6/6] 签名 =="
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$TC/keystore"
    keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Linglum Debug, O=Linglum, C=CN" 2>/dev/null
fi
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out out/linglum-chat.apk \
    build_tmp/aligned.apk

echo "== 完成 =="
ls -lh out/linglum-chat.apk
$EMU "$BT/aapt2" dump badging out/linglum-chat.apk 2>/dev/null | head -2 || true
