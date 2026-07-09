#!/usr/bin/env python3
"""ADB visual harness for Hermes Android emulator validation."""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_PACKAGE = "com.mobilefork.hermesagent"
DEFAULT_READY_TEXT = "Message Hermes Fork|Welcome to Hermes Agent Fork|Settings|Hermes Fork"
CHAT_HOME_READY_TEXT = "Message Hermes Fork|Welcome to Hermes Agent Fork"
UI_DUMP_REMOTE_PATH = "/sdcard/window_dump.xml"


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.is_file():
            return str(candidate)
    if os.name == "nt":
        default_sdk = Path(
            r"C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_android_sdk"
        )
        candidate = default_sdk / "platform-tools" / "adb.exe"
        if candidate.is_file():
            return str(candidate)
    resolved = shutil.which("adb.exe" if os.name == "nt" else "adb")
    if resolved:
        return resolved
    raise FileNotFoundError(
        "adb was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or put adb on PATH."
    )


def adb_args(serial: str | None, *args: str) -> list[str]:
    base = [adb_path()]
    if serial:
        base += ["-s", serial]
    return base + list(args)


def run_adb(serial: str | None, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        adb_args(serial, *args),
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def devices(_: argparse.Namespace) -> int:
    result = subprocess.run([adb_path(), "devices", "-l"], text=True, check=False)
    return result.returncode


def screenshot(args: argparse.Namespace) -> int:
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        adb_args(args.serial, "exec-out", "screencap", "-p"),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode("utf-8", "replace"))
        return proc.returncode
    out.write_bytes(proc.stdout)
    print(out)
    return 0


def write_screenshot(serial: str | None, out: Path) -> int:
    out.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        adb_args(serial, "exec-out", "screencap", "-p"),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode("utf-8", "replace"))
        return proc.returncode
    out.write_bytes(proc.stdout)
    print(out)
    return 0


def tap(args: argparse.Namespace) -> int:
    run_adb(args.serial, "shell", "input", "tap", str(args.x), str(args.y))
    return 0


def swipe(args: argparse.Namespace) -> int:
    run_adb(
        args.serial,
        "shell",
        "input",
        "swipe",
        str(args.x1),
        str(args.y1),
        str(args.x2),
        str(args.y2),
        str(args.duration_ms),
    )
    return 0


def text(args: argparse.Namespace) -> int:
    payload = args.text.replace("%", "%s").replace(" ", "%s")
    run_adb(args.serial, "shell", f"input text {shlex.quote(payload)}")
    return 0


def keyevent(args: argparse.Namespace) -> int:
    run_adb(args.serial, "shell", "input", "keyevent", args.key)
    return 0


def launch(args: argparse.Namespace) -> int:
    result = run_adb(
        args.serial,
        "shell",
        "monkey",
        "-p",
        args.package,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
        check=False,
    )
    if result.stdout:
        sys.stdout.write(result.stdout)
    if result.stderr:
        sys.stderr.write(result.stderr)
    combined = result.stdout + result.stderr
    if "No activities found" in combined:
        return 1
    return result.returncode


def wait_for_focus(serial: str | None, package: str, timeout_ms: int) -> bool:
    deadline = time.monotonic() + (timeout_ms / 1000)
    last_focus = ""
    while time.monotonic() <= deadline:
        result = run_adb(serial, "shell", "dumpsys", "window", check=False)
        combined = result.stdout + result.stderr
        focus_lines = [
            line.strip()
            for line in combined.splitlines()
            if "mCurrentFocus" in line or "mFocusedApp" in line
        ]
        if focus_lines:
            last_focus = " | ".join(focus_lines)
        if any("mCurrentFocus" in line and package in line for line in focus_lines):
            return True
        time.sleep(1)
    sys.stderr.write(f"Timed out waiting for focused window from {package}. Last focus: {last_focus}\n")
    return False


def read_ui_xml(serial: str | None) -> str:
    run_adb(serial, "shell", "rm", "-f", UI_DUMP_REMOTE_PATH, check=False)
    dump_result = run_adb(serial, "shell", "uiautomator", "dump", UI_DUMP_REMOTE_PATH, check=False)
    if dump_result.returncode != 0:
        return ""
    cat_result = run_adb(serial, "exec-out", "cat", UI_DUMP_REMOTE_PATH, check=False)
    if cat_result.returncode != 0:
        return ""
    xml = cat_result.stdout
    if not xml.lstrip().startswith("<?xml"):
        return ""
    return xml


