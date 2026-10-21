#!/bin/bash
# 手动流水线构建 谷歌服务安装助手 APK（无 Gradle）
set -e
cd "$(dirname "$0")"

export JAVA_HOME=$HOME/workspace/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:$PATH
SDK=$HOME/workspace/android-sdk
BT=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar

rm -rf build && mkdir -p build/classes build/dex

echo "== 1. aapt2 编译资源并链接 =="
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/app-unsigned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    -A assets \
    --no-compress \
    --auto-add-overlay

echo "== 2. javac 编译 =="
javac --release 8 -cp "$ANDROID_JAR" -d build/classes \
    src/com/muse/gmssetup/MainActivity.java \
    src/com/muse/gmssetup/FileProvider.java

echo "== 3. d8 转 dex =="
$BT/d8 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name "*.class")

echo "== 4. 塞入 dex =="
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)

echo "== 5. 对齐 + 签名 =="
$BT/zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk
if [ ! -f YOUR_KEYSTORE.jks ]; then
    keytool -genkeypair -keystore YOUR_KEYSTORE.jks -alias gms \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass YOUR_KEYSTORE_PASSWORD -keypass YOUR_KEYSTORE_PASSWORD -dname "CN=GMS Setup, O=Muse, C=CN" 2>/dev/null
fi
$BT/apksigner sign --ks YOUR_KEYSTORE.jks --ks-pass pass:YOUR_KEYSTORE_PASSWORD --key-pass pass:YOUR_KEYSTORE_PASSWORD \
    --out build/gms-setup.apk build/app-aligned.apk

echo "== 6. 验收 =="
$BT/apksigner verify --print-certs build/gms-setup.apk | head -3
$BT/aapt2 dump badging build/gms-setup.apk | head -4
ls -la build/gms-setup.apk
echo "BUILD OK"
