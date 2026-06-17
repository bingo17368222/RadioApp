#!/bin/bash
# Gitee Go 构建：自动安装 Android SDK 并编译 APK

# 使用绝对路径
cd /root/workspace/bingostudio/RadioApp || cd "$(dirname "$0")"

export ANDROID_HOME=/opt/android-sdk

# 如果 SDK 不存在，则下载安装
if [ ! -d "$ANDROID_HOME/platforms/android-33" ]; then
    echo "=== Installing Android SDK ==="
    mkdir -p $ANDROID_HOME
    cd /tmp
    
    # 下载 commandline-tools
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-8512546_latest.zip -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mkdir -p $ANDROID_HOME/cmdline-tools
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest
    
    # 接受许可并安装 SDK
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
        "platforms;android-33" \
        "platform-tools" \
        "build-tools;33.0.2"
    
    echo "=== Android SDK installed ==="
fi

# 回到项目目录
cd /root/workspace/bingostudio/RadioApp || cd "$(dirname "$0")"

# 生成 local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 生成兼容 JDK 11 的 debug keystore（替换仓库中的新版 keystore）
KEYSTORE_FILE="app/debug.keystore"
if [ -f "$KEYSTORE_FILE" ]; then
    echo "=== Replacing keystore with JDK 11 compatible version ==="
    rm -f "$KEYSTORE_FILE"
fi
keytool -genkeypair -v -keystore "$KEYSTORE_FILE" \
    -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" \
    -storetype PKCS12

# 编译
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon --stacktrace
