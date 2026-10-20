#!/bin/sh
# Builds the launcher and Live TV apps, and installs both on the TV at the same time.
# Edit launcher/app/src/main/res/values/config.xml first.
set -e
cd "$(dirname "$0")"

# Set JDK path
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    elif [ -d "/opt/homebrew/opt/openjdk@17" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
    fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

# 1. Build Live TV (Flutter) first, so it can also be bundled in launcher assets
LIVETV_APK=apps/live-tv/build/app/outputs/flutter-apk/app-release.apk
if [ ! -f "$LIVETV_APK" ] || [ "${REBUILD_LIVETV:-0}" = "1" ]; then
    if command -v flutter >/dev/null 2>&1; then
        echo "Building Live TV (Flutter)..."
        (cd apps/live-tv && flutter build apk --release --target-platform "${TARGET:-android-arm,android-arm64}" --android-skip-build-dependency-validation)
    else
        echo "Warning: flutter not found in PATH; skipping Live TV build (existing APK will be used if present)."
    fi
fi

# 2. Build Launcher (which automatically bundles Live TV into its assets)
echo "Building Launcher..."
(cd launcher && ./gradlew -q clean assembleDebug --no-watch-fs)
LAUNCHER_APK=launcher/app/build/outputs/apk/debug/app-debug.apk

# 3. Detect ADB Device (Emulator, USB, or Wi-Fi connected)
ADB="$ANDROID_HOME/platform-tools/adb"
if [ ! -x "$ADB" ]; then
    ADB=$(command -v adb 2>/dev/null || true)
fi

DEVICE=""
if [ -n "$DEVICE_ID" ]; then
    DEVICE="-s $DEVICE_ID"
elif [ -n "$ADB" ] && [ -x "$ADB" ]; then
    TV_DEVICE=$("$ADB" devices -l | grep -E "atv|tv|Television|emulator" | awk '{print $1}' | head -1)
    if [ -n "$TV_DEVICE" ]; then
        DEVICE="-s $TV_DEVICE"
    else
        CONNECTED=$("$ADB" devices | grep -w "device" | grep -v "List of devices" | head -1 | awk '{print $1}')
        if [ -n "$CONNECTED" ]; then
            DEVICE="-s $CONNECTED"
        fi
    fi
fi

if [ -n "$DEVICE" ]; then
    echo "Installing Launcher on $DEVICE via ADB..."
    "$ADB" $DEVICE install -r -t --bypass-low-target-sdk-block "$LAUNCHER_APK"

    if [ -f "$LIVETV_APK" ]; then
        echo "Installing Live TV on $DEVICE via ADB..."
        "$ADB" $DEVICE install -r -t "$LIVETV_APK"
    fi

    AIRPLAY_APK=apps/airplay/airplay-release.apk
    if [ -f "$AIRPLAY_APK" ]; then
        echo "Installing AirPlay Receiver on $DEVICE via ADB..."
        "$ADB" $DEVICE install -r -t "$AIRPLAY_APK"
        "$ADB" $DEVICE shell "am broadcast -n io.github.jqssun.airplay/.SettingsReceiver -a io.github.jqssun.airplay.SET_SETTING --es key 'server_name' --es value 'My TV Airplay'" 2>/dev/null || true
    fi

    "$ADB" $DEVICE shell "pm grant com.kulwant.atvhome android.permission.ACCESS_COARSE_LOCATION" 2>/dev/null || true
    "$ADB" $DEVICE shell "appops set com.kulwant.atvhome SYSTEM_ALERT_WINDOW allow" 2>/dev/null || true
    "$ADB" $DEVICE shell "cmd package set-home-activity com.kulwant.atvhome/.HomeActivity"
    "$ADB" $DEVICE shell "settings put secure enabled_accessibility_services com.kulwant.atvhome/.HomeInterceptService"
    "$ADB" $DEVICE shell "settings put secure accessibility_enabled 1"
    "$ADB" $DEVICE shell "am start -n com.kulwant.atvhome/.HomeActivity"
else
    # Fallback to tools/tv (network ADB client)
    echo "Installing Launcher via tools/tv..."
    tools/tv push "$LAUNCHER_APK" /data/local/tmp/atvhome.apk
    tools/tv shell "pm install -r -t /data/local/tmp/atvhome.apk"
    tools/tv shell "rm /data/local/tmp/atvhome.apk"

    if [ -f "$LIVETV_APK" ]; then
        echo "Installing Live TV via tools/tv..."
        tools/tv push "$LIVETV_APK" /data/local/tmp/livetv.apk
        tools/tv shell "pm install -r /data/local/tmp/livetv.apk"
        tools/tv shell "rm /data/local/tmp/livetv.apk"
    fi

    AIRPLAY_APK=apps/airplay/airplay-release.apk
    if [ -f "$AIRPLAY_APK" ]; then
        echo "Installing AirPlay Receiver via tools/tv..."
        tools/tv push "$AIRPLAY_APK" /data/local/tmp/airplay.apk
        tools/tv shell "pm install -r /data/local/tmp/airplay.apk"
        tools/tv shell "rm /data/local/tmp/airplay.apk"
        tools/tv shell "am broadcast -n io.github.jqssun.airplay/.SettingsReceiver -a io.github.jqssun.airplay.SET_SETTING --es key 'server_name' --es value 'My TV Airplay'" || true
    fi

    tools/tv shell "pm grant com.kulwant.atvhome android.permission.ACCESS_COARSE_LOCATION" || true
    tools/tv shell "appops set com.kulwant.atvhome SYSTEM_ALERT_WINDOW allow"
    tools/tv shell "cmd package set-home-activity com.kulwant.atvhome/.HomeActivity"
    tools/tv shell "settings put secure enabled_accessibility_services com.kulwant.atvhome/.HomeInterceptService"
    tools/tv shell "settings put secure accessibility_enabled 1"
    tools/tv shell "am start -n com.kulwant.atvhome/.HomeActivity"
fi

echo "Launcher, Live TV, and AirPlay successfully installed."
