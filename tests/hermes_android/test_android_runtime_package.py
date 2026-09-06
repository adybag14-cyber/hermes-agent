import io
import zipfile

import pytest

from scripts.verify_android_runtime_package import ABI_MACHINES, NATIVE_PROGRAMS, verify


def zip_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def package_entries(lab):
    entries = {}
    for abi, machine in ABI_MACHINES.items():
        header = b"\x7fELF\x02\x01" + bytes(12) + machine.to_bytes(2, "little")
        for program in NATIVE_PROGRAMS:
            entries[f"lib/{abi}/libhermes_android_{program}.so"] = header
        entries[f"assets/hermes-linux/{abi}/manifest.json"] = '{"schema":1}'
        entries[f"assets/chaquopy/requirements-{abi}.imy"] = zip_bytes(
            {f"{name}/native.so": header for name in ("jiter", "pydantic_core")}
            if lab else {"native.so": b"fixture"})
    entries["assets/hermes-experimental-llama/manifest.json"] = '{"schema":1}'
    entries["assets/chaquopy/bootstrap.imy"] = zip_bytes({"bootstrap.pyc": b"fixture"})
    summary = "Genuine SDK" if lab else "Android/Chaquopy placeholder"
    entries["assets/chaquopy/requirements-common.imy"] = zip_bytes({
        f"{name}.dist-info/METADATA": f"Name: {name}\nVersion: 1.0\nSummary: {summary}\n"
        for name in ("anthropic", "fal-client")
    })
    return entries


@pytest.mark.parametrize("lab", [False, True])
def test_package_mode_must_match_embedded_sdk_metadata(tmp_path, lab):
    apk = tmp_path / "candidate.apk"
    apk.write_bytes(zip_bytes(package_entries(lab)))
    assert verify(apk, legacy_stubs=not lab)["python_dependency_mode"] == (
        "genuine-sdks" if lab else "legacy-stubs")
    with pytest.raises(ValueError, match="Wrong Python dependency mode|Missing genuine native"):
        verify(apk, legacy_stubs=lab)


def test_normal_and_lab_builds_both_require_genuine_dependencies(tmp_path):
    apk = tmp_path / "candidate.apk"
    apk.write_bytes(zip_bytes(package_entries(True)))
    assert verify(apk)["python_dependency_mode"] == "genuine-sdks"
    assert verify(apk, chaquopy_lab=True)["python_dependency_mode"] == "genuine-sdks"
    with pytest.raises(ValueError, match="Legacy placeholder"):
        verify(apk, chaquopy_lab=True, legacy_stubs=True)


def test_genuine_mode_checks_native_python_architecture(tmp_path):
    entries = package_entries(True)
    entries["assets/chaquopy/requirements-arm64-v8a.imy"] = entries["assets/chaquopy/requirements-x86_64.imy"]
    apk = tmp_path / "candidate.apk"
    apk.write_bytes(zip_bytes(entries))
    with pytest.raises(ValueError, match="Wrong Python native ELF"):
        verify(apk)


@pytest.mark.parametrize("fault", ["missing-lane", "wrong-abi", "missing-assets"])
def test_runtime_package_rejects_missing_or_miswired_generated_outputs(tmp_path, fault):
    entries = package_entries(False)
    target = "lib/arm64-v8a/libhermes_android_llama_server_experimental.so"
    if fault == "missing-lane":
        del entries[target]
    elif fault == "wrong-abi":
        entries[target] = entries["lib/x86_64/libhermes_android_llama_server_experimental.so"]
    else:
        del entries["assets/hermes-linux/x86_64/manifest.json"]
    apk = tmp_path / "candidate.apk"
    apk.write_bytes(zip_bytes(entries))
    with pytest.raises((ValueError, KeyError)):
        verify(apk)
