# Gitee Go 构建：自动安装 Android SDK 并编译 APK
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
    
    cd $WORKSPACE
    echo "=== Android SDK installed ==="
fi

# 生成 local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 编译
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon --stacktrace
