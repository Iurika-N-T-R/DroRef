#!/usr/bin/env python3
"""Tiny emulator driver for manual-style UI checks.
  ui.py tap "Text"        tap the element whose text/description is Text
  ui.py drag "Text" DX DY swipe starting on an element
  ui.py tapxy X Y         tap a point
  ui.py swipe X1 Y1 X2 Y2 [ms]
  ui.py shot NAME         screenshot into test-pur/NAME.png
  ui.py texts             list visible texts"""
import os, re, subprocess, sys, time

ADB = "/mnt/hdd/DevTools/Android/Sdk/platform-tools/adb"
SHOTS = "/mnt/hdd/Projects/DroRef/test-pur"


def adb(*a, out=False):
    r = subprocess.run([ADB, *a], capture_output=True)
    return r.stdout if out else None


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", out=True).decode("utf-8", "replace")
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, d, x1, y1, x2, y2 = m.groups()
        yield (t or d), (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "tap":
        want = args[0]
        for t, x, y in nodes():
            if t == want or t.replace(" ", " ") == want:
                adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.8); return
        for t, x, y in nodes():
            if want.lower() in t.lower():
                adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.8); return
        sys.exit(f"not found: {want}")
    elif cmd == "drag":  # drag TEXT DX DY: swipe starting on an element (e.g. to scroll a toolbar)
        for t, x, y in nodes():
            if t == args[0]:
                adb("shell", "input", "swipe", str(x), str(y), str(x + int(args[1])), str(y + int(args[2])), "400"); time.sleep(0.6); return
        sys.exit(f"not found: {args[0]}")
    elif cmd == "tapxy":
        adb("shell", "input", "tap", *args); time.sleep(0.6)
    elif cmd == "swipe":
        adb("shell", "input", "swipe", *args); time.sleep(0.6)
    elif cmd == "shot":
        open(os.path.join(SHOTS, args[0] + ".png"), "wb").write(adb("exec-out", "screencap", "-p", out=True))
    elif cmd == "texts":
        print([t for t, _, _ in nodes() if t])


main()
