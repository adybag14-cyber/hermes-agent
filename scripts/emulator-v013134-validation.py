#!/usr/bin/env python3
"""Emulator validation for Hermes v0.13.134 with mirror checks and sandbox UI flow."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
HARNESS = REPO_ROOT / "scripts" / "android_visual_harness.py"
DEFAULT_OUT = REPO_ROOT / "verification-screenshots" / "v0.13.134"
DEFAULT_SERIAL = "emulator-5554"


def run_harness(serial: str, *args: str) -> None:
    command = [sys.executable, str(HARNESS), "--serial", serial, *args]
    result = subprocess.run(command, check=False, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"Harness failed ({result.returncode}): {' '.join(command)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--skip-chat", action="store_true", help="Only mirror + Device UI checks.")
    args = parser.parse_args()

    out_dir = args.out_dir.expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    serial = args.serial
    summary: dict = {
        "version": "0.13.134",
        "versionCode": 143490,
        "emulator": serial,
        "steps": [],
    }

    mirror_report = out_dir / "mirror-check.json"
    mirror_env = out_dir / "termux-mirrors.env"
    print("Checking Termux package mirrors...")
    mirror_result = subprocess.run(
        [
            sys.executable,
            str(HARNESS),
            "--serial",
            serial,
            "check-termux-mirrors",
            "--out",
            str(mirror_report),
            "--write-env",
            str(mirror_env),
        ],
        check=False,
        text=True,
    )
    summary["steps"].append(
        {
            "name": "termux_mirror_check",
            "ok": mirror_result.returncode == 0,
            "report": str(mirror_report),
            "env": str(mirror_env),
        }
    )
    if mirror_report.is_file():
        summary["mirror_check"] = json.loads(mirror_report.read_text(encoding="utf-8"))

    print("Launching Hermes...")
    run_harness(serial, "launch")
    time.sleep(12)
    run_harness(serial, "screenshot", "--out", str(out_dir / "01-chat-home.png"))

    print("Ensuring chat home and opening navigation drawer...")
    run_harness(serial, "ensure-chat")
    time.sleep(2)
    run_harness(serial, "open-drawer")
    run_harness(serial, "screenshot", "--out", str(out_dir / "02-navigation-menu.png"))

    print("Opening Device screen...")
    run_harness(serial, "nav-section", "Device")
    time.sleep(4)
    run_harness(serial, "screenshot", "--out", str(out_dir / "03-device-top.png"))

    print("Scrolling to Linux sandbox card...")
    sandbox_xml = out_dir / "04-device-linux-sandbox-card.xml"
    run_harness(
        serial,
        "scroll-until-text",
        "Linux sandbox",
        "Deploy Alpine",
        "--out",
        str(sandbox_xml),
        "--max-attempts",
        "18",
    )
    run_harness(serial, "screenshot", "--out", str(out_dir / "04-device-linux-sandbox-card.png"))
    summary["steps"].append({"name": "linux_sandbox_card", "ok": True, "xml": str(sandbox_xml)})

    print("Deploying Alpine sandbox...")
    run_harness(serial, "tap-text", "Deploy Alpine")
    time.sleep(10)
    run_harness(serial, "screenshot", "--out", str(out_dir / "05-alpine-deploy-started.png"))

    print("Switching sandbox mirrors from Device UI...")
    run_harness(
        serial,
        "scroll-until-text",
        "China mirrors",
        "--max-attempts",
        "6",
        "--pause-s",
        "0.8",
    )
    run_harness(serial, "tap-text", "China mirrors")
    time.sleep(8)
    run_harness(serial, "screenshot", "--out", str(out_dir / "05b-china-mirrors-tapped.png"))
    summary["steps"].append({"name": "china_mirrors_button", "ok": True})

    if args.skip_chat:
        summary_path = out_dir / "validation-summary.json"
        summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(summary_path)
        return 0

    print("Returning to chat...")
    run_harness(serial, "nav-section", "Hermes Fork")
    time.sleep(2)
    run_harness(serial, "screenshot", "--out", str(out_dir / "06-chat-ready.png"))

    print("Sending Alpine sandbox tool prompt...")
    run_harness(serial, "tap-text", "Message Hermes Fork")
    time.sleep(1)
    run_harness(
        serial,
        "text",
        "Use mcp_run_in_proot to run: uname -a. Then linux_sandbox_tool action=status and report mirror_profiles.",
    )
    run_harness(serial, "keyevent", "66")
    time.sleep(60)
    run_harness(serial, "screenshot", "--out", str(out_dir / "07-chat-alpine-tool-run.png"))

    print("Sending mirror update prompt...")
    run_harness(serial, "tap-text", "Message Hermes Fork")
    run_harness(
        serial,
        "text",
        "Use linux_sandbox_tool action=set_mirror mirror_profile=tsinghua for the active alpine sandbox.",
    )
    run_harness(serial, "keyevent", "66")
    time.sleep(45)
    run_harness(serial, "screenshot", "--out", str(out_dir / "07b-chat-mirror-update.png"))

    print("Sending memory tool alias prompt...")
    run_harness(serial, "tap-text", "Message Hermes Fork")
    run_harness(
        serial,
        "text",
        "Use memory_add content='violet-714 alpine validation sentinel' then memory_search query=violet-714.",
    )
    run_harness(serial, "keyevent", "66")
    time.sleep(60)
    run_harness(serial, "screenshot", "--out", str(out_dir / "08-chat-memory-tool-display.png"))

    summary["steps"].extend(
        [
            {"name": "chat_alpine_tools", "ok": True},
            {"name": "chat_mirror_update", "ok": True},
            {"name": "chat_memory_aliases", "ok": True},
        ]
    )
    summary_path = out_dir / "validation-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Validation screenshots written to {out_dir}")
    print(summary_path)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        sys.stderr.write(f"{error}\n")
        raise SystemExit(1)