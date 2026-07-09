#!/usr/bin/env python3
"""Validate Hermes chat UI buttons from screenshot flow with timing tables."""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = REPO_ROOT / "scripts"
HARNESS = SCRIPTS_DIR / "android_visual_harness.py"

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from emulator_lifecycle import (  # noqa: E402
    device_state,
    ensure_validation_ready,
    is_device_ready,
    package_installed,
)

import android_visual_harness as avh  # noqa: E402
DEFAULT_OUT = REPO_ROOT / "verification-screenshots" / "v0.13.134" / "chat-ui"
DEFAULT_SERIAL = "emulator-5554"
DEFAULT_MAX_RUNTIME_S = 3600.0
RECOVERY_DEADLINE_EXTENSION_S = 900.0

SCREENSHOT_QUICK_ACTIONS = [
    ("Sensor Advisor", "sensor_workflow_advisor_report"),
    ("Motion Decision", "motion_sensor_decision_packet_report"),
    ("Motion Trends", "motion_sensor_history"),
    ("Motion Quality", "motion_sensor_quality"),
    ("Radio Signals", "radio_signal_graph"),
    ("Radio Advisor", "radio_signal_advisor_report"),
    ("Radio Decision", "radio_signal_decision_packet_report"),
]

ACK_NEEDLES = (
    "android_device_diagnostics_tool",
    "Run android_device_diagnostics_tool",
    "Hermes is preparing a reply",
    "You",
    "Your prompt",
    "Your full prompt",
)


MAX_EMULATOR_RECOVERIES = 5
_emulator_recovery_count = 0


def extend_deadline(deadline: float, *, reason: str, seconds: float = RECOVERY_DEADLINE_EXTENSION_S) -> float:
    extended = deadline + seconds
    print(f"extended validation deadline by {int(seconds)}s ({reason})", flush=True)
    return extended


def ensure_device_online(
    serial: str,
    *,
    context: str,
    deadline: float | None = None,
) -> tuple[str, bool, float | None]:
    global _emulator_recovery_count
    if is_device_ready(serial):
        return serial, False, deadline
    if _emulator_recovery_count < MAX_EMULATOR_RECOVERIES:
        _emulator_recovery_count += 1
        print(
            f"{serial}: offline during {context}; "
            f"recovery {_emulator_recovery_count}/{MAX_EMULATOR_RECOVERIES}...",
            flush=True,
        )
        serial = ensure_validation_ready(serial=serial, install_apk_if_missing=False)
        if deadline is not None:
            deadline = extend_deadline(deadline, reason=f"emulator recovery #{_emulator_recovery_count}")
        return serial, True, deadline
    state = device_state(serial) or "offline"
    raise RuntimeError(f"{serial} is not online during {context} (state={state})")


def check_deadline(deadline: float, context: str) -> None:
    if time.monotonic() > deadline:
        raise RuntimeError(f"Validation exceeded max runtime during {context}")


def log_step(out_dir: Path, message: str) -> None:
    print(message, flush=True)
    log_path = out_dir / "chat-ui-validation-run.log"
    stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write(f"{stamp} {message}\n")


def run_harness(
    serial: str,
    *args: str,
    check: bool = True,
    timeout_s: float = 120.0,
) -> subprocess.CompletedProcess[str]:
    command = [sys.executable, str(HARNESS), "--serial", serial, *args]
    try:
        return subprocess.run(command, check=check, text=True, capture_output=True, timeout=timeout_s)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(command, returncode=124, stdout="", stderr="harness timeout")


def read_ui_xml(serial: str, *, attempts: int = 4) -> str:
    dump_path = Path(REPO_ROOT / "verification-screenshots" / "v0.13.134" / "_chat_ui_dump.xml")
    for attempt in range(attempts):
        result = run_harness(serial, "dump-ui", "--out", str(dump_path), check=False)
        if result.returncode == 0 and dump_path.is_file():
            text = dump_path.read_text(encoding="utf-8", errors="replace")
            if text.strip() and "<hierarchy" in text:
                return text
        time.sleep(0.4 * (attempt + 1))
    return ""


