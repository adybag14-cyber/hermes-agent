#!/usr/bin/env python3
"""Verify that an Android APK/AAB embeds one exact committed source digest."""

from __future__ import annotations

import argparse
import hashlib
import re
import struct
import zipfile
import zlib
from pathlib import Path


SHA256_RE = re.compile(r"[0-9a-f]{64}")
SUPPORTED_DEX_MAGICS = {
    b"dex\n035\0",
    b"dex\n037\0",
    b"dex\n038\0",
    b"dex\n039\0",
    b"dex\n040\0",
}
UNBOUND_SOURCE_SENTINEL = b"hermes-source-unbound"


class SourceBindingError(RuntimeError):
    """Raised when a release artifact does not carry the expected binding."""


def _dex_entry_names(archive: zipfile.ZipFile, artifact: Path) -> list[str]:
    prefix_by_suffix = {".apk": "classes", ".aab": "base/dex/classes"}
    prefix = prefix_by_suffix.get(artifact.suffix.lower())
    if prefix is None:
        raise SourceBindingError("Android artifact must be an APK or AAB")
    pattern = re.compile(rf"{re.escape(prefix)}(?:([2-9]|[1-9][0-9]+))?\.dex")
    indexed_names = [
        (int(match.group(1)) if match.group(1) else 1, name)
        for name in archive.namelist()
        if (match := pattern.fullmatch(name)) is not None
    ]
    names = [name for _, name in indexed_names]
    if len(names) != len(set(names)):
        raise SourceBindingError("Android artifact contains duplicate application DEX entries")
    indexed_names.sort()
    indices = [index for index, _ in indexed_names]
    if indices and indices != list(range(1, indices[-1] + 1)):
        raise SourceBindingError("Android artifact application DEX sequence is not contiguous")
    return [name for _, name in indexed_names]


def _read_uleb128(payload: bytes, offset: int, context: str) -> tuple[int, int]:
    value = 0
    for index in range(5):
        if offset >= len(payload):
            raise SourceBindingError(f"Truncated DEX string length in {context}")
        current = payload[offset]
        offset += 1
        if index == 4 and current & 0xF0:
            raise SourceBindingError(f"Invalid DEX string length in {context}")
        value |= (current & 0x7F) << (index * 7)
        if current & 0x80 == 0:
            return value, offset
    raise SourceBindingError(f"Invalid DEX string length in {context}")


