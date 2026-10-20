#!/bin/sh
# Undo: re-enables every package in the profile and gives Home back to the Google TV launcher.
# Usage: ./restore.sh profiles/<your-tv>.txt [--uninstall]   (--uninstall also removes the custom launcher)
set -e
cd "$(dirname "$0")"
PROFILE="${1:?Usage: ./restore.sh profiles/<your-tv>.txt [--uninstall]}"
for p in $(grep -v '^#' "$PROFILE"); do
  printf '%s: ' "$p"
  tools/tv shell "pm enable $p"   # one pm command per shell call
done
tools/tv shell "cmd package set-home-activity com.google.android.tvlauncher/.MainActivity" || true
if [ "$2" = "--uninstall" ]; then
  tools/tv shell "pm uninstall com.kulwant.atvhome"
fi
tools/tv shell "input keyevent KEYCODE_HOME"
echo "Restored."
