#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

xml = Path(sys.argv[1]).read_text(encoding="utf-8")
label = sys.argv[2].strip()
root = ET.fromstring(xml)
parent = {}
for ancestor in root.iter():
    for child in ancestor:
        if child.tag == "node":
            parent[child] = ancestor

for node in root.iter("node"):
    if node.attrib.get("text", "").strip() != label:
        continue
    current = node
    for depth in range(12):
        if current is None:
            break
        print(
            depth,
            current.attrib.get("class"),
            "clickable=" + current.attrib.get("clickable", ""),
            "focusable=" + current.attrib.get("focusable", ""),
            current.attrib.get("bounds", ""),
        )
        current = parent.get(current)