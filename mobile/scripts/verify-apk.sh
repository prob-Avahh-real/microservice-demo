#!/usr/bin/env bash
#
# 校验 APK 产物是不是个**真的、内容正确的** Android 包，
# 而不是「文件存在就算成功」。
#
#   bash mobile/scripts/verify-apk.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="$ROOT/mobile/android"
SDK_DIR="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"

APK="$(ls -1 "$ANDROID_DIR/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -n 1)"
[[ -n "$APK" ]] || { echo "✘ 没找到 APK，先跑 bash mobile/scripts/build-apk.sh" >&2; exit 1; }

failed=0
check() { # $1=描述 $2=条件结果(0/1) $3=细节
  if (( $2 == 0 )); then
    printf '  \033[32m✔\033[0m %s\n' "$1"
  else
    printf '  \033[31m✘\033[0m %s\n     %s\n' "$1" "${3:-}"
    failed=$((failed + 1))
  fi
}

echo "APK: $APK"
echo "大小: $(ls -lh "$APK" | awk '{print $5}')"
echo "sha256: $(shasum -a 256 "$APK" | awk '{print $1}')"
echo

# ---- 1. 用 aapt2/aapt 读清单（真正解析 APK，不是看文件名）----
AAPT="$(ls -1 "$SDK_DIR"/build-tools/*/aapt2 2>/dev/null | head -n 1)"
[[ -z "$AAPT" ]] && AAPT="$(ls -1 "$SDK_DIR"/build-tools/*/aapt 2>/dev/null | head -n 1)"

if [[ -n "$AAPT" ]]; then
  BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null)"
  PKG="$(printf '%s' "$BADGING" | grep -o "package: name='[^']*'" | head -1)"
  VER="$(printf '%s' "$BADGING" | grep -o "versionName='[^']*'" | head -1)"
  SDK="$(printf '%s' "$BADGING" | grep -o "sdkVersion:'[^']*'" | head -1)"
  TSDK="$(printf '%s' "$BADGING" | grep -o "targetSdkVersion:'[^']*'" | head -1)"
  echo "  $PKG  $VER  $SDK  $TSDK"
  check "包名是 com.demo.microservice" \
    "$([[ "$PKG" == *"com.demo.microservice"* ]] && echo 0 || echo 1)" "$PKG"
  check "清单可解析（aapt dump badging 成功）" \
    "$([[ -n "$PKG" ]] && echo 0 || echo 1)" "aapt 输出为空"
else
  check "找到 aapt2/aapt（用于解析 APK 清单）" 1 "没在 $SDK_DIR/build-tools 下找到"
fi

# ---- 2. 打进包里的 web 资源必须齐（否则装上也只会白屏）----
ENTRIES="$(unzip -Z1 "$APK" 2>/dev/null)"
for asset in assets/public/index.html assets/public/api.js; do
  check "包含 $asset" \
    "$(printf '%s' "$ENTRIES" | grep -qx "$asset" && echo 0 || echo 1)" "APK 内缺该文件"
done

# ---- 3. 入口 HTML 里确实引用了 API 模块与网关地址（不是空壳页）----
if printf '%s' "$ENTRIES" | grep -qx 'assets/public/index.html'; then
  HTML="$(unzip -p "$APK" assets/public/index.html 2>/dev/null)"
  check "index.html 引用了 ./api.js 模块" \
    "$(printf '%s' "$HTML" | grep -q "from './api.js'" && echo 0 || echo 1)" "没找到 import"
  check "index.html 含模拟器网关地址 10.0.2.2" \
    "$(printf '%s' "$HTML" | grep -q "10.0.2.2" && echo 0 || echo 1)" "没找到默认网关地址"
fi

echo
if (( failed == 0 )); then
  echo "✔ APK 校验通过"
  exit 0
fi
echo "✘ APK 校验发现 ${failed} 项问题"
exit 1
