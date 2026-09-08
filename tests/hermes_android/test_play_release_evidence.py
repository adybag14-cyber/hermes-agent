import copy
import importlib
import hashlib
import json
import struct
import zlib
from pathlib import Path
from pathlib import PurePosixPath
from types import SimpleNamespace

import pytest


@pytest.fixture
def contract(monkeypatch):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2] / "scripts"))
    gate = importlib.import_module("verify_android_play_release_evidence")
    model = SimpleNamespace(model_id="test-model", file_name="test.gguf", sha256="b" * 64,
                            expected_bytes=1234, repository="publisher/test", revision="c" * 40, runtime="llama.cpp")
    records = {}
    for case in (*gate.CASES, "model-test-model"):
        records[case] = {
            "schema": "hermes-play-release-v1", "case": case, "result": "passed", "edition": "play",
            "source_digest": "a" * 64, "version_name": "0.13.999", "version_code": 99990,
            "package": "com.mobilefork.hermesagent", "build_variant": "playDebug", "android_sdk": 36,
            "page_size": 16384, "candidate_apk_sha256": "d" * 64, "instrumentation_apk_sha256": "e" * 64,
            "evidence_run_id": "play-test-run-0001", "device_boot_id": "11111111-1111-1111-1111-111111111111",
            "device_serial": "emulator-5580", "avd_name": "Test16K", "build_fingerprint": "google/test",
            "recorded_at_epoch_ms": 1, "details": dict.fromkeys(gate.CASES.get(case, gate.MODEL_CHECKS), True),
        }
    records["report-submit-delete"]["details"]["endpoint"] = gate.ENDPOINT
    records["six-language-privacy"]["details"]["languages"] = sorted(gate.LANGUAGES)
    records["model-test-model"]["details"].update(
        model_id=model.model_id, model_file=model.file_name, device_sha256=model.sha256,
        device_bytes=model.expected_bytes, publisher_repository=model.repository, publisher_revision=model.revision,
        backend=model.runtime, elapsed_ms=2000, reply_characters=10,
    )
    return gate, model, records


def test_only_one_complete_source_bound_play_run_can_pass(contract):
    gate, model, records = contract
    assert gate.validate_records(records, "a" * 64, "0.13.999", 99990, [model])["status"] == "passed"
    for key, value in (("source_digest", "f" * 64), ("build_variant", "debug"), ("edition", "full"),
                       ("page_size", 4096), ("candidate_apk_sha256", "f" * 64), ("version_code", 1),
                       ("evidence_run_id", "different-run-0001")):
        changed = copy.deepcopy(records)
        changed["privacy-consent-lifecycle"][key] = value
        with pytest.raises(ValueError):
            gate.validate_records(changed, "a" * 64, "0.13.999", 99990, [model])


def test_missing_behavior_or_different_model_bytes_cannot_be_certified(contract):
    gate, model, records = contract
    for case, checks in {**gate.CASES, "model-test-model": gate.MODEL_CHECKS}.items():
        for check in checks:
            changed = copy.deepcopy(records)
            changed[case]["details"][check] = False
            with pytest.raises(ValueError, match="Missing completed behavior"):
                gate.validate_records(changed, "a" * 64, "0.13.999", 99990, [model])
    for field, value in (("device_sha256", "f" * 64), ("device_bytes", 1235), ("backend", "litert-lm")):
        changed = copy.deepcopy(records)
        changed["model-test-model"]["details"][field] = value
        with pytest.raises(ValueError):
            gate.validate_records(changed, "a" * 64, "0.13.999", 99990, [model])


def test_full_manifest_includes_exact_play_contract_only_from_play_introduction(contract):
    gate, model, _ = contract
    core = importlib.import_module("android_release_evidence")
    model.evidence_path = PurePosixPath("models/test-model.json")
    expected = {PurePosixPath("play") / f"{case}.json" for case in (*gate.CASES, f"model-{model.model_id}")}
    expected.update(PurePosixPath("play") / f"privacy-{language}.png" for language in gate.LANGUAGES)
    for tag in ("v0.13.156", "v0.13.156-rc.1", "v0.13.157"):
        paths = core.expected_evidence_paths([model], tag=tag)
        assert {path for path in paths if path.parts[0] == "play"} == expected
    for tag in (None, "v0.13.147", "v0.13.155"):
        assert not any(path.parts[0] == "play" for path in core.expected_evidence_paths([model], tag=tag))


def test_directory_and_full_manifest_reject_missing_extra_and_stale_play_evidence(contract, tmp_path):
    gate, model, records = contract
    core = importlib.import_module("android_release_evidence")
    model.evidence_path = PurePosixPath("models/test-model.json")
    for record in records.values():
        record.update(version_name="0.13.156", version_code=145690)
    directory = tmp_path / "play"
    directory.mkdir()

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + chunk(b"IEND", b"")
    screenshots = []
    for language in sorted(gate.LANGUAGES):
        name = f"privacy-{language}.png"
        (directory / name).write_bytes(png)
        screenshots.append(dict(file=name, bytes=len(png), sha256=hashlib.sha256(png).hexdigest(), width=1, height=1))
    records["six-language-privacy"]["details"]["screenshots"] = screenshots
    for case, record in records.items():
        (directory / f"{case}.json").write_text(json.dumps(record), encoding="utf-8")
    assert gate.verify_directory(directory, "a" * 64, "0.13.156", 145690, [model])["status"] == "passed"
    extra = directory / "unexpected.txt"
    extra.write_text("not release evidence", encoding="utf-8")
    with pytest.raises(ValueError, match="layout"):
        gate.verify_directory(directory, "a" * 64, "0.13.156", 145690, [model])
    extra.unlink()
    missing = directory / screenshots[0]["file"]
    missing.unlink()
    with pytest.raises(ValueError):
        gate.verify_directory(directory, "a" * 64, "0.13.156", 145690, [model])
    missing.write_bytes(png)
    # Exercise the actual Full entry point: it must reject the Play binding before
    # interpreting unrelated Full records, not merely include unvalidated files.
    for relative in core.expected_evidence_paths([model], tag="v0.13.156"):
        path = tmp_path / relative
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("{}", encoding="utf-8")
    with pytest.raises(core.EvidenceError, match="Stale source digest"):
        core.validate_evidence_directory(tmp_path, [model], "f" * 64, "v0.13.156")