def center_from_bounds(bounds: str) -> tuple[int, int] | None:
    try:
        left_top, right_bottom = bounds.split("][", 1)
        left, top = [int(part) for part in left_top.strip("[]").split(",", 1)]
        right, bottom = [int(part) for part in right_bottom.strip("[]").split(",", 1)]
        return ((left + right) // 2, (top + bottom) // 2)
    except (AttributeError, ValueError):
        return None


def parse_bounds(bounds: str) -> tuple[int, int, int, int] | None:
    try:
        left_top, right_bottom = bounds.split("][", 1)
        left, top = [int(part) for part in left_top.strip("[]").split(",", 1)]
        right, bottom = [int(part) for part in right_bottom.strip("[]").split(",", 1)]
        return left, top, right, bottom
    except (AttributeError, ValueError):
        return None


def build_parent_map(root: ET.Element) -> dict[ET.Element, ET.Element]:
    parent: dict[ET.Element, ET.Element] = {}
    for ancestor in root.iter():
        for child in ancestor:
            if child.tag == "node":
                parent[child] = ancestor
    return parent


def ui_contains_text(xml: str, *labels: str) -> bool:
    return all(
        label in xml or f'>{label}<' in xml or f'>{label.strip()}<' in xml
        for label in labels
    )


def navigation_drawer_is_open(xml: str) -> bool:
    if is_chat_home_xml(xml):
        return False
    return ui_contains_text(xml, "Accounts", "Settings") and (
        "Portal" in xml or "Provider Portal" in xml
    )


def find_scrollable_bounds(xml: str) -> tuple[int, int, int, int] | None:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    best: tuple[int, int, int, int] | None = None
    best_area = -1
    for node in root.iter("node"):
        if node.attrib.get("scrollable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        left, top, right, bottom = bounds
        area = max(0, right - left) * max(0, bottom - top)
        if area > best_area:
            best_area = area
            best = bounds
    return best


def tap_center_for_label(xml: str, label: str) -> tuple[int, int] | None:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    parent = build_parent_map(root)
    normalized_label = label.strip()
    for node in root.iter("node"):
        text = node.attrib.get("text", "").strip()
        content_description = node.attrib.get("content-desc", "").strip()
        if text != normalized_label and content_description != normalized_label:
            continue
        current: ET.Element | None = node
        for _ in range(8):
            if current is None:
                break
            if current.attrib.get("clickable") == "true":
                center = center_from_bounds(current.attrib.get("bounds", ""))
                if center is not None:
                    return center
            current = parent.get(current)
        return center_from_bounds(node.attrib.get("bounds", ""))
    return None


def tap_ui_label(serial: str | None, label: str) -> bool:
    xml = read_ui_xml(serial)
    if not xml:
        return False
    center = tap_center_for_label(xml, label)
    if center is None:
        return False
    run_adb(serial, "shell", "input", "tap", str(center[0]), str(center[1]), check=False)
    return True


def is_chat_home_xml(xml: str) -> bool:
    return "Message Hermes Fork" in xml or "Welcome to Hermes Agent Fork" in xml


def ensure_chat_home(serial: str | None) -> bool:
    for _ in range(4):
        xml = read_ui_xml(serial)
        if xml and is_chat_home_xml(xml) and not navigation_drawer_is_open(xml):
            return True
        if xml and navigation_drawer_is_open(xml):
            if tap_ui_label(serial, "Hermes Fork"):
                time.sleep(2.0)
                continue
        if xml and (
            "Files, Linux suite, and phone controls" in xml
            or "How to use this alpha" in xml
            or 'text="Device"' in xml
        ):
            if navigate_drawer_section(serial, "Hermes Fork"):
                time.sleep(2.0)
                continue
            return False
        launch(argparse.Namespace(serial=serial, package=DEFAULT_PACKAGE))
        if wait_for_ui_text(serial, CHAT_HOME_READY_TEXT, 30_000):
            xml = read_ui_xml(serial)
            if xml and is_chat_home_xml(xml) and not navigation_drawer_is_open(xml):
                return True
        time.sleep(1.5)
    xml = read_ui_xml(serial)
    return bool(xml and is_chat_home_xml(xml) and not navigation_drawer_is_open(xml))


def open_navigation_drawer(serial: str | None) -> bool:
    xml = read_ui_xml(serial)
    if xml and navigation_drawer_is_open(xml):
        return True
    if xml:
        center = tap_center_for_label(xml, "Open navigation menu")
        if center is not None:
            run_adb(serial, "shell", "input", "tap", str(center[0]), str(center[1]), check=False)
            time.sleep(1.5)
            return navigation_drawer_is_open(read_ui_xml(serial))
    run_adb(serial, "shell", "input", "tap", "96", "241", check=False)
    time.sleep(1.5)
    return navigation_drawer_is_open(read_ui_xml(serial))


def navigate_drawer_section(serial: str | None, section_label: str) -> bool:
    if not open_navigation_drawer(serial):
        return False
    if not tap_ui_label(serial, section_label):
        return False
    time.sleep(2.0)
    return True


def swipe_in_scroll_region(
    serial: str | None,
    bounds: tuple[int, int, int, int],
    *,
    direction: str = "down",
    duration_ms: int = 700,
) -> None:
    left, top, right, bottom = bounds
    center_x = (left + right) // 2
    if direction == "down":
        start_y = top + int((bottom - top) * 0.78)
        end_y = top + int((bottom - top) * 0.28)
    else:
        start_y = top + int((bottom - top) * 0.28)
        end_y = top + int((bottom - top) * 0.78)
    run_adb(
        serial,
        "shell",
        "input",
        "swipe",
        str(center_x),
        str(start_y),
        str(center_x),
        str(end_y),
        str(duration_ms),
        check=False,
    )


def scroll_until_text(
    serial: str | None,
    *labels: str,
    max_attempts: int = 16,
    pause_s: float = 1.0,
) -> str:
    required = tuple(labels)
    last_xml = ""
    for _ in range(max_attempts):
        last_xml = read_ui_xml(serial)
        if last_xml and ui_contains_text(last_xml, *required):
            return last_xml
        bounds = find_scrollable_bounds(last_xml) if last_xml else None
        if bounds is None:
            bounds = (0, 350, 1080, 2337)
        swipe_in_scroll_region(serial, bounds, direction="down", duration_ms=800)
        time.sleep(pause_s)
    return last_xml


def _load_prepare_android_linux_assets_module():
    import importlib.util

    repo_root = Path(__file__).resolve().parents[1]
    module_path = repo_root / "scripts" / "prepare_android_linux_assets.py"
    spec = importlib.util.spec_from_file_location("prepare_android_linux_assets", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_termux_package_mirrors(termux_arch: str = "x86_64") -> list[dict]:
    prepare_module = _load_prepare_android_linux_assets_module()
    relative_path = f"dists/stable/main/binary-{termux_arch}/Packages"
    results: list[dict] = []
    for base_url in prepare_module.configured_termux_main_base_urls():
        url = f"{base_url.rstrip('/')}/{relative_path}"
        started = time.monotonic()
        status = "error"
        detail = ""
        try:
            with urllib.request.urlopen(url, timeout=20) as response:
                status = "ok" if 200 <= response.status < 400 else f"http_{response.status}"
                response.read(1024)
        except urllib.error.HTTPError as error:
            status = f"http_{error.code}"
            detail = str(error)
        except Exception as error:  # pragma: no cover - live mirror probes
            detail = str(error)
        elapsed_ms = int((time.monotonic() - started) * 1000)
        results.append(
            {
                "base_url": base_url,
                "url": url,
                "status": status,
                "elapsed_ms": elapsed_ms,
                "detail": detail,
            }
        )
    healthy = [item for item in results if item["status"] == "ok"]
    for index, item in enumerate(results):
        item["preferred_rank"] = next(
            (rank for rank, healthy_item in enumerate(healthy) if healthy_item["base_url"] == item["base_url"]),
            None,
        )
    return results


def tap_first_ui_text(serial: str | None, xml: str, labels: tuple[str, ...]) -> bool:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return False
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        content_description = node.attrib.get("content-desc", "")
        if text in labels or content_description in labels:
            center = center_from_bounds(node.attrib.get("bounds", ""))
            if center is None:
                continue
            run_adb(serial, "shell", "input", "tap", str(center[0]), str(center[1]), check=False)
            return True
    return False


def continue_past_anr_dialog(serial: str | None, xml: str) -> bool:
    if "isn&apos;t responding" not in xml and "isn't responding" not in xml:
        return False
    if "Wait" not in xml:
        return False
    if tap_first_ui_text(serial, xml, ("Wait",)):
        print("Dismissed Android ANR dialog with Wait")
        return True
    return False


def wait_for_ui_text(serial: str | None, ready_text: str, timeout_ms: int) -> bool:
    if not ready_text:
        return True
    ready_texts = tuple(text.strip() for text in ready_text.split("|") if text.strip())
    if not ready_texts:
        return True
    deadline = time.monotonic() + (timeout_ms / 1000)
    while time.monotonic() <= deadline:
        xml = read_ui_xml(serial)
        if any(text in xml for text in ready_texts):
            return True
        if xml and continue_past_anr_dialog(serial, xml):
            time.sleep(1)
            continue
        time.sleep(2)
    sys.stderr.write(f"Timed out waiting for UI text: {ready_text}\n")
    return False


def set_size(args: argparse.Namespace) -> int:
    run_adb(args.serial, "shell", "wm", "size", args.size)
    if args.density:
        run_adb(args.serial, "shell", "wm", "density", str(args.density))
    return 0


def reset_size(args: argparse.Namespace) -> int:
    run_adb(args.serial, "shell", "wm", "size", "reset")
    run_adb(args.serial, "shell", "wm", "density", "reset")
    return 0


def dump_ui(args: argparse.Namespace) -> int:
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    xml = read_ui_xml(args.serial)
    if not xml:
        sys.stderr.write(f"uiautomator dump did not produce XML at {UI_DUMP_REMOTE_PATH}\n")
        return 1
    out.write_text(xml, encoding="utf-8")
    print(out)
    return 0


def tap_text(args: argparse.Namespace) -> int:
    if not tap_ui_label(args.serial, args.label):
        sys.stderr.write(f"UI label not found or not tappable: {args.label}\n")
        return 1
    print(args.label)
    return 0


def ensure_chat(args: argparse.Namespace) -> int:
    return 0 if ensure_chat_home(args.serial) else 1


def open_drawer(args: argparse.Namespace) -> int:
    return 0 if open_navigation_drawer(args.serial) else 1


def nav_section(args: argparse.Namespace) -> int:
    return 0 if navigate_drawer_section(args.serial, args.section) else 1


def scroll_until(args: argparse.Namespace) -> int:
    xml = scroll_until_text(
        args.serial,
        *args.labels,
        max_attempts=args.max_attempts,
        pause_s=args.pause_s,
    )
    if not xml or not ui_contains_text(xml, *args.labels):
        sys.stderr.write(f"Timed out scrolling to UI labels: {', '.join(args.labels)}\n")
        return 1
    if args.out:
        Path(args.out).write_text(xml, encoding="utf-8")
        print(args.out)
    else:
        print(",".join(args.labels))
    return 0


def mirror_check(args: argparse.Namespace) -> int:
    results = check_termux_package_mirrors(termux_arch=args.termux_arch)
    healthy = [item for item in results if item["status"] == "ok"]
    payload = {
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "termux_arch": args.termux_arch,
        "healthy_count": len(healthy),
        "recommended_base_urls": [item["base_url"] for item in healthy],
        "mirrors": results,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(out)
    if args.write_env and healthy:
        env_path = Path(args.write_env)
        env_path.parent.mkdir(parents=True, exist_ok=True)
        env_value = ",".join(item["base_url"] for item in healthy)
        env_path.write_text(f"HERMES_TERMUX_MAIN_BASE_URLS={env_value}\n", encoding="utf-8")
        print(env_path)
    return 0 if healthy else 1


def wide_capture(args: argparse.Namespace) -> int:
    if args.size:
        run_adb(args.serial, "shell", "wm", "size", args.size)
    if args.density:
        run_adb(args.serial, "shell", "wm", "density", str(args.density))
    try:
        if not args.no_launch:
            launch_result = launch(argparse.Namespace(serial=args.serial, package=args.package))
            if launch_result != 0:
                return launch_result
            if args.ready_timeout_ms and not wait_for_focus(
                args.serial,
                args.package,
                args.ready_timeout_ms,
            ):
                return 1
            if args.ready_timeout_ms and not wait_for_ui_text(
                args.serial,
                args.ready_text,
                args.ready_timeout_ms,
            ):
                return 1
        time.sleep(args.wait_ms / 1000)
        return write_screenshot(args.serial, Path(args.out))
    finally:
        if not args.keep_size:
            reset_size(argparse.Namespace(serial=args.serial))


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--serial", help="ADB device serial. Omit when exactly one device is attached.")
    sub = root.add_subparsers(dest="command", required=True)

    sub.add_parser("devices").set_defaults(func=devices)

    screenshot_parser = sub.add_parser("screenshot")
    screenshot_parser.add_argument("--out", required=True, help="PNG output path on the host.")
    screenshot_parser.set_defaults(func=screenshot)

    tap_parser = sub.add_parser("tap")
    tap_parser.add_argument("x", type=int)
    tap_parser.add_argument("y", type=int)
    tap_parser.set_defaults(func=tap)

    click_parser = sub.add_parser("click")
    click_parser.add_argument("x", type=int)
    click_parser.add_argument("y", type=int)
    click_parser.set_defaults(func=tap)

    swipe_parser = sub.add_parser("swipe")
    swipe_parser.add_argument("x1", type=int)
    swipe_parser.add_argument("y1", type=int)
    swipe_parser.add_argument("x2", type=int)
    swipe_parser.add_argument("y2", type=int)
    swipe_parser.add_argument("--duration-ms", type=int, default=300)
    swipe_parser.set_defaults(func=swipe)

    text_parser = sub.add_parser("text")
    text_parser.add_argument("text")
    text_parser.set_defaults(func=text)

    key_parser = sub.add_parser("keyevent")
    key_parser.add_argument("key", help="Android keyevent name or number, for example BACK or 4.")
    key_parser.set_defaults(func=keyevent)

    launch_parser = sub.add_parser("launch")
    launch_parser.add_argument("--package", default=DEFAULT_PACKAGE)
    launch_parser.set_defaults(func=launch)

    size_parser = sub.add_parser("set-size")
    size_parser.add_argument("size", help="Resolution such as 1920x1080.")
    size_parser.add_argument("--density", type=int, help="Optional density, for example 240.")
    size_parser.set_defaults(func=set_size)

    reset_parser = sub.add_parser("reset-size")
    reset_parser.set_defaults(func=reset_size)

    dump_parser = sub.add_parser("dump-ui")
    dump_parser.add_argument("--out", required=True, help="Host XML output path.")
    dump_parser.set_defaults(func=dump_ui)

    tap_text_parser = sub.add_parser("tap-text")
    tap_text_parser.add_argument("label", help="Visible text or content-desc label to tap.")
    tap_text_parser.set_defaults(func=tap_text)

    ensure_chat_parser = sub.add_parser("ensure-chat")
    ensure_chat_parser.set_defaults(func=ensure_chat)

    open_drawer_parser = sub.add_parser("open-drawer")
    open_drawer_parser.set_defaults(func=open_drawer)

    nav_section_parser = sub.add_parser("nav-section")
    nav_section_parser.add_argument("section", help="Drawer section label such as Device or Hermes Fork.")
    nav_section_parser.set_defaults(func=nav_section)

    scroll_until_parser = sub.add_parser("scroll-until-text")
    scroll_until_parser.add_argument("labels", nargs="+", help="All labels must be visible before success.")
    scroll_until_parser.add_argument("--max-attempts", type=int, default=16)
    scroll_until_parser.add_argument("--pause-s", type=float, default=1.0)
    scroll_until_parser.add_argument("--out", help="Optional host XML output path after scrolling.")
    scroll_until_parser.set_defaults(func=scroll_until)

    mirror_parser = sub.add_parser("check-termux-mirrors")
    mirror_parser.add_argument("--out", required=True, help="JSON report output path.")
    mirror_parser.add_argument("--termux-arch", default="x86_64")
    mirror_parser.add_argument(
        "--write-env",
        help="Optional .env path to write HERMES_TERMUX_MAIN_BASE_URLS from healthy mirrors.",
    )
    mirror_parser.set_defaults(func=mirror_check)

    wide_parser = sub.add_parser("wide-capture")
    wide_parser.add_argument("--out", required=True, help="Host PNG output path.")
    wide_parser.add_argument("--package", default=DEFAULT_PACKAGE)
    wide_parser.add_argument("--size", default="1920x1080", help="Temporary emulator resolution.")
    wide_parser.add_argument("--density", type=int, default=240, help="Temporary emulator density.")
    wide_parser.add_argument("--wait-ms", type=int, default=8000, help="Wait after launch before capture.")
    wide_parser.add_argument(
        "--ready-timeout-ms",
        type=int,
        default=90000,
        help="Maximum time to wait for the package to own the focused window.",
    )
    wide_parser.add_argument(
        "--ready-text",
        default=DEFAULT_READY_TEXT,
        help="Pipe-separated UI text alternatives that may appear before capture; pass an empty value to skip this check.",
    )
    wide_parser.add_argument("--no-launch", action="store_true", help="Capture current screen without launching Hermes.")
    wide_parser.add_argument("--keep-size", action="store_true", help="Do not reset wm size/density after capture.")
    wide_parser.set_defaults(func=wide_capture)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        return args.func(args)
    except subprocess.CalledProcessError as error:
        if error.stdout:
            sys.stdout.write(error.stdout)
        if error.stderr:
            sys.stderr.write(error.stderr)
        return error.returncode


if __name__ == "__main__":
    raise SystemExit(main())
