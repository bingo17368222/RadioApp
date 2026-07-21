#!/bin/bash
# 下载指定版本的 RadioApp APK (使用 ghfast.top 加速镜像)
# 用法: ./download-apk.sh [版本号] [输出目录]
# 示例: ./download-apk.sh 2.4.128 /workspace
#       ./download-apk.sh          # 自动检测当前版本

REPO="bingo17368222/RadioApp"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 获取版本号：从参数或 build.gradle 自动检测
VERSION="${1}"
if [ -z "$VERSION" ]; then
    VERSION=$(grep 'versionName' "$SCRIPT_DIR/app/build.gradle" | head -1 | sed 's/.*versionName "\([^"]*\)".*/\1/')
    echo "从build.gradle检测到版本: $VERSION"
fi

OUTPUT_DIR="${2:-/workspace}"
APK_NAME="RadioApp-v${VERSION}.apk"
APK_URL="https://github.com/${REPO}/releases/download/v${VERSION}/${APK_NAME}"
OUTPUT="${OUTPUT_DIR}/${APK_NAME}"

echo "正在下载 RadioApp v${VERSION}..."
echo "URL: $APK_URL"
echo "输出: $OUTPUT"
echo ""

# 优先使用 ghfast.top 加速镜像 (测试有效，速度约5MB/s)
echo "正在从 ghfast.top 加速镜像下载..."
HTTP_CODE=$(curl -L --max-time 300 -o "$OUTPUT" -w "%{http_code}" \
  "https://ghfast.top/$APK_URL" \
  -w "\n下载完成 HTTP %{http_code} 大小 %{size_download} bytes\n" 2>&1)

if [ -f "$OUTPUT" ] && [ -s "$OUTPUT" ]; then
    FILE_SIZE=$(stat -c%s "$OUTPUT")
    if [ "$FILE_SIZE" -gt 1000000 ]; then
        echo "成功: $(ls -lh "$OUTPUT" | awk '{print $5}') ($FILE_SIZE bytes)"
        echo "APK已保存到: $OUTPUT"
        exit 0
    fi
fi

# 备用镜像: github.moeyy.xyz
echo "ghfast.top加速镜像失败，尝试备用镜像 github.moeyy.xyz..."
curl -L --max-time 300 -o "$OUTPUT" \
  "https://github.moeyy.xyz/$APK_URL" \
  -w "\n下载完成 HTTP %{http_code} 大小 %{size_download} bytes\n" 2>&1

if [ -f "$OUTPUT" ] && [ -s "$OUTPUT" ]; then
    FILE_SIZE=$(stat -c%s "$OUTPUT")
    if [ "$FILE_SIZE" -gt 1000000 ]; then
        echo "成功(备用镜像): $(ls -lh "$OUTPUT" | awk '{print $5}') ($FILE_SIZE bytes)"
        echo "APK已保存到: $OUTPUT"
        exit 0
    fi
fi

echo "错误: 所有下载方式均失败"
exit 1
