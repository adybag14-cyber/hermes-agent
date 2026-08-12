from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIGEST = "d" * 64
CANDIDATE_SHA = "c" * 64
TEST_SHA = "e" * 64
RUN_ID = "release-v0.13.147-live-5566"
BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
SERIAL = "emulator-5566"
AVD = "Medium_Phone_API_35"
QEMU_COMMAND = (
    '"C:\\Android SDK\\emulator\\qemu\\windows-x86_64\\qemu-system-x86_64.exe" '
    f"-avd {AVD} -gpu host -accel on -port 5566"
)


def _load_module(name: str, relative_path: str):
    spec = importlib.util.spec_from_file_location(name, REPO_ROOT / relative_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def release_module():
    return _load_module("android_release_evidence", "scripts/android_release_evidence.py")


@pytest.fixture(scope="module")
def collector_module(release_module):
    del release_module
    return _load_module(
        "android_collect_performance_evidence", "scripts/android_collect_performance_evidence.py"
    )


class FixtureExecutor:
    """Deterministic argv responder; it never starts adb, QEMU, or PowerShell."""

    def __init__(
        self,
        module,
        *,
        profile: str = "phone-compact",
        gfx_frames: int = 120,
        gfx_jank_percent: str = "5.00",
    ):
        self.module = module
        self.profile = profile
        self.gfx_frames = gfx_frames
        self.gfx_jank_percent = gfx_jank_percent
        self.calls: list[tuple[str, ...]] = []
        self.boot_reads = 0
        self.changed_boot_id: str | None = None
        self.accel_returncode = 0
        self.accel_output = "accel:\n0\nWHPX (10.0.26100) is installed and usable."
        self.candidate_sha = CANDIDATE_SHA
        self.pidof_reads = 0
        self.warm_process_pid = 8123
        self.process_pid_after_back: int | None = None
        self.kill_process_after_back = False
        self.pidof_responses: list[int | None] | None = None
        self.warm_unknown_once = False
        self.warm_start_reads = 0
        self.warm_outputs: list[str] | None = None
        self.gfx_header_pid: int | None = 8123
        self.meminfo_header_pid: int | None = 8123
        self.font_scale_outputs = ["1.0", "1.0"]
        self.font_scale_reads = 0
        self.resumed_activities: list[str | None] = [
            "com.mobilefork.hermesagent/.MainActivity",
            "com.mobilefork.hermesagent/.MainActivity",
        ]
        self.activity_reads = 0
        self.ui_remove_stdout: dict[str, str] = {}
        self.ui_remove_stderr: dict[str, str] = {}
        self.ui_dump_stdout: dict[str, str] = {}
        self.ui_dump_stderr: dict[str, str] = {}
        self.ui_stage = "initial"
        self.ui_overrides: dict[str, str] = {}

    @staticmethod
    def ui_node(
        resource_id: str,
        bounds: str,
        *,
        clickable: bool = False,
        scrollable: bool = False,
        enabled: bool = True,
        package: str = "com.mobilefork.hermesagent",
    ) -> str:
        return (
            f'<hierarchy rotation="0"><node package="{package}" '
            f'resource-id="{resource_id}" bounds="{bounds}" '
            f'enabled="{str(enabled).lower()}" '
            f'clickable="{str(clickable).lower()}" scrollable="{str(scrollable).lower()}"/>'
            "</hierarchy>"
        )

    def result(self, argv, stdout="", *, returncode=0, stderr=""):
        return self.module.CommandResult(tuple(argv), returncode, stdout, stderr)

    def run(self, args, *, timeout_seconds):
        del timeout_seconds
        argv = tuple(str(part) for part in args)
        self.calls.append(argv)
        if argv == ("adb", "devices", "-l"):
            return self.result(argv, f"List of devices attached\n{SERIAL} device product:sdk_phone\n")
        if argv == ("emulator", "-accel-check"):
            return self.result(argv, self.accel_output, returncode=self.accel_returncode)

        prefix = ("adb", "-s", SERIAL)
        assert argv[:3] == prefix, f"unexpected command: {argv!r}"
        command = argv[3:]
        if command == ("get-serialno",):
            return self.result(argv, f"{SERIAL}\n")
        if command == ("get-state",):
            return self.result(argv, "device\n")
        if command[:2] == ("shell", "getprop"):
            properties = {
                "ro.boot.qemu.avd_name": AVD,
                "ro.build.fingerprint": "google/sdk_gphone64_x86_64/emu64:15/test-keys",
                "ro.product.model": "sdk_gphone64_x86_64",
                "ro.build.version.sdk": "35",
                "ro.product.cpu.abilist": "x86_64,x86",
            }
            return self.result(argv, properties[command[2]] + "\n")
        if command == ("shell", "cat", "/proc/sys/kernel/random/boot_id"):
            self.boot_reads += 1
            value = self.changed_boot_id if self.boot_reads > 1 and self.changed_boot_id else BOOT_ID
            return self.result(argv, value + "\n")
        if command == ("shell", "settings", "get", "system", "font_scale"):
            index = min(self.font_scale_reads, len(self.font_scale_outputs) - 1)
            self.font_scale_reads += 1
            return self.result(argv, self.font_scale_outputs[index] + "\n")
        if command == ("shell", "pm", "path", "com.mobilefork.hermesagent"):
            return self.result(argv, "package:/data/app/hermes/base.apk\n")
        if command == ("shell", "pm", "path", "com.mobilefork.hermesagent.test"):
            return self.result(argv, "package:/data/app/hermes-test/base.apk\n")
        if command == ("shell", "sha256sum", "/data/app/hermes/base.apk"):
            return self.result(argv, f"{self.candidate_sha}  /data/app/hermes/base.apk\n")
        if command == ("shell", "sha256sum", "/data/app/hermes-test/base.apk"):
            return self.result(argv, f"{TEST_SHA}  /data/app/hermes-test/base.apk\n")
        if command == ("shell", "dumpsys", "package", "com.mobilefork.hermesagent"):
            return self.result(
                argv,
                "Packages:\n  versionCode=144790 minSdk=24 targetSdk=35\n  versionName=0.13.147\n",
            )
        if command == ("shell", "wm", "size"):
            size = "1080x2400" if self.profile == "phone-compact" else "1600x2560"
            return self.result(argv, f"Physical size: {size}\n")
        if command == ("shell", "wm", "density"):
            density = 420 if self.profile == "phone-compact" else 320
            return self.result(argv, f"Physical density: {density}\n")
        if command == ("shell", "am", "force-stop", "com.mobilefork.hermesagent"):
            return self.result(argv)
        if command == (
            "shell",
            "am",
            "start",
            "-W",
            "-S",
            "-n",
            "com.mobilefork.hermesagent/.MainActivity",
        ):
            return self.result(
                argv,
                "Status: ok\n"
                "LaunchState: COLD\n"
                "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                "TotalTime: 900\nWaitTime: 950\nComplete\n",
            )
        if command == ("shell", "pidof", "com.mobilefork.hermesagent"):
            self.pidof_reads += 1
            if self.pidof_responses is not None and self.pidof_reads <= len(
                self.pidof_responses
            ):
                response = self.pidof_responses[self.pidof_reads - 1]
                if response is None:
                    return self.result(argv, returncode=1)
                return self.result(argv, f"{response}\n")
            if self.pidof_reads == 2 and self.kill_process_after_back:
                return self.result(argv, returncode=1)
            pid = (
                self.process_pid_after_back
                if self.pidof_reads == 2 and self.process_pid_after_back is not None
                else self.warm_process_pid
            )
            return self.result(argv, f"{pid}\n")
        if command == ("shell", "input", "keyevent", "KEYCODE_BACK"):
            return self.result(argv)
        if command == (
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "com.mobilefork.hermesagent/.MainActivity",
        ):
            self.warm_start_reads += 1
            if self.warm_outputs is not None:
                return self.result(argv, self.warm_outputs[self.warm_start_reads - 1])
            if self.warm_unknown_once and self.warm_start_reads == 1:
                return self.result(
                    argv,
                    "Status: ok\n"
                    "LaunchState: UNKNOWN (0)\n"
                    "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                    "TotalTime: 0\nWaitTime: 7\nComplete\n",
                )
            return self.result(
                argv,
                "Status: ok\n"
                "LaunchState: WARM\n"
                "Activity: com.mobilefork.hermesagent/.MainActivity\n"
                "TotalTime: 200\nWaitTime: 210\nComplete\n",
            )
        if (
            len(command) == 4
            and command[:3] == ("shell", "rm", "-f")
            and command[3].startswith("/data/local/tmp/hermes-performance-ui-")
        ):
            phase = Path(command[3]).stem.removeprefix("hermes-performance-ui-")
            return self.result(
                argv,
                self.ui_remove_stdout.get(phase, ""),
                stderr=self.ui_remove_stderr.get(phase, ""),
            )
        if (
            len(command) == 4
            and command[:3] == ("shell", "uiautomator", "dump")
            and command[3].startswith("/data/local/tmp/hermes-performance-ui-")
        ):
            phase = Path(command[3]).stem.removeprefix("hermes-performance-ui-")
            return self.result(
                argv,
                self.ui_dump_stdout.get(
                    phase, f"UI hierchary dumped to: {command[3]}\n"
                ),
                stderr=self.ui_dump_stderr.get(phase, ""),
            )
        if (
            len(command) == 3
            and command[:2] == ("shell", "cat")
            and command[2].startswith("/data/local/tmp/hermes-performance-ui-")
        ):
            if self.ui_stage in self.ui_overrides:
                return self.result(argv, self.ui_overrides[self.ui_stage])
            if self.ui_stage == "initial":
                if self.profile == "phone-compact":
                    xml = self.ui_node(
                        "HermesChatDrawerButton", "[10,10][110,110]", clickable=True
                    )
                else:
                    xml = self.ui_node(
                        "HermesRailSettings", "[10,100][110,220]", clickable=True
                    )
            elif self.ui_stage == "drawer":
                xml = self.ui_node("HermesNavSettings", "[0,200][400,300]", clickable=True)
            else:
                bounds = (
                    "[0,100][1080,2300]"
                    if self.profile == "phone-compact"
                    else "[120,100][1600,2460]"
                )
                xml = self.ui_node(
                    "HermesSettingsContentList",
                    bounds,
                    scrollable=True,
                )
            return self.result(argv, xml)
        if command == ("shell", "input", "tap", "60", "60"):
            self.ui_stage = "drawer"
            return self.result(argv)
        if command == ("shell", "input", "tap", "200", "250"):
            self.ui_stage = "settings"
            return self.result(argv)
        if command == ("shell", "input", "tap", "60", "160"):
            self.ui_stage = "settings"
            return self.result(argv)
        if command == ("shell", "am", "get-config"):
            config = (
                "en-rUS-sw411dp-w411dp-h891dp-normal-long"
                if self.profile == "phone-compact"
                else "en-rUS-sw800dp-w800dp-h1280dp-xlarge"
            )
            return self.result(argv, f"config: {config}\n")
        if command == ("shell", "dumpsys", "SurfaceFlinger"):
            return self.result(
                argv,
                "GLES: Google (NVIDIA Corporation), Android Emulator OpenGL ES Translator "
                "(NVIDIA GeForce RTX 4090/PCIe/SSE2), OpenGL ES 3.2\n",
            )
        if command == ("shell", "dumpsys", "activity", "activities"):
            index = min(self.activity_reads, len(self.resumed_activities) - 1)
            self.activity_reads += 1
            component = self.resumed_activities[index]
            output = (
                "Activities:\n"
                if component is None
                else f"topResumedActivity=ActivityRecord{{abc u0 {component} t1}}\n"
            )
            return self.result(argv, output)
        if command == (
            "shell",
            "dumpsys",
            "gfxinfo",
            "com.mobilefork.hermesagent",
            "reset",
        ):
            return self.result(argv)
        if command[:3] == ("shell", "input", "swipe"):
            return self.result(argv)
        if command == (
            "shell",
            "dumpsys",
            "gfxinfo",
            "com.mobilefork.hermesagent",
        ):
            janky = 6
            header = (
                ""
                if self.gfx_header_pid is None
                else (
                    "** Graphics info for pid "
                    f"{self.gfx_header_pid} [com.mobilefork.hermesagent] **\n"
                )
            )
            output = (
                header
                + f"Total frames rendered: {self.gfx_frames}\n"
                + f"Janky frames: {janky} ({self.gfx_jank_percent}%)\n"
                + "50th percentile: 8ms\n"
                + "90th percentile: 14ms\n"
                + "95th percentile: 18ms\n"
                + "99th percentile: 28ms\n"
            )
            return self.result(
                argv,
                output,
            )
        if command == ("shell", "dumpsys", "meminfo", "com.mobilefork.hermesagent"):
            header = (
                ""
                if self.meminfo_header_pid is None
                else (
                    f"** MEMINFO in pid {self.meminfo_header_pid} "
                    "[com.mobilefork.hermesagent] **\n"
                )
            )
            return self.result(
                argv,
                header + " TOTAL PSS: 250000 TOTAL RSS: 320000 TOTAL SWAP PSS: 0\n",
            )
        raise AssertionError(f"unexpected command: {argv!r}")


class StaticProcessSource:
    def __init__(self, module, processes):
        self.module = module
        self.processes = tuple(processes)

    def qemu_snapshot(self):
        stdout = json.dumps(
            [
                {"pid": process.pid, "name": process.name, "command_line": process.command_line}
                for process in self.processes
            ],
            separators=(",", ":"),
        )
        argv = (
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            self.module.QEMU_CIM_SCRIPT,
        )
        return self.module.ProcessSnapshot(
            self.module.CommandResult(argv, 0, stdout, ""), self.processes
        )


class StaticSourceVerifier:
    def __init__(self, module):
        self.module = module
        self.seen: list[str] = []
        self.identities = [
            self.module.SourceIdentity(
                SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
            )
        ]

    def verify(self, expected_digest):
        self.seen.append(expected_digest)
        index = min(len(self.seen) - 1, len(self.identities) - 1)
        return self.identities[index]


class SourceModuleFixture:
    def __init__(self, identities):
        self.identities = list(identities)
        self.events: list[str] = []

    def require_source_clean_for_create(self, repo_root, evidence_root):
        assert evidence_root == repo_root / "android" / "release-evidence"
        self.events.append("clean")

    def git_source_tree_identity(self, repo_root):
        assert repo_root.is_absolute()
        self.events.append("identity")
        return self.identities.pop(0)


def test_git_source_verifier_brackets_identity_with_clean_checks(
    collector_module, monkeypatch, tmp_path
):
    identity = collector_module.SourceIdentity(
        SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
    )
    fixture = SourceModuleFixture([identity, identity])
    monkeypatch.setattr(
        collector_module, "_load_release_evidence_module", lambda: fixture
    )

    observed = collector_module.GitSourceVerifier(tmp_path).verify(SOURCE_DIGEST)

    assert observed == identity
    assert fixture.events == ["clean", "identity", "clean", "identity"]


def test_git_source_verifier_rejects_identity_drift_between_clean_checks(
    collector_module, monkeypatch, tmp_path
):
    initial = collector_module.SourceIdentity(
        SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
    )
    changed = collector_module.SourceIdentity(
        SOURCE_DIGEST, "sha256-git-tree-contents-v1", 124, "sha1"
    )
    fixture = SourceModuleFixture([initial, changed])
    monkeypatch.setattr(
        collector_module, "_load_release_evidence_module", lambda: fixture
    )

    with pytest.raises(collector_module.CollectorError, match="identity changed"):
        collector_module.GitSourceVerifier(tmp_path).verify(SOURCE_DIGEST)

    assert fixture.events == ["clean", "identity", "clean", "identity"]


def _config(module, profile="phone-compact"):
    return module.CollectorConfig(
        serial=SERIAL,
        profile=profile,
        release_source_digest=SOURCE_DIGEST,
        candidate_apk_sha256=CANDIDATE_SHA,
        instrumentation_apk_sha256=TEST_SHA,
        evidence_run_id=RUN_ID,
        version_name="0.13.147",
        version_code=144790,
        litertlm_coordinate=module.LITERTLM_COORDINATE,
        max_exercise_rounds=1,
        swipes_per_round=1,
    )


def _start_output(state: str, total_ms: int, wait_ms: int) -> str:
    return (
        "Status: ok\n"
        f"LaunchState: {state}\n"
        "Activity: com.mobilefork.hermesagent/.MainActivity\n"
        f"TotalTime: {total_ms}\n"
        f"WaitTime: {wait_ms}\n"
        "Complete\n"
    )


def _collect(
    module,
    executor=None,
    processes=None,
    source_verifier=None,
    *,
    profile="phone-compact",
):
    executor = executor or FixtureExecutor(module, profile=profile)
    processes = processes or (module.ProcessInfo(4242, "qemu-system-x86_64.exe", QEMU_COMMAND),)
    verifier = source_verifier or StaticSourceVerifier(module)
    collector = module.PerformanceCollector(
        _config(module, profile), executor, StaticProcessSource(module, processes), verifier
    )
    payload = collector.collect()
    return payload, executor, verifier, collector.raw_transcript


def _write_collected_pair(root: Path, profile: str, payload: dict, raw_transcript: dict):
    performance_dir = root / "performance"
    performance_dir.mkdir(exist_ok=True)
    normalized_path = performance_dir / f"{profile}.json"
    raw_path = performance_dir / f"{profile}.raw.json"
    normalized_path.write_text(json.dumps(payload), encoding="utf-8")
    raw_path.write_text(
        json.dumps(raw_transcript, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return normalized_path, raw_path


def test_live_fixture_collects_current_validator_compatible_payload(
    collector_module, release_module, tmp_path
):
    payload, executor, verifier, raw_transcript = _collect(collector_module)

    assert payload["litertlm_coordinate"] == collector_module.LITERTLM_COORDINATE
    assert payload["device"]["serial"] == SERIAL
    assert payload["device"]["avd_name"] == AVD
    assert payload["device"]["boot_id"] == BOOT_ID
    assert payload["device"]["emulator_pid"] == 4242
    assert payload["device"]["emulator_command"] == QEMU_COMMAND
    assert payload["device"]["acceleration_check_exit_code"] == 0
    assert payload["screen"]["font_scale"] == 1.0
    assert payload["launch"]["warm_process_pid"] == 8123
    assert payload["frames"] == {
        "total_rendered": 120,
        "janky": 6,
        "janky_percent": 5.0,
        "p50_ms": 8.0,
        "p90_ms": 14.0,
        "p95_ms": 18.0,
        "p99_ms": 28.0,
    }
    assert payload["collector"]["gfxinfo_exercise_rounds"] == 1
    assert payload["collector"]["ui_navigation_route"] == "phone-drawer-settings"
    assert payload["collector"]["settings_scroll_bounds_px"] == [0, 100, 1080, 2300]
    assert payload["collector"]["gfx_swipe_coordinates"] == [540, 1860, 540, 540]
    assert payload["raw_evidence"]["path"] == "performance/phone-compact.raw.json"
    assert raw_transcript["schema"] == "hermes-android-performance-raw-v1"
    record_ids = [record["id"] for record in raw_transcript["records"]]
    assert "initial.adb.get-serialno" in record_ids
    assert "initial.device.settings.font_scale" in record_ids
    assert "final.host.qemu_processes" in record_ids
    assert "final.device.settings.font_scale" in record_ids
    warm_sequence = [
        "measure.launch.cold",
        "measure.launch.pid_before_back",
        "measure.launch.back",
        "measure.launch.pid_after_back",
        "measure.launch.warm",
    ]
    cold_index = record_ids.index("measure.launch.cold")
    assert record_ids[cold_index : cold_index + len(warm_sequence)] == warm_sequence
    warm_records = {
        record["id"]: record for record in raw_transcript["records"] if record["id"] in warm_sequence
    }
    targeted = ["adb", "-s", SERIAL]
    assert warm_records["measure.launch.pid_before_back"]["argv"] == [
        *targeted,
        "shell",
        "pidof",
        "com.mobilefork.hermesagent",
    ]
    assert warm_records["measure.launch.back"]["argv"] == [
        *targeted,
        "shell",
        "input",
        "keyevent",
        "KEYCODE_BACK",
    ]
    assert warm_records["measure.launch.pid_after_back"]["argv"] == [
        *targeted,
        "shell",
        "pidof",
        "com.mobilefork.hermesagent",
    ]
    navigation_sequence = [
        "measure.ui.initial.remove",
        "measure.ui.initial.dump",
        "measure.ui.initial.cat",
        "measure.ui.phone.drawer.tap",
        "measure.ui.drawer.remove",
        "measure.ui.drawer.dump",
        "measure.ui.drawer.cat",
        "measure.ui.phone.settings.tap",
        "measure.ui.settings.remove",
        "measure.ui.settings.dump",
        "measure.ui.settings.cat",
    ]
    warm_index = record_ids.index("measure.launch.warm")
    assert record_ids[warm_index + 1 : warm_index + 1 + len(navigation_sequence)] == (
        navigation_sequence
    )
    ui_paths = {
        record["id"]: record["argv"][-1]
        for record in raw_transcript["records"]
        if record["id"].startswith("measure.ui.")
        and record["id"].endswith((".remove", ".dump", ".cat"))
    }
    for phase in ("initial", "drawer", "settings"):
        expected = f"/data/local/tmp/hermes-performance-ui-{phase}.xml"
        assert ui_paths[f"measure.ui.{phase}.remove"] == expected
        assert ui_paths[f"measure.ui.{phase}.dump"] == expected
        assert ui_paths[f"measure.ui.{phase}.cat"] == expected
    assert verifier.seen == [SOURCE_DIGEST, SOURCE_DIGEST]
    assert sum(call == ("adb", "devices", "-l") for call in executor.calls) == 2
    memory_index = record_ids.index("measure.memory.meminfo")
    assert record_ids[memory_index : memory_index + 2] == [
        "measure.memory.meminfo",
        "measure.process.pid_after_measurement",
    ]
    gfx_reset_index = record_ids.index("measure.gfx.reset")
    assert record_ids[gfx_reset_index - 1] == "measure.activity.before_gfx"
    summary_index = record_ids.index("measure.gfx.summary.01")
    assert record_ids[summary_index + 1] == "measure.activity.after_gfx"

    path, _ = _write_collected_pair(tmp_path, "phone-compact", payload, raw_transcript)
    assert release_module._validate_performance(
        path, "phone-compact", SOURCE_DIGEST, "0.13.147", 144790
    ) == payload


def test_tablet_uses_rail_route_and_settings_list_bounds(
    collector_module, release_module, tmp_path
):
    payload, _, _, raw = _collect(collector_module, profile="tablet")

    assert payload["collector"]["ui_navigation_route"] == "tablet-rail-settings"
    assert payload["collector"]["settings_scroll_bounds_px"] == [120, 100, 1600, 2460]
    assert payload["collector"]["gfx_swipe_coordinates"] == [860, 1988, 860, 572]
    record_ids = [record["id"] for record in raw["records"]]
    assert "measure.ui.tablet.settings.tap" in record_ids
    assert "measure.ui.phone.drawer.tap" not in record_ids
    path, _ = _write_collected_pair(tmp_path, "tablet", payload, raw)
    assert release_module._validate_performance(
        path, "tablet", SOURCE_DIGEST, "0.13.147", 144790
    ) == payload


def test_warm_unknown_zero_gets_one_pid_bound_recorded_retry(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.warm_unknown_once = True

    payload, _, _, raw = _collect(collector_module, executor=executor)

    assert payload["launch"]["warm_total_ms"] == 200
    record_ids = [record["id"] for record in raw["records"]]
    assert record_ids.count("measure.launch.retry.warm") == 1
    assert record_ids[record_ids.index("measure.launch.warm") + 1 :][0:4] == [
        "measure.launch.retry.pid_before_back",
        "measure.launch.retry.back",
        "measure.launch.retry.pid_after_back",
        "measure.launch.retry.warm",
    ]


def test_warm_unknown_retry_accepts_positive_hot_result(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.warm_outputs = [
        _start_output("UNKNOWN (0)", 0, 7),
        _start_output("HOT", 173, 181),
    ]

    payload, _, _, raw = _collect(collector_module, executor=executor)

    assert payload["launch"]["warm_total_ms"] == 173
    retry = next(
        record for record in raw["records"] if record["id"] == "measure.launch.retry.warm"
    )
    assert "LaunchState: HOT" in retry["stdout"]


@pytest.mark.parametrize(
    ("retry_output", "message"),
    [
        (_start_output("UNKNOWN (0)", 0, 7), "launch state"),
        (_start_output("COLD", 200, 210), "launch state"),
        (_start_output("WARM", 0, 10), "timings must be positive"),
        (_start_output("WARM", 10, 0), "timings must be positive"),
        (_start_output("WARM", -1, 10), "exactly one TotalTime and WaitTime"),
        (_start_output("WARM", 10, -1), "exactly one TotalTime and WaitTime"),
    ],
)
def test_bounded_warm_retry_rejects_noncertifying_second_result(
    collector_module, retry_output, message
):
    executor = FixtureExecutor(collector_module)
    executor.warm_outputs = [_start_output("UNKNOWN (0)", 0, 7), retry_output]

    with pytest.raises(collector_module.CollectorError, match=message):
        _collect(collector_module, executor=executor)

    assert executor.warm_start_reads == 2


@pytest.mark.parametrize(
    ("output", "message"),
    [
        (_start_output("WARM", 200, 210) + "Status: ok\n", "exactly one Status"),
        (_start_output("WARM", 200, 210) + "Status: failed\n", "exactly one Status"),
        (_start_output("WARM", 200, 210) + "LaunchState: WARM\n", "launch state"),
        (_start_output("WARM", 200, 210) + "LaunchState: COLD\n", "launch state"),
        (_start_output("WARM", 200, 210) + "TotalTime: 200\n", "exactly one TotalTime"),
        (_start_output("WARM", 200, 210) + "WaitTime: 211\n", "exactly one TotalTime"),
        (
            _start_output("WARM", 200, 210).replace(
                "Activity: com.mobilefork.hermesagent/.MainActivity\n", ""
            ),
            "intended Activity",
        ),
        (
            _start_output("WARM", 200, 210).replace(
                "com.mobilefork.hermesagent/.MainActivity", "com.example/.MainActivity"
            ),
            "intended Activity",
        ),
        (
            _start_output("WARM", 200, 210)
            + "Activity: com.mobilefork.hermesagent/.MainActivity\n",
            "intended Activity",
        ),
    ],
)
def test_accepted_start_parser_rejects_duplicate_conflicting_fields_or_wrong_activity(
    collector_module, output, message
):
    with pytest.raises(collector_module.CollectorError, match=message):
        collector_module._parse_start_metrics(output, expected_states={"WARM", "HOT"})


def test_unknown_wait_over_bound_is_not_retried(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.warm_outputs = [_start_output("UNKNOWN (0)", 0, 1_001)]

    with pytest.raises(collector_module.CollectorError, match="launch state"):
        _collect(collector_module, executor=executor)

    assert executor.warm_start_reads == 1


@pytest.mark.parametrize(
    "unknown_output",
    [
        _start_output("UNKNOWN (0)", 0, 7) + "Status: ok\n",
        _start_output("UNKNOWN (0)", 0, 7) + "LaunchState: UNKNOWN (0)\n",
        _start_output("UNKNOWN (0)", 0, 7) + "TotalTime: 0\n",
        _start_output("UNKNOWN (0)", 0, 7) + "WaitTime: 7\n",
        _start_output("UNKNOWN (0) trailing", 0, 7),
        _start_output("UNKNOWN OTHER", 0, 7),
        _start_output("UNKNOWN (0)", 0, 7).replace("Status: ok", "Status: ok extra"),
    ],
)
def test_malformed_or_duplicate_unknown_launch_is_not_retryable(
    collector_module, unknown_output
):
    executor = FixtureExecutor(collector_module)
    executor.warm_outputs = [unknown_output]

    with pytest.raises(collector_module.CollectorError):
        _collect(collector_module, executor=executor)

    assert executor.warm_start_reads == 1


@pytest.mark.parametrize(
    ("pidof_responses", "message"),
    [
        ([8123, 8123, None], "Failed to run adb shell"),
        ([8123, 8123, 8124], "changed after transient UNKNOWN"),
        ([8123, 8123, 8123, None], "Failed to run adb shell"),
        ([8123, 8123, 8123, 8124], "changed during bounded warm-launch retry"),
    ],
)
def test_bounded_warm_retry_rejects_killed_or_replaced_pid(
    collector_module, pidof_responses, message
):
    executor = FixtureExecutor(collector_module)
    executor.warm_outputs = [
        _start_output("UNKNOWN (0)", 0, 7),
        _start_output("HOT", 173, 181),
    ]
    executor.pidof_responses = pidof_responses

    with pytest.raises(collector_module.CollectorError, match=message):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize("final_pid", (None, 8124))
def test_measurement_rejects_killed_or_replaced_warm_process(collector_module, final_pid):
    executor = FixtureExecutor(collector_module)
    executor.pidof_responses = [8123, 8123, final_pid]

    with pytest.raises(collector_module.CollectorError):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize(
    ("attribute", "pid"),
    [
        ("gfx_header_pid", None),
        ("gfx_header_pid", 8124),
        ("meminfo_header_pid", None),
        ("meminfo_header_pid", 8124),
    ],
)
def test_measurement_output_must_name_the_warm_process(
    collector_module, attribute, pid
):
    executor = FixtureExecutor(collector_module)
    setattr(executor, attribute, pid)

    with pytest.raises(collector_module.CollectorError):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize(
    ("kind", "message"),
    [
        ("gfx_header", "process header"),
        ("gfx_total", "one unambiguous Total frames rendered"),
        ("gfx_jank", "one unambiguous Janky frames"),
        ("mem_header", "process header"),
        ("mem_total", "one TOTAL PSS/RSS pair"),
    ],
)
def test_measurement_rejects_duplicate_headers_or_metrics(collector_module, kind, message):
    executor = FixtureExecutor(collector_module)
    original_result = executor.result

    def duplicate_result(argv, stdout="", *, returncode=0, stderr=""):
        command = tuple(argv[3:]) if tuple(argv[:3]) == ("adb", "-s", SERIAL) else ()
        if command == (
            "shell",
            "dumpsys",
            "gfxinfo",
            "com.mobilefork.hermesagent",
        ):
            if kind == "gfx_header":
                stdout += "** Graphics info for pid 8123 [com.mobilefork.hermesagent] **\n"
            elif kind == "gfx_total":
                stdout += "Total frames rendered: 121\n"
            elif kind == "gfx_jank":
                stdout += "Janky frames: 7 (5.83%)\n"
        elif command == (
            "shell",
            "dumpsys",
            "meminfo",
            "com.mobilefork.hermesagent",
        ):
            if kind == "mem_header":
                stdout += "** MEMINFO in pid 8123 [com.mobilefork.hermesagent] **\n"
            elif kind == "mem_total":
                stdout += " TOTAL PSS: 250000 TOTAL RSS: 320000 TOTAL SWAP PSS: 0\n"
        return original_result(argv, stdout, returncode=returncode, stderr=stderr)

    executor.result = duplicate_result
    with pytest.raises(collector_module.CollectorError, match=message):
        _collect(collector_module, executor=executor)


def test_gfx_parser_accepts_android_duplicate_summary(collector_module):
    summary = (
        "Total frames rendered: 120\n"
        "Janky frames: 6 (5.00%)\n"
        "50th percentile: 8ms\n"
        "90th percentile: 14ms\n"
        "95th percentile: 18ms\n"
        "99th percentile: 28ms\n"
    )

    assert collector_module._parse_gfxinfo(summary + summary) == {
        "total_rendered": 120,
        "janky": 6,
        "janky_percent": 5.0,
        "p50_ms": 8.0,
        "p90_ms": 14.0,
        "p95_ms": 18.0,
        "p99_ms": 28.0,
    }


@pytest.mark.parametrize(
    ("initial_xml", "message"),
    [
        (
            FixtureExecutor.ui_node(
                "HermesChatDrawerButton",
                "[10,10][110,110]",
                clickable=True,
                package="com.example.unrelated",
            ),
            "wrong package",
        ),
        (
            FixtureExecutor.ui_node("MissingTag", "[10,10][110,110]", clickable=True),
            "must appear exactly once",
        ),
        (
            '<hierarchy rotation="0">'
            '<node package="com.mobilefork.hermesagent" resource-id="HermesChatDrawerButton" '
            'bounds="[10,10][110,110]" enabled="true" clickable="true" scrollable="false"/>'
            '<node package="com.mobilefork.hermesagent" resource-id="HermesChatDrawerButton" '
            'bounds="[120,10][220,110]" enabled="true" clickable="true" scrollable="false"/>'
            "</hierarchy>",
            "exactly once",
        ),
        (
            FixtureExecutor.ui_node(
                "HermesRailSettings", "[10,10][110,110]", clickable=True
            ),
            "wrong-profile",
        ),
    ],
)
def test_phone_navigation_hierarchy_fails_closed(collector_module, initial_xml, message):
    executor = FixtureExecutor(collector_module)
    executor.ui_overrides["initial"] = initial_xml

    with pytest.raises(collector_module.CollectorError, match=message):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize(
    ("settings_xml", "message"),
    [
        (
            FixtureExecutor.ui_node(
                "HermesSettingsContentList", "[0,100][1080,2300]", scrollable=True, enabled=False
            ),
            "not enabled",
        ),
        (
            FixtureExecutor.ui_node(
                "HermesSettingsContentList", "[0,100][1080,2300]", scrollable=False
            ),
            "not scrollable",
        ),
        (
            FixtureExecutor.ui_node(
                "HermesSettingsContentList", "[-1,100][1080,2300]", scrollable=True
            ),
            "invalid bounds",
        ),
        (
            FixtureExecutor.ui_node(
                "HermesSettingsContentList", "[100,100][10,2300]", scrollable=True
            ),
            "outside the effective",
        ),
        (
            FixtureExecutor.ui_node(
                "HermesSettingsContentList", "[0,100][0,2300]", scrollable=True
            ),
            "outside the effective",
        ),
        (
            FixtureExecutor.ui_node(
                "HermesSettingsContentList", "[0,100][1080,2401]", scrollable=True
            ),
            "outside the effective",
        ),
    ],
)
def test_settings_scroll_target_rejects_disabled_non_scrollable_or_unsafe_bounds(
    collector_module, settings_xml, message
):
    executor = FixtureExecutor(collector_module)
    executor.ui_overrides["settings"] = settings_xml

    with pytest.raises(collector_module.CollectorError, match=message):
        _collect(collector_module, executor=executor)


def test_tablet_initial_hierarchy_rejects_phone_only_navigation(collector_module):
    executor = FixtureExecutor(collector_module, profile="tablet")
    executor.ui_overrides["initial"] = FixtureExecutor.ui_node(
        "HermesChatDrawerButton", "[10,10][110,110]", clickable=True
    )

    with pytest.raises(collector_module.CollectorError, match="wrong-profile"):
        _collect(collector_module, executor=executor, profile="tablet")


@pytest.mark.parametrize(
    ("attribute", "value", "message"),
    [
        ("ui_dump_stdout", {"initial": ""}, "exact success"),
        (
            "ui_dump_stdout",
            {"initial": "UI hierarchy dumped to: /data/local/tmp/hermes-performance-ui-initial.xml\n"},
            "exact success",
        ),
        (
            "ui_dump_stdout",
            {"initial": "UI hierchary dumped to: /data/local/tmp/hermes-performance-ui-stale.xml\n"},
            "exact success",
        ),
        ("ui_dump_stderr", {"initial": "ERROR: stale hierarchy\n"}, "exact success"),
    ],
)
def test_ui_dump_requires_exact_fresh_success_marker(
    collector_module, attribute, value, message
):
    executor = FixtureExecutor(collector_module)
    setattr(executor, attribute, value)

    with pytest.raises(collector_module.CollectorError, match=message):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize(
    "output",
    [
        "topResumedActivity=ActivityRecord{abc u0 "
        "com.mobilefork.hermesagent/.MainActivity t1}\n",
        "ResumedActivity: ActivityRecord{abc u0 "
        "com.mobilefork.hermesagent/.MainActivity t1}\n",
        (
            "topResumedActivity=ActivityRecord{abc u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
            "ResumedActivity: ActivityRecord{def u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
        ),
    ],
)
def test_resumed_activity_parser_accepts_current_and_canonical_hermes_claims(
    collector_module, output
):
    collector_module._require_resumed_activity(output, "synthetic.activity")


@pytest.mark.parametrize(
    "output",
    [
        (
            "topResumedActivity=ActivityRecord{abc u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
            "ResumedActivity: malformed\n"
        ),
        (
            "topResumedActivity=ActivityRecord{abc u0 "
            "com.mobilefork.hermesagent/.MainActivity t1}\n"
            "ResumedActivity: ActivityRecord{def u0 com.example/.MainActivity t1}\n"
        ),
    ],
)
def test_resumed_activity_parser_rejects_unparsed_or_non_hermes_claims(
    collector_module, output
):
    with pytest.raises(collector_module.CollectorError, match="only resumed Hermes"):
        collector_module._require_resumed_activity(output, "synthetic.activity")


def test_ambiguous_matching_qemu_processes_fail_closed(collector_module):
    matching = collector_module.ProcessInfo(4242, "qemu-system-x86_64.exe", QEMU_COMMAND)
    with pytest.raises(collector_module.CollectorError, match="exactly one live qemu-system"):
        _collect(collector_module, processes=(matching, matching.__class__(4243, matching.name, QEMU_COMMAND)))


@pytest.mark.parametrize(
    ("exit_code", "output"),
    [
        (1, "WHPX accelerator check failed"),
        (0, "WHPX is not usable"),
    ],
)
def test_acceleration_check_failure_or_negative_output_fails_closed(
    collector_module, exit_code, output
):
    executor = FixtureExecutor(collector_module)
    executor.accel_returncode = exit_code
    executor.accel_output = output
    with pytest.raises(collector_module.CollectorError, match="accel-check"):
        _collect(collector_module, executor=executor)


def test_device_boot_change_during_collection_fails_closed(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.changed_boot_id = "87654321-4321-4cba-8fed-ba0987654321"
    with pytest.raises(collector_module.CollectorError, match="identity changed"):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize("font_scale_outputs", (["1.1"], ["1.0", "1.1"]))
def test_font_scale_must_be_one_and_cannot_drift(collector_module, font_scale_outputs):
    executor = FixtureExecutor(collector_module)
    executor.font_scale_outputs = list(font_scale_outputs)

    with pytest.raises(collector_module.CollectorError, match="font_scale exactly 1.0"):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize(
    "activities",
    [
        [None, "com.mobilefork.hermesagent/.MainActivity"],
        ["com.example/.MainActivity", "com.mobilefork.hermesagent/.MainActivity"],
        ["com.mobilefork.hermesagent/.MainActivity", None],
        ["com.mobilefork.hermesagent/.MainActivity", "com.example/.MainActivity"],
    ],
)
def test_gfx_measurement_requires_hermes_foreground_before_and_after(
    collector_module, activities
):
    executor = FixtureExecutor(collector_module)
    executor.resumed_activities = activities

    with pytest.raises(collector_module.CollectorError, match="resumed Hermes MainActivity"):
        _collect(collector_module, executor=executor)


def test_source_identity_change_during_collection_fails_closed(collector_module):
    verifier = StaticSourceVerifier(collector_module)
    verifier.identities = [
        collector_module.SourceIdentity(
            SOURCE_DIGEST, "sha256-git-tree-contents-v1", 123, "sha1"
        ),
        collector_module.SourceIdentity(
            SOURCE_DIGEST, "sha256-git-tree-contents-v1", 124, "sha1"
        ),
    ]

    with pytest.raises(collector_module.CollectorError, match="source identity changed"):
        _collect(collector_module, source_verifier=verifier)

    assert verifier.seen == [SOURCE_DIGEST, SOURCE_DIGEST]


def test_installed_candidate_apk_hash_mismatch_fails_closed(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.candidate_sha = "a" * 64
    with pytest.raises(collector_module.CollectorError, match="APK SHA-256 differs"):
        _collect(collector_module, executor=executor)


@pytest.mark.parametrize("duplicate", ("  versionName=0.13.147\n", "  versionCode=144790\n"))
def test_installed_version_rejects_duplicate_fields(collector_module, duplicate):
    executor = FixtureExecutor(collector_module)
    original_result = executor.result

    def duplicate_result(argv, stdout="", *, returncode=0, stderr=""):
        command = tuple(argv[3:]) if tuple(argv[:3]) == ("adb", "-s", SERIAL) else ()
        if command == ("shell", "dumpsys", "package", "com.mobilefork.hermesagent"):
            stdout += duplicate
        return original_result(argv, stdout, returncode=returncode, stderr=stderr)

    executor.result = duplicate_result
    with pytest.raises(collector_module.CollectorError, match="one unambiguous installed version"):
        _collect(collector_module, executor=executor)


def test_warm_launch_rejects_process_killed_by_back_before_relaunch(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.kill_process_after_back = True

    with pytest.raises(collector_module.CollectorError, match="Failed to run adb shell: exit 1"):
        _collect(collector_module, executor=executor)

    warm_start = (
        "adb",
        "-s",
        SERIAL,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "com.mobilefork.hermesagent/.MainActivity",
    )
    assert warm_start not in executor.calls


def test_warm_launch_rejects_changed_process_pid_before_relaunch(collector_module):
    executor = FixtureExecutor(collector_module)
    executor.process_pid_after_back = 8124

    with pytest.raises(collector_module.CollectorError, match="PID changed after KEYCODE_BACK"):
        _collect(collector_module, executor=executor)

    warm_start = (
        "adb",
        "-s",
        SERIAL,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "com.mobilefork.hermesagent/.MainActivity",
    )
    assert warm_start not in executor.calls


def test_fewer_than_one_hundred_frames_fails_closed(collector_module):
    executor = FixtureExecutor(collector_module, gfx_frames=99, gfx_jank_percent="6.06")
    with pytest.raises(collector_module.CollectorError, match="only 99 frames"):
        _collect(collector_module, executor=executor)


def test_gfxinfo_inconsistent_printed_jank_percentage_fails_closed(collector_module):
    executor = FixtureExecutor(collector_module, gfx_jank_percent="50.00")
    with pytest.raises(collector_module.CollectorError, match="jank percentage disagrees"):
        _collect(collector_module, executor=executor)


class RejectingValidator:
    def __init__(self, module):
        self.module = module

    def validate(self, path, raw_path, config):
        del path, raw_path, config
        raise self.module.CollectorError("synthetic schema rejection")


def test_atomic_writer_preserves_existing_file_when_validation_fails(collector_module, tmp_path):
    performance_dir = tmp_path / "performance"
    performance_dir.mkdir()
    destination = performance_dir / "phone-compact.json"
    raw_destination = performance_dir / "phone-compact.raw.json"
    destination.write_text("existing\n", encoding="utf-8")
    raw_destination.write_text("existing raw\n", encoding="utf-8")
    config = _config(collector_module)
    payload, _, _, raw_transcript = _collect(collector_module)

    with pytest.raises(collector_module.CollectorError, match="synthetic schema rejection"):
        collector_module.write_atomic_validated_evidence(
            destination,
            payload,
            raw_transcript,
            config,
            RejectingValidator(collector_module),
            StaticSourceVerifier(collector_module),
            overwrite=True,
        )

    assert destination.read_text(encoding="utf-8") == "existing\n"
    assert raw_destination.read_text(encoding="utf-8") == "existing raw\n"
    assert list(performance_dir.glob(".*.tmp")) == []


def test_atomic_writer_commits_only_current_validator_accepted_payload(
    collector_module, tmp_path
):
    payload, _, _, raw_transcript = _collect(collector_module)
    performance_dir = tmp_path / "performance"
    performance_dir.mkdir()
    destination = performance_dir / "phone-compact.json"
    writer_verifier = StaticSourceVerifier(collector_module)

    collector_module.write_atomic_validated_evidence(
        destination,
        payload,
        raw_transcript,
        _config(collector_module),
        collector_module.ReleaseEvidencePayloadValidator(),
        writer_verifier,
        overwrite=False,
    )

    assert json.loads(destination.read_text(encoding="utf-8")) == payload
    raw_path = performance_dir / "phone-compact.raw.json"
    assert json.loads(raw_path.read_text(encoding="utf-8")) == raw_transcript
    assert list(performance_dir.glob(".*.tmp")) == []
    assert writer_verifier.seen == [SOURCE_DIGEST, SOURCE_DIGEST, SOURCE_DIGEST]


class FailingNthSourceVerifier(StaticSourceVerifier):
    def __init__(self, module, fail_at: int):
        super().__init__(module)
        self.fail_at = fail_at

    def verify(self, expected_digest):
        identity = super().verify(expected_digest)
        if len(self.seen) == self.fail_at:
            raise self.module.CollectorError("synthetic source drift during evidence write")
        return identity


@pytest.mark.parametrize("fail_at", (2, 3))
def test_atomic_writer_rolls_back_pair_when_source_drifts_during_commit(
    collector_module, tmp_path, fail_at
):
    payload, _, _, raw_transcript = _collect(collector_module)
    performance_dir = tmp_path / "performance"
    performance_dir.mkdir()
    destination = performance_dir / "phone-compact.json"
    raw_destination = performance_dir / "phone-compact.raw.json"
    destination.write_bytes(b"prior normalized\n")
    raw_destination.write_bytes(b"prior raw\n")
    verifier = FailingNthSourceVerifier(collector_module, fail_at)

    with pytest.raises(collector_module.CollectorError, match="source drift"):
        collector_module.write_atomic_validated_evidence(
            destination,
            payload,
            raw_transcript,
            _config(collector_module),
            collector_module.ReleaseEvidencePayloadValidator(),
            verifier,
            overwrite=True,
        )

    assert destination.read_bytes() == b"prior normalized\n"
    assert raw_destination.read_bytes() == b"prior raw\n"
    assert len(verifier.seen) == fail_at
    assert list(performance_dir.glob(".*.tmp")) == []


@pytest.mark.parametrize("fail_at", (2, 3))
def test_atomic_writer_removes_new_pair_when_source_drifts_during_commit(
    collector_module, tmp_path, fail_at
):
    payload, _, _, raw_transcript = _collect(collector_module)
    performance_dir = tmp_path / "performance"
    performance_dir.mkdir()
    destination = performance_dir / "phone-compact.json"
    raw_destination = performance_dir / "phone-compact.raw.json"
    verifier = FailingNthSourceVerifier(collector_module, fail_at)

    with pytest.raises(collector_module.CollectorError, match="source drift"):
        collector_module.write_atomic_validated_evidence(
            destination,
            payload,
            raw_transcript,
            _config(collector_module),
            collector_module.ReleaseEvidencePayloadValidator(),
            verifier,
            overwrite=False,
        )

    assert not destination.exists()
    assert not raw_destination.exists()
    assert len(verifier.seen) == fail_at
    assert list(performance_dir.glob(".*.tmp")) == []


class CimFixtureExecutor:
    def __init__(self, module, stdout):
        self.module = module
        self.stdout = stdout
        self.calls = []

    def run(self, args, *, timeout_seconds):
        self.calls.append((tuple(args), timeout_seconds))
        return self.module.CommandResult(tuple(args), 0, self.stdout, "")


def test_cim_process_source_parses_actual_pid_name_and_command_line(collector_module):
    executor = CimFixtureExecutor(
        collector_module,
        json.dumps([{"pid": 4242, "name": "qemu-system-x86_64.exe", "command_line": QEMU_COMMAND}]),
    )
    source = collector_module.WindowsCimProcessSource(executor, "pwsh")

    snapshot = source.qemu_snapshot()
    assert snapshot.processes == (
        collector_module.ProcessInfo(4242, "qemu-system-x86_64.exe", QEMU_COMMAND),
    )
    assert snapshot.query.stdout == executor.stdout
    argv, timeout = executor.calls[0]
    assert argv[:5] == ("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command")
    assert "Win32_Process" in argv[-1]
    assert timeout == 30
