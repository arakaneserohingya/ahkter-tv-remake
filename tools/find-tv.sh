#!/bin/sh
# Finds Android TVs with network ADB (port 5555) on the local /24 network and saves the address to .tv-ip.
cd "$(dirname "$0")/.."
IFACE_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$IFACE_IP" ] && { echo "Could not detect this computer's IP. Set TV_IP manually."; exit 1; }
NET=${IFACE_IP%.*}
echo "Scanning $NET.1-254 for port 5555..."
FOUND=""
for i in $(seq 1 254); do
  ( nc -z -G 1 "$NET.$i" 5555 2>/dev/null || nc -z -w 1 "$NET.$i" 5555 2>/dev/null ) && echo "$NET.$i" &
done > .tv-scan 2>/dev/null
wait
FOUND=$(sort -u .tv-scan | head -1); rm -f .tv-scan
if [ -z "$FOUND" ]; then
  echo "No TV found. Is network/USB debugging on and the TV awake? You can also set TV_IP=<address>."
  exit 1
fi
echo "$FOUND" > .tv-ip
echo "Found TV at $FOUND (saved to .tv-ip). The TV may now ask to allow USB debugging: accept it."