def timing_row(label: str, samples_ms: list[float]) -> dict:
    if not samples_ms:
        return {"label": label, "samples": 0, "min_ms": None, "median_ms": None, "average_ms": None, "max_ms": None}
    return {
        "label": label,
        "samples": len(samples_ms),
        "min_ms": round(min(samples_ms), 1),
        "median_ms": round(statistics.median(samples_ms), 1),
        "average_ms": round(statistics.mean(samples_ms), 1),
        "max_ms": round(max(samples_ms), 1),
    }


def swipe_composer_menu(serial: str, direction: str = "down") -> None:
    if direction == "down":
        run_harness(serial, "swipe", "540", "1750", "540", "1250", "--duration-ms", "500", check=False)
    else:
        run_harness(serial, "swipe", "540", "1250", "540", "1750", "--duration-ms", "500", check=False)


def dismiss_keyboard(serial: str) -> None:
    run_harness(serial, "tap", "540", "260", check=False)
    run_harness(serial, "keyevent", "111", check=False)
    time.sleep(0.3)





def wait_for_any_text(serial: str, needles: tuple[str, ...], timeout_s: float = 8.0) -> tuple[bool, float, str | None]:
    started = time.monotonic()
    while time.monotonic() - started < timeout_s:
        xml = read_ui_xml(serial)
        for needle in needles:
            if needle in xml:
                return True, (time.monotonic() - started) * 1000.0, needle
        time.sleep(0.35)
    return False, (time.monotonic() - started) * 1000.0, None


def wait_for_send_idle(serial: str, timeout_s: float = 45.0) -> float:
    started = time.monotonic()
    saw_busy = False
    while time.monotonic() - started < min(timeout_s, 8.0):
        if "Hermes is preparing a reply" in read_ui_xml(serial):
            saw_busy = True
            break
        time.sleep(0.35)
    if saw_busy:
        while time.monotonic() - started < timeout_s:
            if "Hermes is preparing a reply" in read_ui_xml(serial):
                time.sleep(0.5)
                continue
            time.sleep(0.8)
            return (time.monotonic() - started) * 1000.0
    else:
        time.sleep(1.5)
    return (time.monotonic() - started) * 1000.0


def ensure_expanded_mode(serial: str) -> bool:
    xml = read_ui_xml(serial)
    if 'text="Expanded"' in xml:
        return True
    if 'text="Compact"' in xml:
        result = run_harness(serial, "tap-text", "Compact", check=False)
        if result.returncode != 0:
            return False
        time.sleep(0.8)
        found, _, _ = wait_for_any_text(serial, ('text="Expanded"', "Expanded"), timeout_s=5.0)
        return found
    return False


def ensure_chat_ready(serial: str, *, attempts: int = 4, deadline: float | None = None) -> tuple[str, bool]:
    """Reach chat home without the harness ensure-chat 30s UI waits."""
    for _ in range(attempts):
        if deadline is not None:
            check_deadline(deadline, "ensure_chat_ready")
        serial, _, _ = ensure_device_online(serial, context="ensure_chat_ready")
        xml = read_ui_xml(serial)
        if xml and chat_home_visible(xml):
            return serial, True
        run_harness(serial, "launch", check=False)
        time.sleep(3.0)
        xml = read_ui_xml(serial)
        if xml and chat_home_visible(xml):
            return serial, True
        run_harness(serial, "nav-section", "Hermes Fork", check=False)
        time.sleep(2.0)
    return serial, chat_home_visible(read_ui_xml(serial))


def ui_label_visible(xml: str, label: str) -> bool:
    normalized = label.strip()
    if not normalized:
        return False
    return any(
        token in xml
        for token in (
            f'text="{normalized}"',
            f'text=" {normalized}"',
            f'text="{normalized} "',
            f'content-desc="{normalized}"',
            f'content-desc=" {normalized}"',
            f">{normalized}<",
            label,
            normalized,
        )
    )


