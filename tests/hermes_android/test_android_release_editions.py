import hashlib
import importlib.util
from pathlib import Path

import pytest


def _module():
    path = Path(__file__).resolve().parents[2] / "scripts/android_release_manifest.py"
    spec = importlib.util.spec_from_file_location("edition_manifest", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_historical_editions_coexist_and_checksums_identify_their_own_bytes(tmp_path):
    manifest = _module()
    outputs = {}
    for edition in ("full", "play"):
        source = tmp_path / edition
        source.mkdir()
        (source / "app-release.apk").write_bytes(f"{edition} apk".encode())
        (source / "app-release.aab").write_bytes(f"{edition} aab".encode())
        outputs[edition] = manifest.stage_release_artifacts(
            "v0.13.161", edition, source, source, tmp_path / "dist",
        )
    assert set(outputs["full"]).isdisjoint(outputs["play"])
    for edition, artifacts in outputs.items():
        for artifact in artifacts:
            assert artifact.read_bytes() == f"{edition} {artifact.suffix[1:]}".encode()
            assert artifact.with_suffix(artifact.suffix + ".sha256").read_text() == (
                f"{hashlib.sha256(artifact.read_bytes()).hexdigest()}  {artifact.name}\n"
            )


def test_unknown_edition_does_not_create_artifacts(tmp_path):
    with pytest.raises(ValueError, match="Unknown Android edition"):
        _module().stage_release_artifacts("v0.13.999", "typo", tmp_path, tmp_path, tmp_path / "out")
    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("tag", ["v0.13.162", "v0.13.162-rc.1", "v1.0.0"])
def test_deprecated_play_release_cannot_stage_artifacts(tmp_path, tag):
    with pytest.raises(ValueError, match="Play edition is deprecated"):
        _module().stage_release_artifacts(tag, "play", tmp_path, tmp_path, tmp_path / "out")
    assert not (tmp_path / "out").exists()


def test_full_only_staging_rejects_leftover_play_assets(tmp_path):
    output = tmp_path / "dist"
    output.mkdir()
    old = output / "hermes-agent-android-play-v0.13.161.aab"
    old.write_bytes(b"historical artifact fixture")
    with pytest.raises(ValueError, match="deprecated Play artifacts"):
        _module().stage_release_artifacts("v0.13.162", "full", tmp_path, tmp_path, output)
    assert old.read_bytes() == b"historical artifact fixture"
    assert list(output.iterdir()) == [old]
