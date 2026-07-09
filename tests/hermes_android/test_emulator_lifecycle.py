from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LIFECYCLE_PATH = REPO_ROOT / "scripts" / "emulator_lifecycle.py"


def load_lifecycle():
    spec = importlib.util.spec_from_file_location("emulator_lifecycle", LIFECYCLE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_pick_emulator_serial_prefers_requested_device():
    lifecycle = load_lifecycle()

    def fake_devices():
        return [("emulator-5554", "device"), ("emulator-5556", "device")]

    lifecycle.adb_devices = fake_devices
    assert lifecycle.pick_emulator_serial("emulator-5556") == "emulator-5556"


def test_ensure_emulator_online_reuses_ready_device_without_launch(monkeypatch):
    lifecycle = load_lifecycle()
    lifecycle.adb_devices = lambda: [("emulator-5554", "device")]
    launched: list[str] = []
    lifecycle.launch_emulator_detached = lambda **_: launched.append("launch")
    assert lifecycle.ensure_emulator_online(serial="emulator-5554") == "emulator-5554"
    assert launched == []


def test_ensure_emulator_online_launches_detached_when_offline(monkeypatch):
    lifecycle = load_lifecycle()
    lifecycle.adb_devices = lambda: []
    lifecycle.launch_emulator_detached = lambda **_: None
    lifecycle.wait_for_device = lambda *_args, **_kwargs: None
    assert lifecycle.ensure_emulator_online(serial="emulator-5554") == "emulator-5554"


def test_launch_emulator_detached_uses_detached_flags_on_windows(monkeypatch):
    lifecycle = load_lifecycle()
    lifecycle.sdk_root = lambda: Path("C:/sdk")
    lifecycle.emulator_executable = lambda: Path("C:/sdk/emulator/emulator.exe")
    captured: dict = {}

    class FakePopen:
        def __init__(self, command, **kwargs):
            captured["command"] = command
            captured["kwargs"] = kwargs

    monkeypatch.setattr(lifecycle.subprocess, "Popen", FakePopen)
    monkeypatch.setattr(lifecycle.os, "name", "nt", raising=False)
    lifecycle.launch_emulator_detached(avd="HermesX86Api35")
    assert "6144" in captured["command"]
    flags = captured["kwargs"]["creationflags"]
    assert flags & subprocess.DETACHED_PROCESS
    assert flags & subprocess.CREATE_NEW_PROCESS_GROUP


def test_package_installed_detects_pm_path_output():
    lifecycle = load_lifecycle()
    lifecycle.run_adb = lambda _serial, *args, check=True: type(
        "Result", (), {"stdout": "package:/data/app/.../base.apk", "stderr": ""}
    )()
    assert lifecycle.package_installed("emulator-5554") is True


def test_ensure_validation_ready_installs_apk_on_fresh_device(monkeypatch):
    lifecycle = load_lifecycle()
    lifecycle.ensure_emulator_online = lambda **_: "emulator-5554"
    lifecycle.dismiss_setup_wizard = lambda _serial: None
    installed: list[str] = []
    lifecycle.ensure_apk_installed = lambda serial, **_: installed.append(serial)
    assert lifecycle.ensure_validation_ready(serial="emulator-5554") == "emulator-5554"
    assert installed == ["emulator-5554"]


def test_wait_for_device_times_out_when_never_ready(monkeypatch):
    lifecycle = load_lifecycle()
    lifecycle.device_state = lambda _serial: "offline"
    monkeypatch.setattr(lifecycle.time, "sleep", lambda _s: None)
    try:
        lifecycle.wait_for_device("emulator-5554", timeout_s=0.01, poll_interval_s=0.0)
    except TimeoutError as error:
        assert "did not become ready" in str(error)
    else:
        raise AssertionError("expected TimeoutError")