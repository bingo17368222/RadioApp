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

# 编译成功后上传 APK 到码云 Release
if [ $? -eq 0 ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        echo "=== Uploading APK to Gitee Release ==="
        APK_SIZE=$(stat -c%s "$APK_PATH")
        echo "APK size: $APK_SIZE bytes"
        
        GITEE_TOKEN="c49181ed3234c05100ef7db8a2b979c6"
        RELEASE_TAG="debug-latest"
        
        # 检查 Release 是否已存在（纯 shell 解析 JSON）
        RELEASE_LIST=$(curl -s "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases?access_token=$GITEE_TOKEN&page=1&per_page=5")
        RELEASE_ID=$(echo "$RELEASE_LIST" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
        
        if [ -n "$RELEASE_ID" ]; then
            echo "Deleting existing Release #$RELEASE_ID"
            curl -s -X DELETE "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases/$RELEASE_ID?access_token=$GITEE_TOKEN" > /dev/null
            sleep 1
        fi
        
        # 创建新 Release（先不带附件）
        echo "Creating new Release..."
        RELEASE_RESULT=$(curl -s -X POST "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases" \
            -H "Content-Type: application/json" \
            -d "{\"access_token\":\"$GITEE_TOKEN\",\"tag_name\":\"$RELEASE_TAG\",\"name\":\"Debug Build $(date +%Y%m%d-%H%M)\",\"body\":\"Auto-generated debug APK from Gitee Go pipeline\",\"prerelease\":true,\"target_commitish\":\"main\"}")
        
        # 解析 Release ID（纯 shell）
        NEW_RELEASE_ID=$(echo "$RELEASE_RESULT" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
        
        if [ -n "$NEW_RELEASE_ID" ]; then
            echo "Release created: #$NEW_RELEASE_ID"
            
            # 单独上传 APK 附件
            echo "Uploading APK attachment..."
            UPLOAD_RESULT=$(curl -s -X POST "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases/$NEW_RELEASE_ID/attach_files?access_token=$GITEE_TOKEN" \
                -F "file=@$APK_PATH")
            echo "Upload result: $UPLOAD_RESULT" | head -c 300
            echo ""
        else
            echo "Failed to create Release: $RELEASE_RESULT" | head -c 300
            echo ""
        fi
        
        echo "=== Upload complete ==="
    else
        echo "WARNING: APK file not found at $APK_PATH"
    fi
else
    echo "Build failed, skipping APK upload"
fi
