import importlib.util
import sys
import zipfile
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
EXPECTED_DIGEST = "a" * 64


def _load_verifier():
    script = REPO_ROOT / "scripts/verify_android_source_bound_artifact.py"
    spec = importlib.util.spec_from_file_location("verify_android_source_bound_artifact", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _write_artifact(path: Path, dex_name: str, payload: bytes) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(dex_name, payload)


@pytest.mark.parametrize(
    ("suffix", "dex_name"),
    [(".apk", "classes.dex"), (".aab", "base/dex/classes.dex")],
)
def test_apk_and_aab_require_the_exact_bound_digest(tmp_path, suffix, dex_name):
    verifier = _load_verifier()
    artifact = tmp_path / f"candidate{suffix}"
    _write_artifact(artifact, dex_name, b"prefix" + EXPECTED_DIGEST.encode() + b"suffix")

    assert verifier.verify_source_binding(artifact, EXPECTED_DIGEST) == [dex_name]
    with pytest.raises(verifier.SourceBindingError, match="does not embed expected"):
        verifier.verify_source_binding(artifact, "b" * 64)


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (b"release-unbound", "expected source digest"),
        ((EXPECTED_DIGEST + "-unbound").encode(), "unbound source identity"),
    ],
)
def test_release_artifact_rejects_missing_or_unbound_identity(tmp_path, payload, message):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    _write_artifact(artifact, "classes.dex", payload)

    with pytest.raises(verifier.SourceBindingError, match=message):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)
