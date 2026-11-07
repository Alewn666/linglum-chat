#!/bin/sh
# 只编译不打包：改代码后秒级看语法错误（正式发布仍用 build_apk.sh）
set -e
cd "$(cd "$(dirname "$0")/.." && pwd)"
TC=../toolchain
JAVA_HOME="$TC/jdk17"; export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"; export PATH
EMU="$TC/qemu-x86_64-static"
[ -x "$EMU" ] && export QEMU_LD_PREFIX="$TC/x86sys"
ANDROID_JAR="$TC/sdk/platforms/android-34/android.jar"
CP="$(ls libs/*.jar 2>/dev/null | tr '\n' ':')"
rm -rf build_tmp/classes
mkdir -p build_tmp/classes
javac -J-Xmx${JAVAC_HEAP:-256m} -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$ANDROID_JAR" -classpath "$CP" \
    -d build_tmp/classes \
    $(find src -name '*.java')
echo "✓ javac OK（build_tmp/classes）"
