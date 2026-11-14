import copy
import io
import json
import shutil
import sys
import zipfile

import pytest

from scripts import prepare_android_python_runtime as runtime
from scripts import refresh_android_python_pure_wheels as refresh


def fixture_wheel(version):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("example/__init__.py", f"VERSION = {version!r}\n")
        archive.writestr(f"example-{version}.dist-info/WHEEL", "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n")
    return data.getvalue()


@pytest.fixture
def inputs(tmp_path):
    import hashlib
    root = tmp_path / "bundle"
    (root / "wheels").mkdir(parents=True)
    original = fixture_wheel("1.0")
    replacement = fixture_wheel("1.1")
    wheel = {"filename": "example-1.0-py3-none-any.whl", "bytes": len(original),
             "sha256": hashlib.sha256(original).hexdigest()}
    new_wheel = {"filename": "example-1.1-py3-none-any.whl", "bytes": len(replacement),
                 "sha256": hashlib.sha256(replacement).hexdigest()}
    old = {"schema_version": 1, "python": "3.13", "bootstrap_version": "17.2.0",
           "source": {"commit": "a" * 40, "archive_sha256": "b" * 64, "archive_size_bytes": 123,
                      "archive_url": "https://codeload.github.com/adybag14-cyber/chaquopy/tar.gz/" + "a" * 40},
           "official_wheels": [wheel]}
    new = copy.deepcopy(old)
    new["official_wheels"] = [new_wheel]
    req = "jiter==0.16.0\npydantic-core==2.46.5\nmsgpack==1.2.2\nexample==1.0\n"
    previous_lock = tmp_path / "old.json"
    previous_lock.write_text(json.dumps(old))
    previous_requirements = tmp_path / "old.txt"
    previous_requirements.write_text(req)
    next_lock = tmp_path / "new.json"
    next_lock.write_text(json.dumps(new))
    next_requirements = tmp_path / "new.txt"
    next_requirements.write_text(req.replace("example==1.0", "example==1.1"))
    bootstrap = root / "maven/com/chaquo/python/runtime/bootstrap/17.2.0/bootstrap-17.2.0-3.13.imy"
    bootstrap.parent.mkdir(parents=True)
    bootstrap.write_bytes(b"native bootstrap fixture; must be preserved")
    (root / "wheels" / wheel["filename"]).write_bytes(original)
    shutil.copyfile(previous_requirements, root / "requirements.txt")
    receipt = {"schema": runtime.SCHEMA, "python": "3.13", "bootstrap_version": "17.2.0",
               "source_lock_sha256": runtime.digest(previous_lock),
               "hermes_requirements_sha256": runtime.digest(previous_requirements),
               "fork_commit": "a" * 40, "runtime_tested": False,
               "bootstrap_sha256": runtime.digest(bootstrap), "files": runtime.inventory(root)}
    (root / "consumer.json").write_text(json.dumps(receipt))
    helpers = tmp_path / "helpers"
    helpers.mkdir()
    (helpers / "audit_wheels.py").write_text("# existing helper fixture\n")
    return root, previous_lock, previous_requirements, next_lock, next_requirements, helpers, replacement


@pytest.mark.parametrize("mutation", ["native-source", "native-wheel", "source-package", "changed-package-set"])
def test_refresh_rejects_changes_outside_official_pure_python_scope(inputs, mutation):
    _, old_file, old_req, new_file, new_req, _, _ = inputs
    old, new = json.loads(old_file.read_text()), json.loads(new_file.read_text())
    op, np = runtime.pins(old_req.read_text()), runtime.pins(new_req.read_text())
    if mutation == "native-source":
        new["source"]["commit"] = "c" * 40
    elif mutation == "native-wheel":
        new["official_wheels"][0]["filename"] = "example-1.1-cp313-cp313-android_24_x86_64.whl"
    elif mutation == "source-package":
        np["jiter"] = "0.17.0"
    else:
        np["unreviewed-package"] = "1.0"
    with pytest.raises(ValueError):
        refresh.pure_wheel_changes(old, new, op, np)


@pytest.mark.parametrize("fail_closure", [False, True])
def test_pure_refresh_preserves_old_bundle_and_requires_both_abi_closures(inputs, tmp_path, monkeypatch, fail_closure):
    from pathlib import Path
    root, old_lock, old_req, new_lock, new_req, helpers, replacement = inputs
    before = {p.relative_to(root): p.read_bytes() for p in root.rglob("*") if p.is_file()}
    commands = []
    monkeypatch.setattr(refresh, "fetch_official_wheel", lambda entry, path: path.write_bytes(replacement))
    def validate(command, **kwargs):
        commands.append(list(map(str, command)))
        if fail_closure:
            raise ValueError("Controlled dependency incompatibility")
    monkeypatch.setattr(runtime, "run", validate)
    work = tmp_path / "results"
    args = (root, work, old_lock, old_req, Path(sys.executable), helpers)
    if fail_closure:
        with pytest.raises(ValueError, match="dependency incompatibility"):
            refresh.refresh(*args, lock_file=new_lock, requirements=new_req)
        assert before == {p.relative_to(root): p.read_bytes() for p in root.rglob("*") if p.is_file()}
        return
    result = refresh.refresh(*args, lock_file=new_lock, requirements=new_req)
    assert not result["runtime_tested"]
    assert runtime.verify(root, lock_file=new_lock, requirements=new_req) == result
    backup = work / "previous-bundle"
    assert before == {p.relative_to(backup): p.read_bytes() for p in backup.rglob("*") if p.is_file()}
    assert len(commands) == 4
    assert {c[c.index("--abi") + 1] for c in commands if "--helpers" in c} == {"arm64-v8a", "x86_64"}
    assert (root / "wheels/example-1.1-py3-none-any.whl").read_bytes() == replacement
    assert not (root / "wheels/example-1.0-py3-none-any.whl").exists()