def composer_menu_is_open(xml: str) -> bool:
    return any(token in xml for token in ("Signal intelligence", "Image", "Camera", "Attach"))


def reset_composer_menu_scroll(serial: str) -> None:
    for _ in range(10):
        swipe_composer_menu(serial, direction="up")
        time.sleep(0.2)


def close_composer_menu(serial: str) -> None:
    xml = read_ui_xml(serial)
    if composer_menu_is_open(xml):
        run_harness(serial, "tap-text", "More input actions", check=False)
        time.sleep(0.45)


def open_composer_menu(serial: str, *, reset_scroll: bool = False, attempts: int = 4) -> bool:
    for attempt in range(attempts):
        xml = read_ui_xml(serial)
        if xml and composer_menu_is_open(xml):
            if reset_scroll:
                reset_composer_menu_scroll(serial)
            return True
        dismiss_keyboard(serial)
        run_harness(serial, "tap-text", "More input actions", check=False)
        time.sleep(0.65)
        found, _, _ = wait_for_any_text(serial, ("Signal intelligence", "Image", "Camera", "Attach"), timeout_s=6.0)
        if found:
            if reset_scroll:
                reset_composer_menu_scroll(serial)
            return True
        if attempt < attempts - 1:
            wait_for_send_idle(serial, timeout_s=8.0)
            dismiss_keyboard(serial)
            time.sleep(0.5)
    return False


def chat_home_visible(xml: str) -> bool:
    if "Message Hermes Fork" in xml or "Welcome to Hermes Agent Fork" in xml:
        return True
    return "More input actions" in xml or 'text="Send"' in xml or "Send" in xml


def start_new_chat(serial: str) -> bool:
    if run_harness(serial, "tap-text", "New chat", check=False).returncode == 0:
        time.sleep(1.0)
        if chat_home_visible(read_ui_xml(serial)):
            return True
    for sheet_opener in ("Open page actions", "Open history"):
        if run_harness(serial, "tap-text", sheet_opener, check=False).returncode != 0:
            continue
        time.sleep(0.7)
        if run_harness(serial, "tap-text", "New chat", check=False).returncode == 0:
            time.sleep(1.2)
            if chat_home_visible(read_ui_xml(serial)):
                return True
        if run_harness(serial, "tap-text", "Clear conversation", check=False).returncode == 0:
            time.sleep(1.2)
            if chat_home_visible(read_ui_xml(serial)):
                return True
        run_harness(serial, "keyevent", "4", check=False)
        time.sleep(0.4)
    return False


def reset_quick_action_surface(serial: str) -> bool:
    """Lightweight chat reset before falling back to a cold relaunch."""
    wait_for_send_idle(serial, timeout_s=20.0)
    if not start_new_chat(serial):
        return False
    time.sleep(0.8)
    ensure_expanded_mode(serial)
    dismiss_keyboard(serial)
    close_composer_menu(serial)
    xml = read_ui_xml(serial)
    return bool(xml and chat_home_visible(xml) and not composer_menu_is_open(xml))


def recover_chat_home(serial: str, *, attempts: int = 2) -> bool:
    for attempt in range(attempts):
        run_harness(serial, "launch", check=False)
        time.sleep(3.0 + attempt)
        serial, ready = ensure_chat_ready(serial, attempts=2)
        if not ready:
            continue
        if not ensure_expanded_mode(serial):
            continue
        if not start_new_chat(serial):
            continue
        time.sleep(0.8)
        dismiss_keyboard(serial)
        close_composer_menu(serial)
        xml = read_ui_xml(serial)
        if xml and chat_home_visible(xml) and not composer_menu_is_open(xml):
            return True
    return chat_home_visible(read_ui_xml(serial))


def prepare_for_quick_action(serial: str, *, index: int = 1) -> bool:
    dismiss_keyboard(serial)
    if index > 1:
        wait_for_send_idle(serial, timeout_s=45.0)
    if index >= 4 and not reset_quick_action_surface(serial):
        if not recover_chat_home(serial):
            return False
    elif index > 1:
        # Best-effort reset; fall back to a full relaunch when sheet actions are unavailable.
        if not start_new_chat(serial) and not recover_chat_home(serial):
            return False
    time.sleep(1.0)
    ensure_expanded_mode(serial)
    dismiss_keyboard(serial)
    close_composer_menu(serial)
    return chat_home_visible(read_ui_xml(serial))


