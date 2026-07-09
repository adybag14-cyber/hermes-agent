#!/usr/bin/env python3
import re
import sys
from pathlib import Path

xml = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
needle = sys.argv[2] if len(sys.argv) > 2 else ""
for match in re.finditer(r'text="([^"]*)"[^>]*clickable="([^"]*)"[^>]*bounds="([^"]*)"', xml):
    text, clickable, bounds = match.groups()
    if needle.lower() in text.lower() or not needle:
        print(f"{text!r} clickable={clickable} bounds={bounds}")