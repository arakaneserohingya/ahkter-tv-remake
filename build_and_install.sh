#!/bin/sh
# Builds the launcher and Live TV apps, and installs them on the TV together with the apps whose APKs
# are in apps/ (AirPlay, SmartTube, MovieBox). Edit launcher/app/src/main/res/values/config.xml first.
# With several devices connected, pick one: DEVICE_ID=192.168.0.146:5555 ./build_and_install.sh
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
    SERIALS=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [ "$(echo "$SERIALS" | grep -c .)" -gt 1 ]; then
        # e.g. the TV and an emulator: never guess which one gets the launcher
        echo "More than one device is connected:"
        echo "$SERIALS"
        echo "Pick one, e.g.: DEVICE_ID=$(echo "$SERIALS" | head -1) ./build_and_install.sh"
        exit 1
    fi
    if [ -n "$SERIALS" ]; then
        DEVICE="-s $SERIALS"
    fi
fi

if [ -n "$DEVICE" ]; then
    echo "Installing on $DEVICE via ADB..."
    tv_shell() { "$ADB" $DEVICE shell "$@"; }
    tv_install() { "$ADB" $DEVICE install -r -t "$1"; }
else
    # Fallback to tools/tv (network ADB client)
    echo "Installing via tools/tv..."
    tv_shell() { tools/tv shell "$@"; }
    tv_install() {
        tools/tv push "$1" /data/local/tmp/app.apk
        tools/tv shell "pm install -r -t /data/local/tmp/app.apk"
        tools/tv shell "rm /data/local/tmp/app.apk"
    }
fi

on_tv() { tv_shell "pm path $1" 2>/dev/null | grep -q '^package:'; }

# 4. The apps that come with the launcher, installed when their APK is in apps/. A new TV gets all of
# them; later runs (updates) only update the ones still there, so an app the user removed stays removed.
if on_tv com.kulwant.atvhome; then FIRST_INSTALL=0; else FIRST_INSTALL=1; fi

install_app() {   # name package apk
    if [ ! -f "$3" ]; then
        echo "Skipping $1: $3 not found"
    elif [ "$FIRST_INSTALL" = 0 ] && ! on_tv "$2"; then
        echo "Skipping $1: not on this TV, and only a new TV gets it"
    else
        echo "Installing $1..."
        tv_install "$3" || echo "Couldn't install $1, carrying on"
    fi
}

echo "Installing Launcher..."
if [ -n "$DEVICE" ]; then
    # the launcher targets an old SDK on purpose (see launcher/app/build.gradle): without this flag
    # newer Android blocks it, and Play Protect holds the install on an "Unsafe app blocked" dialog
    "$ADB" $DEVICE install -r -t --bypass-low-target-sdk-block "$LAUNCHER_APK"
else
    tv_install "$LAUNCHER_APK"
fi
install_app "Live TV" com.nk.live_tv "$LIVETV_APK"
install_app "AirPlay Receiver" io.github.jqssun.airplay apps/airplay/airplay-release.apk
install_app "SmartTube" org.smarttube.stable apps/smarttube/smarttube.apk
install_app "MovieBox" com.community.mbox.tv apps/moviebox/moviebox.apk

if on_tv io.github.jqssun.airplay; then
    tv_shell "am broadcast -n io.github.jqssun.airplay/.SettingsReceiver -a io.github.jqssun.airplay.SET_SETTING --es key 'server_name' --es value 'My TV Airplay'" >/dev/null 2>&1 || true
fi

tv_shell "pm grant com.kulwant.atvhome android.permission.ACCESS_COARSE_LOCATION" 2>/dev/null || true
tv_shell "pm grant com.kulwant.atvhome android.permission.ACCESS_FINE_LOCATION" 2>/dev/null || true
tv_shell "pm grant com.kulwant.atvhome android.permission.POST_NOTIFICATIONS" 2>/dev/null || true
# file sharing on the phone page: the Download folder (All files access; the storage permission on
# Android 9-10), and opening APKs sent to the TV in its installer
tv_shell "appops set com.kulwant.atvhome MANAGE_EXTERNAL_STORAGE allow" 2>/dev/null || true
tv_shell "pm grant com.kulwant.atvhome android.permission.WRITE_EXTERNAL_STORAGE" 2>/dev/null || true
tv_shell "appops set com.kulwant.atvhome REQUEST_INSTALL_PACKAGES allow" 2>/dev/null || true
tv_shell "appops set com.kulwant.atvhome SYSTEM_ALERT_WINDOW allow" 2>/dev/null || true
tv_shell "cmd package set-home-activity com.kulwant.atvhome/.HomeActivity"
tv_shell "settings put secure enabled_accessibility_services com.kulwant.atvhome/.HomeInterceptService"
tv_shell "settings put secure accessibility_enabled 1"
tv_shell "am start -n com.kulwant.atvhome/.HomeActivity"

echo "Done."
