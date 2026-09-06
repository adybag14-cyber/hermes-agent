import copy
import io
import json
from pathlib import Path
import tarfile

import pytest

from scripts import prepare_android_python_runtime as runtime


@pytest.fixture
def inputs(tmp_path):
    requirements = tmp_path / "requirements.txt"
    requirements.write_text("jiter==0.16.0\npydantic-core==2.46.5\nmsgpack==1.2.2\nexample==1.0\n")
    lock = {
        "schema_version": 1, "python": "3.13", "bootstrap_version": "17.0.1",
        "source": {"commit": "a" * 40, "archive_sha256": "b" * 64,
                   "archive_size_bytes": 123,
                   "archive_url": "https://codeload.github.com/adybag14-cyber/chaquopy/tar.gz/" + "a" * 40},
        "official_wheels": [{"filename": "example-1.0-py3-none-any.whl",
                             "bytes": 12, "sha256": "c" * 64}],
    }
    lock_file = tmp_path / "lock.json"
    lock_file.write_text(json.dumps(lock))
    return lock_file, requirements, lock


def make_bundle(tmp_path, lock_file, requirements, lock):
    root = tmp_path / "bundle"
    bootstrap = root / "maven/com/chaquo/python/runtime/bootstrap/17.0.1/bootstrap-17.0.1-3.13.imy"
    bootstrap.parent.mkdir(parents=True)
    bootstrap.write_bytes(b"fixture bootstrap")
    (root / "requirements.txt").write_bytes(requirements.read_bytes())
    receipt = {
        "schema": runtime.SCHEMA, "python": "3.13", "bootstrap_version": "17.0.1",
        "source_lock_sha256": runtime.digest(lock_file),
        "hermes_requirements_sha256": runtime.digest(requirements),
        "fork_commit": lock["source"]["commit"], "runtime_tested": False,
        "bootstrap_sha256": runtime.digest(bootstrap), "files": runtime.inventory(root),
    }
    (root / "consumer.json").write_text(json.dumps(receipt))
    return root, receipt


def test_committed_requirements_have_complete_official_hash_coverage():
    lock = runtime.load_lock()
    selected = runtime.pins(runtime.REQUIREMENTS.read_text())
    assert runtime.CUSTOM <= set(selected)
    text = runtime.official_requirements(lock, runtime.REQUIREMENTS.read_text())
    lines = text.splitlines()
    assert {line.split("==")[0] for line in lines} == set(selected) - runtime.CUSTOM
    assert all(" --hash=sha256:" in line for line in lines)


@pytest.mark.parametrize("text", ["example>=1", "example==1\nExample==2", "-r other.txt", "thing @ https://a/b"])
def test_ambiguous_or_unbounded_requirements_rejected(text):
    with pytest.raises(ValueError, match="exact"):
        runtime.pins(text)


@pytest.mark.parametrize("mutation", [
    lambda lock: lock["source"].update(commit="main"),
    lambda lock: lock["source"].update(archive_url="https://untrusted.example/source.tar.gz"),
    lambda lock: lock["source"].update(archive_sha256="bad"),
    lambda lock: lock["official_wheels"].append(copy.deepcopy(lock["official_wheels"][0])),
    lambda lock: lock["official_wheels"][0].update(filename="../example-1.0-py3-none-any.whl"),
    lambda lock: lock["official_wheels"][0].update(filename="example-2.0-py3-none-any.whl"),
    lambda lock: lock.update(official_wheels=[]),
])
def test_source_or_wheel_lock_rejects_ambiguous_inputs(inputs, mutation):
    lock_file, requirements, lock = inputs
    mutation(lock)
    lock_file.write_text(json.dumps(lock))
    with pytest.raises(ValueError):
        runtime.load_lock(lock_file, requirements)


def test_verified_bundle_is_build_evidence_not_runtime_evidence(tmp_path, inputs):
    lock_file, requirements, lock = inputs
    root, receipt = make_bundle(tmp_path, lock_file, requirements, lock)
    assert runtime.verify(root, lock_file=lock_file, requirements=requirements) == receipt
    assert receipt["runtime_tested"] is False


@pytest.mark.parametrize("mutation", [
    lambda root: (root / "unrecorded.txt").write_text("extra"),
    lambda root: (root / "requirements.txt").write_text("changed"),
    lambda root: (root / "requirements.txt").unlink(),
    lambda root: (root / "nested").mkdir(),
])
def test_closed_inventory_detects_changes(tmp_path, inputs, mutation):
    lock_file, requirements, lock = inputs
    root, _ = make_bundle(tmp_path, lock_file, requirements, lock)
    mutation(root)
    # Nested consumer.json must not be hidden by the receipt exclusion.
    if (root / "nested").is_dir():
        (root / "nested/consumer.json").write_text("unexpected")
    with pytest.raises(ValueError, match="closed inventory"):
        runtime.verify(root, lock_file=lock_file, requirements=requirements)


def test_prepared_bundle_cannot_claim_device_execution(tmp_path, inputs):
    lock_file, requirements, lock = inputs
    root, receipt = make_bundle(tmp_path, lock_file, requirements, lock)
    receipt["runtime_tested"] = True
    (root / "consumer.json").write_text(json.dumps(receipt))
    with pytest.raises(ValueError):
        runtime.verify(root, lock_file=lock_file, requirements=requirements)


def make_tar(path, members):
    with tarfile.open(path, "w") as archive:
        for name, data, kind in members:
            item = tarfile.TarInfo(name)
            item.type = kind
            item.size = len(data) if kind == tarfile.REGTYPE else 0
            item.linkname = "/outside" if kind == tarfile.SYMTYPE else ""
            archive.addfile(item, io.BytesIO(data) if item.isfile() else None)


def test_source_extraction_selects_only_required_regular_sources(tmp_path):
    archive = tmp_path / "source.tar"
    make_tar(archive, [("chaquopy-sha/compat/hermes/build_native.py", b"source", tarfile.REGTYPE),
                       ("chaquopy-sha/elsewhere/unused", b"ignored", tarfile.REGTYPE)])
    output = tmp_path / "output"
    runtime.extract_source(archive, output, "chaquopy-sha")
    assert (output / "compat/hermes/build_native.py").read_bytes() == b"source"
    assert not (output / "elsewhere").exists()


@pytest.mark.parametrize("name,kind", [
    ("chaquopy-sha/../escape", tarfile.REGTYPE),
    ("/absolute", tarfile.REGTYPE),
    ("different-prefix/file", tarfile.REGTYPE),
    ("chaquopy-sha/compat/hermes/linked.py", tarfile.SYMTYPE),
])
def test_unsafe_source_archive_entries_rejected(tmp_path, name, kind):
    archive = tmp_path / "source.tar"
    make_tar(archive, [(name, b"value", kind)])
    with pytest.raises(ValueError):
        runtime.extract_source(archive, tmp_path / "output", "chaquopy-sha")
