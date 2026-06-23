#!/bin/bash
# 下载最新 RadioApp APK (使用加速镜像)
# 用法: ./download-apk.sh

APK_URL="https://github.com/bingo17368222/RadioApp/releases/download/debug-latest/app-debug.apk"
OUTPUT="RadioApp-debug.apk"

# 优先使用 ghfast.top 加速
echo "正在从加速镜像下载..."
curl -L --max-time 300 -o "$OUTPUT" \
  "https://ghfast.top/$APK_URL" \
  -w "\n下载完成 HTTP %{http_code} 大小 %{size_download} bytes\n" 2>&1

if [ -f "$OUTPUT" ] && [ -s "$OUTPUT" ]; then
    echo "成功: $(ls -lh "$OUTPUT" | awk '{print $5}')"
else
    echo "加速镜像失败，尝试备用镜像..."
    curl -L --max-time 300 -o "$OUTPUT" \
      "https://github.moeyy.xyz/$APK_URL" \
      -w "\n下载完成 HTTP %{http_code} 大小 %{size_download} bytes\n" 2>&1
fi