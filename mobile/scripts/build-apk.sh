#!/usr/bin/env bash
#
# 构建 Android debug APK。零手工步骤：JDK、SDK 路径、代理全部在这里定好，
# 换一台机器只需要改这三个变量（AGENTS.md「环境可重复」）。
#
#   bash mobile/scripts/build-apk.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"        # 工程根目录
ANDROID_DIR="$ROOT/mobile/android"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31m!!\033[0m %s\n' "$*" >&2; exit 1; }

# ---- 1. JDK：AGP 8.2.1 不支持 JDK 24/27，必须钉在 21 ----
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
[[ -x "$JAVA_HOME/bin/java" ]] || die "找不到 JDK 21：$JAVA_HOME"
export JAVA_HOME

# ---- 2. Android SDK：本机在 Homebrew 的 android-commandlinetools，不是 ~/Library/Android/sdk ----
SDK_DIR="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
[[ -d "$SDK_DIR/platforms" ]] || die "找不到 Android SDK：${SDK_DIR}（需要 platforms/ 与 build-tools/）"
print_platforms=$(ls "$SDK_DIR/platforms" | tr '\n' ' ')
print_tools=$(ls "$SDK_DIR/build-tools" | tr '\n' ' ')
log "SDK: $SDK_DIR  (platforms: $print_platforms | build-tools: $print_tools)"
# local.properties 指向真实 SDK；它不进版本控制（见 .gitignore）
echo "sdk.dir=$SDK_DIR" > "$ANDROID_DIR/local.properties"

# ---- 3. 依赖仓库：走国内镜像，不需要代理 ----
# Google Maven 直连被墙，但 Gradle 走 Clash 代理会 TLS 握手失败（"Remote host terminated
# the handshake"），而同一代理用 curl/JDK HttpClient 都是通的 —— 是 Gradle 自己的 HTTP
# 客户端的问题。解法不是继续折腾代理，而是改用直连可达的国内镜像（见 android/build.gradle）。
log "依赖仓库：国内镜像优先（腾讯/阿里），官方源兜底；不使用代理"

# ---- 4. 同步 web 资源（www → android 的 assets）----
log "同步 web 资源"
( cd "$ROOT/mobile" && npx cap sync android >/dev/null ) || die "cap sync 失败"

# ---- 4.5 给 Capacitor 自带子工程插入国内镜像 ----
# cap sync 会重新生成 capacitor-cordova-android-plugins/build.gradle，
# 所以每次构建前都必须重打补丁（脚本幂等）。否则 Gradle 会去直连 dl.google.com 超时失败。
log "给 Capacitor 子工程插入镜像仓库"
( cd "$ROOT/mobile" && node scripts/patch-capacitor-repos.cjs ) || die "镜像补丁失败"

# ---- 5. 构建 ----
log "开始 gradle assembleDebug（首次会下载 Gradle 发行包与 AGP 依赖，较慢）"
( cd "$ANDROID_DIR" && ./gradlew --no-daemon assembleDebug "$@" )
status=$?
if (( status != 0 )); then
  die "gradle assembleDebug 失败（退出码 ${status}）"
fi

APK="$(ls -1 "$ANDROID_DIR/app/build/outputs/apk/debug/"*.apk 2>/dev/null | head -n 1)"
[[ -n "$APK" ]] || die "构建成功但没找到 APK 产物"

log "APK 产物：$APK"
ls -lh "$APK" | awk '{print "     大小: " $5}'
