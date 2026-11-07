#!/bin/sh
# 打热更新补丁（只含 ChatApp 及其内部类）→ d8 → ECDSA 签名 → patch-<v>.dex + version.json
#
# 用法： sh build_patch.sh <补丁号> ["说明"] [minShell]
#   例： sh build_patch.sh 1 "修复删除好友" 7
#
# 前置：先跑过 build_apk.sh（需要 build_tmp/classes 作为编译/引用类路径）
set -e
cd "$(dirname "$0")"

VERSION="${1:?用法: sh build_patch.sh <补丁号> [\"说明\"] [minShell]}"
NOTES="${2:-}"
MINSHELL="${3:-7}"
KEY="keys/private.pem"
[ -f "$KEY" ] || { echo "✗ 缺签名私钥 $KEY"; exit 1; }
[ -d build_tmp/classes ] || { echo "✗ 先跑一次 build_apk.sh（缺 build_tmp/classes）"; exit 1; }

TC=../toolchain
JAVA_HOME="$TC/jdk17"; export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"; export PATH
EMU="$TC/qemu-x86_64-static"
[ -x "$EMU" ] && export QEMU_LD_PREFIX="$TC/x86sys"
SDK="$TC/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

AVAIL_MB=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
if [ "$AVAIL_MB" -lt "${BUILD_MIN_MB:-400}" ] && [ "${FORCE_BUILD:-0}" != "1" ]; then
    echo "✗ 可用内存只有 ${AVAIL_MB}MB（要求 ≥ 400MB），先不编译"
    exit 3
fi
echo "== [0/4] 可用内存 ${AVAIL_MB}MB  补丁 v$VERSION（minShell=$MINSHELL）=="

# ---------- ① 只编译 ChatApp ----------
echo "== [1/4] 编译 ChatApp =="
rm -rf build_tmp/patch_classes build_tmp/patch_dex
mkdir -p build_tmp/patch_classes build_tmp/patch_dex out/patch
javac -J-Xmx${JAVAC_HEAP:-256m} -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "build_tmp/classes" \
    -d build_tmp/patch_classes \
    src/com/linglum/chat/ChatApp.java

# ---------- ② d8（只打补丁自己的 class） ----------
echo "== [2/4] 转 dex =="
"$BT/d8" --lib "$ANDROID_JAR" --min-api 21 \
    --classpath build_tmp/classes \
    --output build_tmp/patch_dex \
    $(find build_tmp/patch_classes -name '*.class')
DEX="out/patch/patch-$VERSION.dex"
cp build_tmp/patch_dex/classes.dex "$DEX"

# ---------- ③ 签名 ----------
echo "== [3/4] 签名 =="
openssl dgst -sha256 -sign "$KEY" -out "out/patch/patch-$VERSION.sig" "$DEX"
openssl dgst -sha256 -verify keys/public.pem -signature "out/patch/patch-$VERSION.sig" "$DEX" >/dev/null
SIG=$(base64 -w0 "out/patch/patch-$VERSION.sig")
SHA=$(sha256sum "$DEX" | cut -d' ' -f1)
SIZE=$(stat -c '%s' "$DEX")

# ---------- ④ 元信息 ----------
echo "== [4/4] 生成 version.json =="
python3 - "$VERSION" "$MINSHELL" "$NOTES" "$SHA" "$SIG" "$SIZE" > "out/patch/version.json" <<'PY'
import json, sys, time
v, min_shell, notes, sha, sig, size = sys.argv[1:7]
print(json.dumps({
    "patchVersion": int(v),
    "minShell": int(min_shell),
    "file": "patch-%s.dex" % v,
    "sha256": sha,
    "sig": sig,
    "bytes": int(size),
    "notes": notes,
    "created": int(time.time()),
}, ensure_ascii=False))
PY
echo "✓ 产物：$DEX（${SIZE}B）"
echo "  sha256: $SHA"
echo "  签名  : out/patch/patch-$VERSION.sig"
echo "  元信息: out/patch/version.json"
