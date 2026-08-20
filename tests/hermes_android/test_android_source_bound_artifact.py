import hashlib
import importlib.util
import struct
import sys
import zipfile
import zlib
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


def _uleb128(value: int) -> bytes:
    encoded = bytearray()
    while True:
        current = value & 0x7F
        value >>= 7
        encoded.append(current | (0x80 if value else 0))
        if not value:
            return bytes(encoded)


def _dex_with_strings(*values: str) -> bytes:
    values = tuple(sorted(values))
    header_size = 0x70
    string_ids_offset = header_size
    string_data_offset = string_ids_offset + len(values) * 4
    data = bytearray()
    offsets: list[int] = []
    for value in values:
        encoded = value.encode("utf-8")
        offsets.append(string_data_offset + len(data))
        data.extend(_uleb128(len(value)))
        data.extend(encoded)
        data.append(0)

    while (string_data_offset + len(data)) % 4:
        data.append(0)
    map_offset = string_data_offset + len(data)
    map_items = (
        (0x0000, 1, 0),
        (0x0001, len(values), string_ids_offset),
        (0x2002, len(values), offsets[0]),
        (0x1000, 1, map_offset),
    )
    data.extend(struct.pack("<I", len(map_items)))
    for item_type, item_count, item_offset in map_items:
        data.extend(struct.pack("<HHII", item_type, 0, item_count, item_offset))

    file_size = header_size + len(values) * 4 + len(data)
    header = bytearray(header_size)
    header[:8] = b"dex\n035\0"
    struct.pack_into("<III", header, 0x20, file_size, header_size, 0x12345678)
    struct.pack_into("<I", header, 0x34, map_offset)
    struct.pack_into("<II", header, 0x38, len(values), string_ids_offset)
    struct.pack_into("<II", header, 0x68, len(data), string_data_offset)
    payload = header + b"".join(struct.pack("<I", offset) for offset in offsets) + data
    payload[12:32] = hashlib.sha1(payload[32:]).digest()  # noqa: S324 - DEX format
    struct.pack_into("<I", payload, 8, zlib.adler32(payload[12:]) & 0xFFFFFFFF)
    return bytes(payload)


def _resign_dex(payload: bytearray) -> None:
    payload[12:32] = hashlib.sha1(payload[32:]).digest()  # noqa: S324 - DEX format
    struct.pack_into("<I", payload, 8, zlib.adler32(payload[12:]) & 0xFFFFFFFF)


def _write_artifact(path: Path, dex_name: str, *strings: str) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(dex_name, _dex_with_strings(*strings))


@pytest.mark.parametrize(
    ("suffix", "dex_name"),
    [(".apk", "classes.dex"), (".aab", "base/dex/classes.dex")],
)
def test_apk_and_aab_require_the_exact_bound_digest(tmp_path, suffix, dex_name):
    verifier = _load_verifier()
    artifact = tmp_path / f"candidate{suffix}"
    _write_artifact(artifact, dex_name, EXPECTED_DIGEST, "unbound", "unbounded = true")

    assert verifier.verify_source_binding(artifact, EXPECTED_DIGEST) == [dex_name]
    with pytest.raises(verifier.SourceBindingError, match="does not embed expected"):
        verifier.verify_source_binding(artifact, "b" * 64)


@pytest.mark.parametrize(
    ("strings", "message"),
    [
        (("release-unbound",), "expected source digest"),
        ((EXPECTED_DIGEST, "hermes-source-unbound"), "unbound source identity"),
    ],
)
def test_release_artifact_rejects_missing_or_unbound_identity(tmp_path, strings, message):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    _write_artifact(artifact, "classes.dex", *strings)

    with pytest.raises(verifier.SourceBindingError, match=message):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_rejects_malformed_dex(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("classes.dex", b"not-a-dex")

    with pytest.raises(verifier.SourceBindingError, match="Invalid DEX header"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_rejects_structurally_fake_dex(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    payload = bytearray(0x70 + 4 + 1 + len(EXPECTED_DIGEST) + 1)
    payload[:8] = b"dex\n035\0"
    struct.pack_into("<II", payload, 0x38, 1, 0x70)
    struct.pack_into("<I", payload, 0x70, 0x74)
    payload[0x74] = len(EXPECTED_DIGEST)
    payload[0x75 : 0x75 + len(EXPECTED_DIGEST)] = EXPECTED_DIGEST.encode()
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("classes.dex", payload)

    with pytest.raises(verifier.SourceBindingError, match="structural header"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_rejects_unknown_dex_version(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    payload = bytearray(_dex_with_strings(EXPECTED_DIGEST))
    payload[:8] = b"dex\n999\0"
    _resign_dex(payload)
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("classes.dex", payload)

    with pytest.raises(verifier.SourceBindingError, match="Invalid DEX header"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_checks_every_standard_multidex_entry(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("classes.dex", _dex_with_strings(EXPECTED_DIGEST))
        for index in range(2, 10):
            archive.writestr(
                f"classes{index}.dex", _dex_with_strings(f"ordinary dependency string {index}")
            )
        archive.writestr("classes10.dex", _dex_with_strings("hermes-source-unbound"))

    with pytest.raises(verifier.SourceBindingError, match="unbound source identity"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


@pytest.mark.parametrize(
    ("suffix", "wrong_dex_name"),
    [(".apk", "base/dex/classes.dex"), (".aab", "classes.dex")],
)
def test_release_artifact_rejects_dex_from_the_wrong_archive_layout(
    tmp_path, suffix, wrong_dex_name
):
    verifier = _load_verifier()
    artifact = tmp_path / f"candidate{suffix}"
    _write_artifact(artifact, wrong_dex_name, EXPECTED_DIGEST)

    with pytest.raises(verifier.SourceBindingError, match="contains no application DEX"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_rejects_multidex_gaps(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("classes.dex", _dex_with_strings(EXPECTED_DIGEST))
        archive.writestr("classes3.dex", _dex_with_strings("ordinary dependency string"))

    with pytest.raises(verifier.SourceBindingError, match="sequence is not contiguous"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_rejects_wrong_declared_mutf8_length(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    payload = bytearray(_dex_with_strings(EXPECTED_DIGEST))
    string_ids_offset = struct.unpack_from("<I", payload, 0x3C)[0]
    string_data_offset = struct.unpack_from("<I", payload, string_ids_offset)[0]
    payload[string_data_offset] = len(EXPECTED_DIGEST) - 1
    _resign_dex(payload)
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("classes.dex", payload)

    with pytest.raises(verifier.SourceBindingError, match="longer than declared"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)


def test_release_artifact_wraps_invalid_zip(tmp_path):
    verifier = _load_verifier()
    artifact = tmp_path / "candidate.apk"
    artifact.write_bytes(b"not a zip")

    with pytest.raises(verifier.SourceBindingError, match="Unable to inspect"):
        verifier.verify_source_binding(artifact, EXPECTED_DIGEST)
