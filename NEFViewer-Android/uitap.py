#!/usr/bin/env python3
"""uiautomator dump → 按 text/content-desc 找节点中心坐标。用法:
   uitap.py <文本或desc> [点击序号]   —— 打印 "x y" 坐标
   优先精确匹配 text 或 desc，其次包含匹配。
"""
import re
import subprocess
import sys

ADB = "/Users/bbpcs/Library/Android/sdk/platform-tools/adb"
DEV = "HA2Q0SW1"  # USB 调试；无线调试时为 192.168.50.135:5555

def dump():
    subprocess.run([ADB, "-s", DEV, "shell", "uiautomator", "dump", "/sdcard/__ui.xml"],
                   check=True, capture_output=True)
    out = subprocess.run([ADB, "-s", DEV, "shell", "cat", "/sdcard/__ui.xml"],
                         check=True, capture_output=True)
    return out.stdout.decode("utf-8", "ignore")

def find(xml, needle):
    exact, partial = [], []
    for m in re.finditer(r'<node[^>]*>', xml):
        node = m.group(0)
        text = re.search(r'text="([^"]*)"', node)
        desc = re.search(r'content-desc="([^"]*)"', node)
        t = text.group(1) if text else ""
        d = desc.group(1) if desc else ""
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        entry = ((x1 + x2) // 2, (y1 + y2) // 2, t + "|" + d)
        if needle == t or needle == d:
            exact.append(entry)
        elif needle in t or needle in d:
            partial.append(entry)
    return exact or partial

if __name__ == "__main__":
    needle = sys.argv[1]
    idx = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    hits = find(dump(), needle)
    if not hits:
        print(f"NOTFOUND: {needle}", file=sys.stderr)
        sys.exit(1)
    x, y, label = hits[idx][:3]
    print(f"{x} {y}")
    print(f"# {label}", file=sys.stderr)
