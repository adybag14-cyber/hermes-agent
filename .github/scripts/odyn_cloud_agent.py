#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

IGNORE_DIRS = {".git", "node_modules", "venv", ".venv", "__pycache__", "build", "dist", ".gradle", ".idea"}
IGNORE_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".tar", ".gz", ".jar", ".apk", ".aab", ".so", ".bin", ".lock"}

def build_snapshot(root: Path) -> str:
    tree, sections, chars = [], [], 0
    for cur, dirs, files in os.walk(root):
        dirs[:] = sorted([d for d in dirs if d not in IGNORE_DIRS])
        for f in sorted(files):
            p = Path(cur) / f
            rel = str(p.relative_to(root))
            tree.append(rel)
            if p.suffix.lower() in IGNORE_EXT:
                continue
            try:
                st = p.stat()
                if 0 < st.st_size <= 40960:
                    txt = p.read_text(encoding="utf-8", errors="replace")
                    if "\x00" not in txt and chars + len(txt) <= 180000:
                        sections.append(f"--- FILE: {rel} ---\n{txt}\n")
                        chars += len(txt)
            except OSError:
                pass
    return "TREE:\n" + "\n".join(tree) + "\n\nFILES:\n" + "\n".join(sections)

def main() -> int:
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    task = os.environ.get("AGENT_TASK", "").strip()
    if not api_key or not task:
        return 1
    root = Path.cwd()
    snapshot = build_snapshot(root)
    sys_inst = (
        "Jesteś autonomicznym inżynierem oprogramowania operującym bezpośrednio na repozytorium Git. "
        "Zwróć wyłącznie poprawny JSON: {\"commit_message\": \"string\", \"summary\": \"string\", "
        "\"mutations\": [{\"path\": \"rel/path\", \"action\": \"upsert\" | \"delete\", \"content\": \"full content\"}]}."
    )
    payload = {
        "systemInstruction": {"parts": [{"text": sys_inst}]},
        "contents": [{"role": "user", "parts": [{"text": f"ZADANIE:\n{task}\n\nREPO:\n{snapshot}"}]}],
        "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json"}
    }
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent?key={api_key}"
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as resp:
        raw = json.loads(resp.read().decode("utf-8"))
    plan = json.loads(raw["candidates"][0]["content"]["parts"][0]["text"])
    modified = []
    for m in plan.get("mutations", []):
        target = (root / m["path"]).resolve()
        if not str(target).startswith(str(root.resolve())):
            continue
        if m["action"] == "delete" and target.exists():
            target.unlink()
            modified.append(f"USUNIĘTO: {m['path']}")
        elif m["action"] == "upsert" and m.get("content") is not None:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(m["content"], encoding="utf-8")
            modified.append(f"ZAKTUALIZOWANO: {m['path']}")
    subprocess.run(["git", "config", "user.name", "ODYN-AI Autonomous Agent"], check=True)
    subprocess.run(["git", "config", "user.email", "odyn-ai-bot@users.noreply.github.com"], check=True)
    subprocess.run(["git", "add", "-A"], check=True)
    st = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True, check=True)
    pushed = False
    if st.stdout.strip():
        subprocess.run(["git", "commit", "-m", plan.get("commit_message", "chore: AI update")], check=True)
        subprocess.run(["git", "push"], check=True)
        pushed = True
    report = f"## Raport ODYN-AI Agent\n**Status:** `{'SUKCES' if pushed else 'BRAK ZMIAN'}`\n\n{plan.get('summary', '')}\n"
    Path("AGENT_EXECUTION_REPORT.md").write_text(report, encoding="utf-8")
    return 0

if __name__ == "__main__":
    sys.exit(main())