def _read_mutf8(
    payload: bytes,
    offset: int,
    utf16_size: int,
    context: str,
) -> bytes:
    start = offset
    code_units = 0
    while code_units < utf16_size:
        if offset >= len(payload):
            raise SourceBindingError(f"Truncated DEX string in {context}")
        first = payload[offset]
        if first == 0:
            raise SourceBindingError(f"DEX string is shorter than declared in {context}")
        if first < 0x80:
            offset += 1
        elif first & 0xE0 == 0xC0:
            if offset + 1 >= len(payload):
                raise SourceBindingError(f"Truncated DEX MUTF-8 string in {context}")
            second = payload[offset + 1]
            code_point = ((first & 0x1F) << 6) | (second & 0x3F)
            if second & 0xC0 != 0x80 or (code_point < 0x80 and code_point != 0):
                raise SourceBindingError(f"Invalid DEX MUTF-8 string in {context}")
            offset += 2
        elif first & 0xF0 == 0xE0:
            if offset + 2 >= len(payload):
                raise SourceBindingError(f"Truncated DEX MUTF-8 string in {context}")
            second, third = payload[offset + 1 : offset + 3]
            code_point = ((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F)
            if second & 0xC0 != 0x80 or third & 0xC0 != 0x80 or code_point < 0x800:
                raise SourceBindingError(f"Invalid DEX MUTF-8 string in {context}")
            offset += 3
        else:
            raise SourceBindingError(f"Invalid DEX MUTF-8 string in {context}")
        code_units += 1
    if offset >= len(payload) or payload[offset] != 0:
        raise SourceBindingError(f"DEX string is longer than declared in {context}")
    return payload[start:offset]


def _dex_string_values(payload: bytes, context: str) -> set[bytes]:
    # DEX stores string constants in an indexed string_data table. Parsing the
    # table avoids treating unrelated values such as Compose's "unbounded" as
    # the namespaced source-identity sentinel.
    if len(payload) < 0x70 or payload[:8] not in SUPPORTED_DEX_MAGICS:
        raise SourceBindingError(f"Invalid DEX header in {context}")

    file_size, header_size, endian_tag = struct.unpack_from("<III", payload, 0x20)
    map_offset = struct.unpack_from("<I", payload, 0x34)[0]
    string_count, string_ids_offset = struct.unpack_from("<II", payload, 0x38)
    data_size, data_offset = struct.unpack_from("<II", payload, 0x68)
    if (
        file_size != len(payload)
        or header_size != 0x70
        or endian_tag != 0x12345678
        or data_offset < 0x70
        or data_offset + data_size != len(payload)
        or map_offset < data_offset
        or map_offset % 4 != 0
        or map_offset + 4 > len(payload)
    ):
        raise SourceBindingError(f"Invalid DEX structural header in {context}")
    if payload[12:32] != hashlib.sha1(payload[32:]).digest():  # noqa: S324 - DEX format
        raise SourceBindingError(f"Invalid DEX signature in {context}")
    if struct.unpack_from("<I", payload, 8)[0] != zlib.adler32(payload[12:]) & 0xFFFFFFFF:
        raise SourceBindingError(f"Invalid DEX checksum in {context}")

    table_end = string_ids_offset + string_count * 4
    if string_count == 0 or string_ids_offset < 0x70 or table_end > data_offset:
        raise SourceBindingError(f"Invalid DEX string table in {context}")

    map_count = struct.unpack_from("<I", payload, map_offset)[0]
    map_end = map_offset + 4 + map_count * 12
    if map_count == 0 or map_end > len(payload):
        raise SourceBindingError(f"Invalid DEX map in {context}")
    map_items: dict[int, tuple[int, int]] = {}
    map_item_offsets: list[int] = []
    for index in range(map_count):
        item_type, unused, item_count, item_offset = struct.unpack_from(
            "<HHII", payload, map_offset + 4 + index * 12
        )
        if unused != 0 or item_type in map_items:
            raise SourceBindingError(f"Invalid DEX map item in {context}")
        map_items[item_type] = (item_count, item_offset)
        map_item_offsets.append(item_offset)
    if map_item_offsets != sorted(map_item_offsets):
        raise SourceBindingError(f"Invalid DEX map order in {context}")
    required_map_items = {
        0x0000: (1, 0),
        0x0001: (string_count, string_ids_offset),
        0x1000: (1, map_offset),
        0x2002: (string_count, None),
    }
    for item_type, (expected_count, expected_offset) in required_map_items.items():
        actual = map_items.get(item_type)
        if (
            actual is None
            or actual[0] != expected_count
            or (expected_offset is not None and actual[1] != expected_offset)
        ):
            raise SourceBindingError(f"Invalid DEX map authority in {context}")

    values: set[bytes] = set()
    string_data_offsets: set[int] = set()
    for index in range(string_count):
        (string_data_offset,) = struct.unpack_from(
            "<I", payload, string_ids_offset + index * 4
        )
        if string_data_offset < data_offset or string_data_offset >= len(payload):
            raise SourceBindingError(f"Invalid DEX string offset in {context}")
        if string_data_offset in string_data_offsets:
            raise SourceBindingError(f"Duplicate DEX string offset in {context}")
        string_data_offsets.add(string_data_offset)
        utf16_size, value_offset = _read_uleb128(payload, string_data_offset, context)
        values.add(_read_mutf8(payload, value_offset, utf16_size, context))
    if map_items[0x2002][1] != min(string_data_offsets):
        raise SourceBindingError(f"Invalid DEX string-data map offset in {context}")
    return values


def verify_source_binding(artifact: Path, source_digest: str) -> list[str]:
    normalized_digest = source_digest.strip().lower()
    if not SHA256_RE.fullmatch(normalized_digest):
        raise SourceBindingError("source digest must be one lowercase SHA-256")
    if not artifact.is_file():
        raise SourceBindingError(f"Android artifact does not exist: {artifact}")

    expected = normalized_digest.encode("ascii")
    try:
        with zipfile.ZipFile(artifact) as archive:
            dex_entries = _dex_entry_names(archive, artifact)
            if not dex_entries:
                raise SourceBindingError(f"Android artifact contains no application DEX: {artifact}")
            dex_strings = {
                value
                for name in dex_entries
                for value in _dex_string_values(archive.read(name), f"{artifact}!/{name}")
            }
    except SourceBindingError:
        raise
    except (OSError, KeyError, RuntimeError, struct.error, zipfile.BadZipFile, zlib.error) as exc:
        raise SourceBindingError(f"Unable to inspect Android artifact {artifact}: {exc}") from exc

    if expected not in dex_strings:
        raise SourceBindingError(
            f"Android artifact does not embed expected source digest {normalized_digest}: {artifact}"
        )
    if UNBOUND_SOURCE_SENTINEL in dex_strings:
        raise SourceBindingError(f"Android artifact still embeds an unbound source identity: {artifact}")
    return dex_entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--source-digest", required=True)
    args = parser.parse_args()
    entries = verify_source_binding(args.artifact, args.source_digest)
    print(f"sourceBinding=verified artifact={args.artifact} dexEntries={','.join(entries)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
