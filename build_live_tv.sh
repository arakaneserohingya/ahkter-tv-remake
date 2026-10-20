#!/bin/sh
# Builds the Live TV app (Flutter) for 32-bit ARM TVs and installs it.
# Most TVs are armeabi-v7a; for a 64-bit TV use --target-platform android-arm64.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
(cd apps/live-tv && flutter build apk --release --target-platform "${TARGET:-android-arm}" --android-skip-build-dependency-validation)
APK=apps/live-tv/build/app/outputs/flutter-apk/app-release.apk
tools/tv push "$APK" /data/local/tmp/livetv.apk
tools/tv shell "pm install -r /data/local/tmp/livetv.apk"
tools/tv shell "rm /data/local/tmp/livetv.apk"
echo "Live TV installed."
