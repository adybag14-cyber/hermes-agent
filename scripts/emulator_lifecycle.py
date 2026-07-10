#!/usr/bin/env python3
"""Detached Hermes emulator launch + bounded ADB wait.

Agent background shells are killed after ~10 hours (max_runtime). Long-running
emulator.exe processes must be started detached so validation scripts can run in
the foreground, poll until the device is online, then exit.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_AVD = "HermesX86Api35"
DEFAULT_SERIAL = "emulator-5554"
DEFAULT_BOOT_TIMEOUT_S = 600
DEFAULT_POLL_INTERVAL_S = 5.0

DEFAULT_SDK = Path(
    r"C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_android_sdk"
)

DEFAULT_EMULATOR_ARGS = (
    "-no-snapshot-load",
    "-gpu",
    "swiftshader_indirect",
    "-memory",
    "6144",
    "-no-audio",
)

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACKAGE = "com.mobilefork.hermesagent"
DEFAULT_APK = REPO_ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def sdk_root() -> Path:
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value:
            root = Path(value)
            if root.is_dir():
                return root
    if DEFAULT_SDK.is_dir():
        return DEFAULT_SDK
    raise FileNotFoundError(
        "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
    )


def adb_executable() -> Path:
    root = sdk_root()
    name = "adb.exe" if os.name == "nt" else "adb"
    candidate = root / "platform-tools" / name
    if candidate.is_file():
        return candidate
    resolved = shutil.which(name)
    if resolved:
        return Path(resolved)
    raise FileNotFoundError(f"adb not found under {root / 'platform-tools'}")


def emulator_executable() -> Path:
    root = sdk_root()
    name = "emulator.exe" if os.name == "nt" else "emulator"
    candidate = root / "emulator" / name
    if candidate.is_file():
        return candidate
    resolved = shutil.which(name)
    if resolved:
        return Path(resolved)
    raise FileNotFoundError(f"emulator not found under {root / 'emulator'}")


def adb_devices() -> list[tuple[str, str]]:
    proc = subprocess.run(
        [str(adb_executable()), "devices"],
        check=False,
        text=True,
        capture_output=True,
    )
    rows: list[tuple[str, str]] = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def device_state(serial: str) -> str | None:
    for listed_serial, state in adb_devices():
        if listed_serial == serial:
            return state
    return None


def is_device_ready(serial: str) -> bool:
    return device_state(serial) == "device"


def pick_emulator_serial(preferred: str | None = None) -> str | None:
    online = [serial for serial, state in adb_devices() if state == "device"]
    if preferred and preferred in online:
        return preferred
    emulators = [serial for serial in online if serial.startswith("emulator-")]
    if len(emulators) == 1:
        return emulators[0]
    if preferred:
        return None
    return emulators[0] if emulators else None


def launch_emulator_detached(
    avd: str = DEFAULT_AVD,
    extra_args: tuple[str, ...] = DEFAULT_EMULATOR_ARGS,
) -> None:
    emulator = emulator_executable()
    command = [str(emulator), "-avd", avd, *extra_args]
    kwargs: dict = {
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
    }
    if os.name == "nt":
        # Hardcode Win32 values so this works even when the test suite
        # monkeypatches os.name="nt" on Linux runners (where subprocess
        # does not define DETACHED_PROCESS / CREATE_NEW_PROCESS_GROUP).
        detached = getattr(subprocess, "DETACHED_PROCESS", 0x00000008)
        new_group = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
        kwargs["creationflags"] = detached | new_group
    else:
        kwargs["start_new_session"] = True
    subprocess.Popen(command, **kwargs)
    print(f"Started detached emulator: {' '.join(command)}", flush=True)


def wait_for_device(
    serial: str,
    *,
    timeout_s: float = DEFAULT_BOOT_TIMEOUT_S,
    poll_interval_s: float = DEFAULT_POLL_INTERVAL_S,
) -> None:
    deadline = time.monotonic() + timeout_s
    last_state: str | None = None
    while time.monotonic() < deadline:
        state = device_state(serial)
        if state != last_state:
            if state:
                print(f"{serial}: {state}", flush=True)
            else:
                print(f"{serial}: offline", flush=True)
            last_state = state
        if state == "device":
            boot = subprocess.run(
                [str(adb_executable()), "-s", serial, "shell", "getprop", "sys.boot_completed"],
                check=False,
                text=True,
                capture_output=True,
            )
            if boot.stdout.strip() == "1":
                print(f"{serial}: boot completed", flush=True)
                return
        time.sleep(poll_interval_s)
    raise TimeoutError(
        f"{serial} did not become ready within {int(timeout_s)}s "
        f"(last state: {last_state or 'offline'})"
    )


def run_adb(serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    command = [str(adb_executable()), "-s", serial, *args]
    return subprocess.run(command, check=check, text=True, capture_output=True)


def package_installed(serial: str, package: str = DEFAULT_PACKAGE) -> bool:
    result = run_adb(serial, "shell", "pm", "path", package, check=False)
    return "package:" in result.stdout


def dismiss_setup_wizard(serial: str) -> None:
    """Skip first-boot setup screens that block app launch on fresh AVDs."""
    commands = (
        ("settings", "put", "global", "device_provisioned", "1"),
        ("settings", "put", "secure", "user_setup_complete", "1"),
        ("settings", "put", "global", "setup_wizard_has_run", "1"),
    )
    for args in commands:
        run_adb(serial, "shell", *args, check=False)
    run_adb(serial, "shell", "am", "force-stop", "com.google.android.setupwizard", check=False)
    print(f"{serial}: dismissed setup wizard", flush=True)


def install_apk(serial: str, apk_path: Path, *, reinstall: bool = True) -> None:
    if not apk_path.is_file():
        raise FileNotFoundError(
            f"APK not found at {apk_path}. Build with: "
            "cd android && gradlew :app:assembleDebug"
        )
    args = ["install"]
    if reinstall:
        args.append("-r")
    args.append("-d")
    args.append(str(apk_path))
    result = run_adb(serial, *args, check=False)
    combined = (result.stdout or "") + (result.stderr or "")
    if result.returncode != 0 or "Failure" in combined:
        raise RuntimeError(f"adb install failed ({result.returncode}): {combined.strip()}")
    print(f"{serial}: installed {apk_path.name}", flush=True)


def ensure_apk_installed(
    serial: str,
    *,
    apk_path: Path = DEFAULT_APK,
    package: str = DEFAULT_PACKAGE,
    reinstall: bool = True,
) -> None:
    if package_installed(serial, package) and not reinstall:
        print(f"{serial}: {package} already installed", flush=True)
        return
    if package_installed(serial, package) and reinstall:
        print(f"{serial}: reinstalling {package}", flush=True)
    else:
        print(f"{serial}: installing {package}", flush=True)
    install_apk(serial, apk_path, reinstall=reinstall)


def ensure_validation_ready(
    *,
    serial: str = DEFAULT_SERIAL,
    avd: str = DEFAULT_AVD,
    boot_timeout_s: float = DEFAULT_BOOT_TIMEOUT_S,
    poll_interval_s: float = DEFAULT_POLL_INTERVAL_S,
    launch_if_missing: bool = True,
    install_apk_if_missing: bool = True,
    apk_path: Path = DEFAULT_APK,
    dismiss_setup: bool = True,
) -> str:
    """Boot emulator detached, prepare device, and install Hermes APK."""
    serial = ensure_emulator_online(
        serial=serial,
        avd=avd,
        boot_timeout_s=boot_timeout_s,
        poll_interval_s=poll_interval_s,
        launch_if_missing=launch_if_missing,
    )
    if dismiss_setup:
        dismiss_setup_wizard(serial)
    if install_apk_if_missing:
        ensure_apk_installed(serial, apk_path=apk_path, reinstall=True)
    return serial


def ensure_emulator_online(
    *,
    serial: str = DEFAULT_SERIAL,
    avd: str = DEFAULT_AVD,
    boot_timeout_s: float = DEFAULT_BOOT_TIMEOUT_S,
    poll_interval_s: float = DEFAULT_POLL_INTERVAL_S,
    launch_if_missing: bool = True,
) -> str:
    """Return an online emulator serial, launching detached if needed."""
    ready = pick_emulator_serial(serial)
    if ready:
        print(f"Using online emulator {ready}", flush=True)
        return ready

    if not launch_if_missing:
        raise RuntimeError(
            f"No emulator online for serial={serial!r}. "
            "Start one manually or pass launch_if_missing=True."
        )

    print(f"No emulator online; launching {avd} detached...", flush=True)
    launch_emulator_detached(avd=avd)
    wait_for_device(
        serial,
        timeout_s=boot_timeout_s,
        poll_interval_s=poll_interval_s,
    )
    return serial


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--avd", default=DEFAULT_AVD)
    parser.add_argument("--boot-timeout-s", type=float, default=DEFAULT_BOOT_TIMEOUT_S)
    parser.add_argument("--poll-interval-s", type=float, default=DEFAULT_POLL_INTERVAL_S)
    parser.add_argument(
        "--no-launch",
        action="store_true",
        help="Fail instead of launching when the emulator is offline.",
    )
    parser.add_argument(
        "--install-apk",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Install app-debug.apk after the emulator is online.",
    )
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    args = parser.parse_args(argv)
    try:
        if args.install_apk:
            ensure_validation_ready(
                serial=args.serial,
                avd=args.avd,
                boot_timeout_s=args.boot_timeout_s,
                poll_interval_s=args.poll_interval_s,
                launch_if_missing=not args.no_launch,
                apk_path=args.apk.expanduser().resolve(),
            )
        else:
            ensure_emulator_online(
                serial=args.serial,
                avd=args.avd,
                boot_timeout_s=args.boot_timeout_s,
                poll_interval_s=args.poll_interval_s,
                launch_if_missing=not args.no_launch,
            )
    except (FileNotFoundError, RuntimeError, TimeoutError) as error:
        sys.stderr.write(f"{error}\n")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())