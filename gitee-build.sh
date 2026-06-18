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
        
        # 获取或创建 Release
        GITEE_TOKEN="c49181ed3234c05100ef7db8a2b979c6"
        RELEASE_TAG="debug-latest"
        
        # 检查 Release 是否已存在
        RELEASE_ID=$(curl -s "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases?access_token=$GITEE_TOKEN&page=1&per_page=5" | python3 -c "
import sys,json
try:
    releases=json.load(sys.stdin)
    for r in releases:
        if r.get('tag_name') == '$RELEASE_TAG':
            print(r['id'])
            break
except:
    print('')
" 2>/dev/null)
        
        if [ -n "$RELEASE_ID" ]; then
            echo "Updating existing Release #$RELEASE_ID"
            # 删除旧的 Release（因为码云 Release API 不支持更新附件）
            curl -s -X DELETE "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases/$RELEASE_ID?access_token=$GITEE_TOKEN" > /dev/null
        fi
        
        # 创建新 Release 并上传 APK
        curl -s -X POST "https://gitee.com/api/v5/repos/bingostudio/RadioApp/releases" \
            -H "Authorization: token $GITEE_TOKEN" \
            -F "access_token=$GITEE_TOKEN" \
            -F "tag_name=$RELEASE_TAG" \
            -F "name=Debug Build $(date +%Y%m%d-%H%M)" \
            -F "body=Auto-generated debug APK from Gitee Go pipeline" \
            -F "prerelease=true" \
            -F "attach_files=@$APK_PATH" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    if 'id' in d:
        print('Release created: #' + str(d['id']))
        for f in d.get('assets', []):
            print('Asset: ' + f.get('name','') + ' -> ' + f.get('browser_download_url',''))
    else:
        print('Response: ' + json.dumps(d, ensure_ascii=False)[:300])
except Exception as e:
    print('Parse error: ' + str(e))
"
        echo "=== Upload complete ==="
    else
        echo "WARNING: APK file not found at $APK_PATH"
    fi
else
    echo "Build failed, skipping APK upload"
fi
