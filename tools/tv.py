#!/usr/bin/env python3
"""Tiny ADB client for the TV, built on adb-shell (works when macOS blocks the adb binary).

Usage:
  tv.py shell "<command>"          run a shell command, print output
  tv.py screenshot out.png         grab the screen
  tv.py push local remote          push a file (base64 chunks, md5 verified)
  tv.py pull remote local          pull a file (base64)
  tv.py key KEY [KEY ...]          inject key events (DPAD_DOWN, HOME, BACK, ENTER ...)
TV address: TV_IP env var, else the .tv-ip file in the repo root (tools/find-tv.sh writes it).
"""
import base64, hashlib, os, sys, time
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def tv_ip():
    ip = os.environ.get("TV_IP", "").strip()
    if not ip and os.path.exists(os.path.join(ROOT, ".tv-ip")):
        ip = open(os.path.join(ROOT, ".tv-ip")).read().strip()
    if not ip:
        sys.exit("No TV address. Run tools/find-tv.sh, or set TV_IP=192.168.x.y")
    return ip

KEY = os.path.expanduser("~/.android/adbkey_py")


def connect():
    if not os.path.exists(KEY):
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(tv_ip(), 5555, default_transport_timeout_s=180.0)
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=60.0)
    return dev


def sh(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout, decode=True)


def push(dev, local, remote):
    data = open(local, "rb").read()
    b64 = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    sh(dev, f"rm -f {tmp} {remote}")
    step = 60000
    for i in range(0, len(b64), step):
        sh(dev, f"echo -n '{b64[i:i+step]}' >> {tmp}")
    sh(dev, f"base64 -d {tmp} > {remote}; rm -f {tmp}")
    want = hashlib.md5(data).hexdigest()
    got = sh(dev, f"md5sum {remote}").split()[0]
    if want != got:
        sys.exit(f"md5 mismatch: {want} != {got}")
    print(f"pushed {len(data)} bytes, md5 ok")


def pull(dev, remote, local):
    b64 = sh(dev, f"base64 {remote}", timeout=300)
    with open(local, "wb") as f:
        f.write(base64.b64decode("".join(b64.split())))


def main():
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    dev = connect()
    cmd = a[0]
    if cmd == "shell":
        print(sh(dev, a[1]), end="")
    elif cmd == "screenshot":
        sh(dev, "screencap -p /data/local/tmp/s.png")
        pull(dev, "/data/local/tmp/s.png", a[1])
        print(a[1])
    elif cmd == "push":
        push(dev, a[1], a[2])
    elif cmd == "pull":
        pull(dev, a[1], a[2])
    elif cmd == "key":
        for k in a[1:]:
            if k.startswith("sleep"):
                time.sleep(float(k[5:] or 1))
                continue
            sh(dev, f"input keyevent KEYCODE_{k}" if not k.isdigit() else f"input keyevent {k}")
            time.sleep(0.35)
    dev.close()


if __name__ == "__main__":
    main()
