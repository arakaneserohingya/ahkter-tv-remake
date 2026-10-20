#!/bin/sh
# Disables (never uninstalls) every package listed in a profile. Usage: ./disable.sh profiles/<your-tv>.txt
# Review the profile first: lines starting with # are comments. Undo with ./restore.sh <same profile>.
set -e
cd "$(dirname "$0")"
PROFILE="${1:?Usage: ./disable.sh profiles/<your-tv>.txt}"
for p in $(grep -v '^#' "$PROFILE"); do
  printf '%s: ' "$p"
  tools/tv shell "pm disable-user --user 0 $p"   # one pm command per shell call
done
