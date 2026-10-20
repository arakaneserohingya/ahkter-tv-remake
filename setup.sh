#!/bin/sh
# One-time setup: Python environment for tools/tv (an ADB client that also works when macOS blocks adb).
set -e
cd "$(dirname "$0")"
python3 -m venv .venv
.venv/bin/pip install -q -r requirements.txt
echo "Ready. Next: tools/find-tv.sh"
