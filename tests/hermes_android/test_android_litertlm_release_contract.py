"""Keep new SDK evidence bound to its release without rewriting past records."""
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from scripts import android_collect_performance_evidence as collector
from scripts import android_release_evidence as evidence


@pytest.mark.parametrize("tag,version", [
    ("v0.13.147", "0.16.0"),
    ("v0.13.148", "0.16.1"),
    ("v0.13.153", "0.16.1"),
    ("v0.13.154", "0.17.1"),
    ("v0.13.154-rc.1", "0.17.1"),
])
def test_release_coordinate_preserves_historical_contracts(tag, version):
    assert evidence.litertlm_coordinate_for_tag(tag) == (
        f"com.google.ai.edge.litertlm:litertlm-android:{version}"
    )


def _config(tmp_path, *, version_name="0.13.154", sdk_version="0.17.1"):
    report = tmp_path / "report.json"
    invocation = tmp_path / "invocation.json"
    report.write_text("{}")
    invocation.write_text("{}")
    traces = []
    for index in range(5):
        trace = tmp_path / f"iteration-{index}.perfetto-trace"
        trace.write_bytes(b"unit-test-input")
        traces.append(trace)
    return collector.CollectorConfig(
        serial="emulator-5566", profile="phone-compact",
        expected_avd_name="Hermes_API_35",
        expected_boot_id="12345678-1234-4abc-8def-1234567890ab",
        release_source_digest="d" * 64,
        benchmark_target_apk_sha256="c" * 64,
        benchmark_test_apk_sha256="e" * 64,
        evidence_run_id="release-v0.13.154-unit-test",
        version_name=version_name, version_code=150890,
        litertlm_coordinate=f"com.google.ai.edge.litertlm:litertlm-android:{sdk_version}",
        macrobenchmark_report=report, macrobenchmark_traces=tuple(traces),
        macrobenchmark_invocation=invocation,
    )


@pytest.mark.parametrize("version_name,sdk_version,accepted", [
    ("0.13.148", "0.16.1", True),
    ("0.13.154", "0.17.1", True),
    ("0.13.148", "0.17.1", False),
    ("0.13.154", "0.16.1", False),
])
def test_collector_requires_the_sdk_for_the_release(tmp_path, version_name, sdk_version, accepted):
    config = _config(tmp_path, version_name=version_name, sdk_version=sdk_version)
    if accepted:
        assert config.validate() == 5566
    else:
        with pytest.raises(collector.CollectorError, match="release dependency"):
            config.validate()


def test_payload_validator_uses_the_collected_sdk_coordinate(tmp_path, monkeypatch):
    config = _config(tmp_path)
    validate = Mock()
    monkeypatch.setattr(collector, "_load_release_evidence_module",
                        lambda: SimpleNamespace(_validate_performance=validate))
    collector.ReleaseEvidencePayloadValidator().validate(
        tmp_path / "payload.json", tmp_path / "host.json", tmp_path / "raw.json",
        config.macrobenchmark_traces, config,
    )
    assert validate.call_args.kwargs["litertlm_coordinate"] == config.litertlm_coordinate
