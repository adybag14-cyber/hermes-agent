import json
from pathlib import Path

import pytest

from scripts.report_android_llama_cpp_drift import build_report, main


def _lock(version: str = "0.0.0-b9784-0") -> dict:
    return {
        "termux_main_base_url": "https://packages.termux.dev/apt/termux-main",
        "architectures": {
            "arm64-v8a": {
                "termux_arch": "aarch64",
                "packages": [
                    {
                        "name": "llama-cpp",
                        "version": version,
                        "filename": f"pool/main/l/llama-cpp/llama-cpp_{version}_aarch64.deb",
                        "sha256": "a" * 64,
                    }
                ],
            },
            "x86_64": {
                "termux_arch": "x86_64",
                "packages": [
                    {
                        "name": "llama-cpp",
                        "version": version,
                        "filename": f"pool/main/l/llama-cpp/llama-cpp_{version}_x86_64.deb",
                        "sha256": "b" * 64,
                    }
                ],
            },
        },
    }


def _index(version: str) -> str:
    return "\n".join(
        [
            f'<a href="llama-cpp_{version}_aarch64.deb">arm64</a>',
            f'<a href="llama-cpp_{version}_x86_64.deb">x86</a>',
        ]
    )


def test_llama_cpp_report_keeps_compatibility_authority_separate_from_upstream_drift():
    report = build_report(
        _lock(),
        _index("0.0.0-b10290-0"),
        index_source="fixture-index",
    )

    assert report["status"] == "upstream-drift"
    assert report["upstream_drift"] is True
    assert report["architectures"]["arm64-v8a"]["pinned"] == "0.0.0-b9784-0"
    assert report["architectures"]["x86_64"]["latest_published"] == "0.0.0-b10290-0"
    assert "does not reclassify compatibility" in report["compatibility_authority"]


def test_llama_cpp_report_selects_latest_published_build_per_architecture():
    index = _index("0.0.0-b9784-0") + "\n" + _index("0.0.0-b10290-0")

    report = build_report(_lock("0.0.0-b10290-0"), index, index_source="fixture-index")

    assert report["status"] == "upstream-current"
    assert report["upstream_drift"] is False


def test_llama_cpp_report_rejects_a_lock_without_content_addressing():
    lock = _lock()
    lock["architectures"]["x86_64"]["packages"][0]["sha256"] = "moving"

    with pytest.raises(ValueError, match="exact SHA-256"):
        build_report(lock, _index("0.0.0-b9784-0"), index_source="fixture-index")


def test_llama_cpp_cli_exits_successfully_when_upstream_has_drift(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
):
    lock_file = tmp_path / "termux-lock.json"
    lock_file.write_text(json.dumps(_lock()), encoding="utf-8")
    index_file = tmp_path / "index.html"
    index_file.write_text(_index("0.0.0-b10290-0"), encoding="utf-8")

    main(["--lock-file", str(lock_file), "--index-file", str(index_file)])

    assert json.loads(capsys.readouterr().out)["status"] == "upstream-drift"
