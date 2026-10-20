#!/bin/sh
# Saves the TV's state before any change: package lists, disabled packages, props, all settings, home app.
set -e
cd "$(dirname "$0")"
OUT="backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
tools/tv shell "pm list packages -f" > "$OUT/packages_all.txt"
tools/tv shell "pm list packages -d" > "$OUT/packages_disabled.txt"
tools/tv shell "pm list packages -s" > "$OUT/packages_system.txt"
tools/tv shell "pm list packages -3" > "$OUT/packages_thirdparty.txt"
tools/tv shell "getprop" > "$OUT/getprop.txt"
for ns in system secure global; do tools/tv shell "settings list $ns" > "$OUT/settings_$ns.txt"; done
tools/tv shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" > "$OUT/home_activity.txt"
tools/tv screenshot "$OUT/home.png" >/dev/null
echo "Backup saved to $OUT (it contains device details: keep it out of git)."