def scroll_to_label(serial: str, label: str, max_attempts: int = 24, *, reset_first: bool = True) -> bool:
    if reset_first:
        reset_composer_menu_scroll(serial)
    xml = read_ui_xml(serial)
    if ui_label_visible(xml, label):
        return True
    # Top-of-menu labels can sit above the viewport; scroll up before down.
    for _ in range(8):
        swipe_composer_menu(serial, direction="up")
        time.sleep(0.3)
        xml = read_ui_xml(serial)
        if ui_label_visible(xml, label):
            return True
    for _ in range(max_attempts):
        swipe_composer_menu(serial, direction="down")
        time.sleep(0.3)
        xml = read_ui_xml(serial)
        if ui_label_visible(xml, label):
            return True
    try:
        xml = avh.scroll_until_text(serial, label, max_attempts=16, pause_s=0.35)
        if xml and ui_label_visible(xml, label):
            return True
    except Exception:
        pass
    return ui_label_visible(read_ui_xml(serial), label)


def wait_for_quick_action_ack(serial: str, diagnostic_action: str, timeout_s: float = 15.0) -> tuple[bool, float, str | None]:
    needles = (
        diagnostic_action,
        "android_device_diagnostics_tool",
        "Run android_device_diagnostics_tool",
        "Hermes is preparing a reply",
        "You",
        "Your prompt",
        "Your full prompt",
    )
    started = time.monotonic()
    while time.monotonic() - started < timeout_s:
        xml = read_ui_xml(serial)
        for needle in needles:
            if needle in xml:
                return True, (time.monotonic() - started) * 1000.0, needle
        time.sleep(0.35)
    return False, (time.monotonic() - started) * 1000.0, None


