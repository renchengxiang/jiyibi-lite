#!/bin/bash
# build.sh —— 不依赖 Gradle / AGP：aapt2 + javac + d8 + zipalign + apksigner
set -e

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
OUT="build"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 0/6 解包依赖（幂等：只在缺失时从 AAR/JAR 解出，产物不入库）
if [ ! -f libs/aar/classes.jar ]; then
  echo "==> 0/6 解包 Vosk AAR"
  mkdir -p libs/aar
  (cd libs/aar && unzip -oq ../vosk-android-0.3.47.aar)
fi
if [ ! -f libs/jna/classes.jar ]; then
  echo "==> 0/6 解包 JNA AAR"
  mkdir -p libs/jna
  (cd libs/jna && unzip -oq ../jna.aar)
fi
if [ ! -d assets/model-cn ]; then
  echo "⚠️  assets/model-cn 不存在：将构建出「无语音模型」的 APK（只能手输）。"
  echo "    获取方式见 README「准备语音模型」。"
  mkdir -p assets/model-cn
fi

echo "==> 1/6 编译资源"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "==> 2/6 链接资源与清单（含离线语音模型 assets）"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$JAR" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  -A assets \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --auto-add-overlay

echo "==> 3/6 编译 Java"
find java "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
CP="$JAR:libs/aar/classes.jar:libs/jna/classes.jar"
"$JDK/bin/javac" --release 11 -encoding UTF-8 -nowarn \
  -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> 4/6 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$JAR" --min-api 26 --output "$OUT/dex" \
  @"$OUT/classes.txt" libs/aar/classes.jar libs/jna/classes.jar
echo "==> 5/6 打包 classes.dex 与原生库"
mkdir -p "$OUT/lib/arm64-v8a"
cp libs/aar/jni/arm64-v8a/libvosk.so "$OUT/lib/arm64-v8a/"
cp libs/jna/jni/arm64-v8a/libjnidispatch.so "$OUT/lib/arm64-v8a/"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "../unsigned.apk" classes.dex)
(cd "$OUT" && zip -q -r -X "unsigned.apk" lib)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名"
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app-debug.apk "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs app-debug.apk | head -5
echo
echo "✓ 产物：$(pwd)/app-debug.apk  ($(du -h app-debug.apk | cut -f1))"
