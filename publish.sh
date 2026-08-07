#!/usr/bin/env bash
# 一键发布 Synaps APK 到 abuaibobo-dev/aibox-updates Releases，并输出直链
# 用法: ./publish.sh <版本号如 v2.3.0> <apk 路径> [发布说明]
set -euo pipefail
TAG="${1:?用法: ./publish.sh <版本号> <apk路径> [发布说明]}"
APK="${2:?缺少 APK 路径}"
NOTES="${3:-Synaps 更新}"
REPO="abuaibobo-dev/aibox-updates"
[ -f "$APK" ] || { echo "APK 不存在: $APK"; exit 1; }
gh release create "$TAG" "$APK" --repo "$REPO" --title "Synaps $TAG" --notes "$NOTES"
NAME="$(basename "$APK")"
echo
echo "直链: https://github.com/$REPO/releases/download/$TAG/$NAME"