def run_validation_pass(
    serial: str,
    deadline: float,
    out_dir: Path,
    *,
    run_attempt: int,
) -> tuple[int, str]:
    log_step(out_dir, f"validation pass {run_attempt} starting on {serial}")
    summary: dict = {
        "version": "0.13.135",
        "emulator": serial,  # updated again if mid-run recovery relaunches the AVD
        "screenshot_reference": r"C:\Users\Ady\Downloads\Screenshot_20260707_132306.jpg",
        "steps": [],
        "timing_tables": {},
    }

    launch_started = time.monotonic()
    check_deadline(deadline, "startup")
    log_step(out_dir, "startup: ensuring device online")
    serial, recovered, deadline = ensure_device_online(serial, context="startup", deadline=deadline)
    if recovered and not recover_chat_home(serial):
        raise RuntimeError("Unable to restore chat home after startup emulator recovery")
    log_step(out_dir, "startup: launching Hermes")
    run_harness(serial, "launch", check=False)
    time.sleep(4)
    log_step(out_dir, "startup: ensuring chat home")
    serial, chat_ready = ensure_chat_ready(serial, deadline=deadline)
    if not chat_ready:
        raise RuntimeError(
            "Unable to reach Hermes chat home after launch "
            f"(package_installed={package_installed(serial)})"
        )
    launch_ms = (time.monotonic() - launch_started) * 1000.0
    summary["timing_tables"]["cold_launch_to_chat_ms"] = timing_row("cold_launch_to_chat", [launch_ms])
    start_new_chat(serial)
    ensure_expanded_mode(serial)
    run_harness(serial, "screenshot", "--out", str(out_dir / "01-chat-home.png"))

    expanded_ok = ensure_expanded_mode(serial)
    summary["steps"].append({"name": "ensure_expanded_mode", "ok": expanded_ok})
    run_harness(serial, "screenshot", "--out", str(out_dir / "02-expanded-mode.png"), check=False)

    menu_started = time.monotonic()
    menu_result = run_harness(serial, "tap-text", "More input actions", check=False)
    menu_ms = (time.monotonic() - menu_started) * 1000.0
    menu_found, _, _ = wait_for_any_text(serial, ("Signal intelligence", "Image", "Camera", "Attach"), timeout_s=5.0)
    summary["steps"].append(
        {
            "name": "open_action_menu",
            "label": "More input actions",
            "ok": menu_result.returncode == 0 and menu_found,
            "tap_ms": round(menu_ms, 1),
        }
    )
    summary["timing_tables"]["header_control_tap_ms"] = timing_row("open_action_menu", [menu_ms])
    run_harness(serial, "screenshot", "--out", str(out_dir / "02-header-open_action_menu.png"), check=False)
    close_composer_menu(serial)

    button_rows: list[dict] = []
    tap_samples: list[float] = []
    ack_samples: list[float] = []
    idle_samples: list[float] = []
    for index, (label, diagnostic_action) in enumerate(SCREENSHOT_QUICK_ACTIONS, start=1):
        check_deadline(deadline, f"quick_action:{label}")
        print(f"quick_action {index}/{len(SCREENSHOT_QUICK_ACTIONS)}: {label}", flush=True)
        if button_rows and not button_rows[-1]["ok"]:
            print(f"recovering chat home after failed {button_rows[-1]['label']}", flush=True)
            recover_chat_home(serial)
        serial, recovered, deadline = ensure_device_online(
            serial,
            context=f"quick_action:{label}",
            deadline=deadline,
        )
        if recovered and not recover_chat_home(serial):
            raise RuntimeError(f"Unable to restore chat home after emulator recovery ({label})")
        menu_open = False
        scrolled = False
        for attempt in range(3):
            if not prepare_for_quick_action(serial, index=index):
                if recover_chat_home(serial):
                    continue
                break
            menu_open = open_composer_menu(serial, reset_scroll=index > 1 or attempt > 0)
            scrolled = scroll_to_label(serial, label, reset_first=index > 1 or attempt > 0) if menu_open else False
            if menu_open and not scrolled:
                close_composer_menu(serial)
                time.sleep(0.45)
                menu_open = open_composer_menu(serial, reset_scroll=True)
                scrolled = scroll_to_label(serial, label, reset_first=True) if menu_open else False
            if menu_open and scrolled:
                break
            recover_chat_home(serial)
        visible = menu_open and scrolled
        if not visible:
            print(f"quick_action {label}: not visible after retries; final recover + single retry", flush=True)
            recover_chat_home(serial)
            if prepare_for_quick_action(serial, index=index):
                menu_open = open_composer_menu(serial, reset_scroll=True)
                scrolled = scroll_to_label(serial, label, reset_first=True) if menu_open else False
                visible = menu_open and scrolled
        dismiss_keyboard(serial)
        tap_started = time.monotonic()
        tap_result = run_harness(serial, "tap-text", label, check=False) if visible else subprocess.CompletedProcess(
            args=[], returncode=1, stdout="", stderr=f"label not visible: {label}"
        )
        tap_ms = (time.monotonic() - tap_started) * 1000.0
        ack_ok = False
        ack_ms = 0.0
        ack_needle: str | None = None
        if tap_result.returncode == 0:
            ack_ok, ack_ms, ack_needle = wait_for_quick_action_ack(serial, diagnostic_action, timeout_s=15.0)
        idle_ms = wait_for_send_idle(serial, timeout_s=35.0) if tap_result.returncode == 0 else 0.0
        ok = visible and tap_result.returncode == 0 and ack_ok
        tap_samples.append(tap_ms)
        if ack_ok:
            ack_samples.append(ack_ms)
            idle_samples.append(idle_ms)
        button_rows.append(
            {
                "label": label,
                "menu_open": menu_open,
                "visible_before_tap": visible,
                "ok": ok,
                "tap_ms": round(tap_ms, 1),
                "ack_ms": round(ack_ms, 1),
                "ack_needle": ack_needle,
                "idle_ms": round(idle_ms, 1),
            }
        )
        run_harness(serial, "screenshot", "--out", str(out_dir / f"03-quick-action-{index:02d}.png"), check=False)
        time.sleep(0.5)

    summary["quick_action_buttons"] = button_rows
    summary["timing_tables"]["quick_action_tap_ms"] = timing_row("quick_action_tap", tap_samples)
    summary["timing_tables"]["quick_action_ack_ms"] = timing_row("quick_action_ack", ack_samples)
    summary["timing_tables"]["quick_action_idle_ms"] = timing_row("quick_action_idle", idle_samples)
    passed_buttons = sum(1 for row in button_rows if row["ok"])
    summary["passed_quick_actions"] = passed_buttons
    summary["total_quick_actions"] = len(button_rows)
    summary["expanded_mode_ok"] = expanded_ok
    summary["emulator_recoveries"] = _emulator_recovery_count
    summary["validation_pass"] = run_attempt
    summary["ok"] = passed_buttons == len(button_rows)

    summary["emulator"] = serial
    summary_path = out_dir / "chat-ui-validation-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if not summary["ok"]:
        failed_copy = out_dir / "chat-ui-validation-summary.failed.json"
        failed_copy.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(summary_path)
    print(json.dumps(summary["timing_tables"], indent=2))
    if summary["ok"]:
        print(
            f"chat-ui validation PASSED: {passed_buttons}/{len(button_rows)} quick actions "
            f"(emulator_recoveries={_emulator_recovery_count})",
            flush=True,
        )
    else:
        failed = [row["label"] for row in button_rows if not row["ok"]]
        message = (
            f"chat-ui validation FAILED: {passed_buttons}/{len(button_rows)} quick actions passed; "
            f"failed={failed}; emulator_recoveries={_emulator_recovery_count}"
        )
        log_step(out_dir, message)
        sys.stderr.write(message + "\n")
    return (0 if summary["ok"] else 1), serial


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument(
        "--ensure-emulator",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Launch emulator detached, install APK, wait for ADB (default: true).",
    )
    parser.add_argument(
        "--install-apk",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Install app-debug.apk when ensuring emulator (default: true).",
    )
    parser.add_argument(
        "--max-runtime-s",
        type=float,
        default=DEFAULT_MAX_RUNTIME_S,
        help="Wall-clock cap for each validation pass (default: 3600s).",
    )
    parser.add_argument(
        "--retry-on-fail",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Run one full retry after a failed pass (default: true).",
    )
    args = parser.parse_args()

    out_dir = args.out_dir.expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    serial = args.serial
    if args.ensure_emulator:
        serial = ensure_validation_ready(
            serial=serial,
            install_apk_if_missing=args.install_apk,
        )
    elif args.install_apk and not package_installed(serial):
        log_step(out_dir, f"{serial}: APK missing; running lifecycle install before validation")
        serial = ensure_validation_ready(
            serial=serial,
            install_apk_if_missing=True,
        )
    elif not is_device_ready(serial):
        raise RuntimeError(
            f"{serial} is not online (state={device_state(serial) or 'offline'}). "
            "Run scripts/ensure-hermes-emulator.ps1 or omit --no-ensure-emulator."
        )

    max_attempts = 2 if args.retry_on_fail else 1
    exit_code = 1
    for run_attempt in range(1, max_attempts + 1):
        global _emulator_recovery_count
        _emulator_recovery_count = 0
        deadline = time.monotonic() + max(60.0, args.max_runtime_s)
        if run_attempt > 1:
            log_step(out_dir, f"retrying validation ({run_attempt}/{max_attempts}) after failed pass")
            serial = ensure_validation_ready(serial=serial, install_apk_if_missing=False)
        exit_code, serial = run_validation_pass(
            serial,
            deadline,
            out_dir,
            run_attempt=run_attempt,
        )
        if exit_code == 0:
            break
    return exit_code


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        sys.stderr.write(f"{error}\n")
        raise SystemExit(1)
    except subprocess.CalledProcessError as error:
        sys.stderr.write(error.stderr or str(error))
        raise SystemExit(1)