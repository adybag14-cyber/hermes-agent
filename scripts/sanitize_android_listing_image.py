#!/usr/bin/env python3
"""Losslessly remove metadata containers from F-Droid listing images.

JPEG sanitization removes APP0 through APP15 and COM segments while preserving
the encoded image scan byte-for-byte. PNG sanitization retains only critical
chunks, validating every retained CRC. The tool deliberately does not crop,
resize, recolor, or otherwise change visible app pixels.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import tempfile
import zlib
from pathlib import Path
from typing import Any


JPEG_SOI = b"\xff\xd8"
JPEG_EOI = b"\xff\xd9"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
JPEG_APP_MARKERS = frozenset(range(0xE0, 0xF0))
JPEG_COM_MARKER = 0xFE
JPEG_SOS_MARKER = 0xDA
JPEG_EOI_MARKER = 0xD9
JPEG_BASELINE_SOF_MARKER = 0xC0
JPEG_STANDALONE_MARKERS = frozenset({0x01, *range(0xD0, 0xDA)})
JPEG_SOF_MARKERS = frozenset(
    marker
    for marker in range(0xC0, 0xD0)
    if marker not in {0xC4, 0xC8, 0xCC}
)
PNG_ALLOWED_CHUNKS = frozenset({b"IHDR", b"IDAT", b"IEND"})
PNG_SAFE_METADATA_CHUNKS = frozenset(
    {b"tEXt", b"zTXt", b"iTXt", b"eXIf", b"tIME", b"pHYs"}
)


class ListingImageError(ValueError):
    """Raised when a listing image is malformed or retains metadata."""


def _jpeg_segment(data: bytes, offset: int) -> tuple[int, int, int, bytes]:
    if offset >= len(data) or data[offset] != 0xFF:
        raise ListingImageError(f"expected JPEG marker at byte {offset}")
    marker_start = offset
    while offset < len(data) and data[offset] == 0xFF:
        offset += 1
    if offset >= len(data):
        raise ListingImageError("truncated JPEG marker")
    marker = data[offset]
    offset += 1
    if marker == 0x00:
        raise ListingImageError("escaped scan byte appeared before JPEG SOS")
    if marker in JPEG_STANDALONE_MARKERS:
        return marker, marker_start, offset, b""
    if offset + 2 > len(data):
        raise ListingImageError("truncated JPEG segment length")
    segment_length = int.from_bytes(data[offset : offset + 2], "big")
    if segment_length < 2:
        raise ListingImageError("invalid JPEG segment length")
    segment_end = offset + segment_length
    if segment_end > len(data):
        raise ListingImageError("JPEG segment extends past end of file")
    payload = data[offset + 2 : segment_end]
    return marker, marker_start, segment_end, payload


def inspect_jpeg_bytes(data: bytes) -> dict[str, Any]:
    if not data.startswith(JPEG_SOI):
        raise ListingImageError("missing JPEG SOI marker")
    if not data.endswith(JPEG_EOI):
        raise ListingImageError("JPEG must end exactly at its EOI marker")

    offset = len(JPEG_SOI)
    markers: list[str] = []
    metadata_markers: list[str] = []
    width: int | None = None
    height: int | None = None
    scan_offset: int | None = None

    while offset < len(data):
        marker, marker_start, segment_end, payload = _jpeg_segment(data, offset)
        if marker == JPEG_EOI_MARKER:
            raise ListingImageError("JPEG ended before an SOS scan")
        if marker == JPEG_SOS_MARKER:
            scan_offset = marker_start
            break
        name = f"APP{marker - 0xE0}" if marker in JPEG_APP_MARKERS else (
            "COM" if marker == JPEG_COM_MARKER else f"0x{marker:02X}"
        )
        markers.append(name)
        if marker in JPEG_APP_MARKERS or marker == JPEG_COM_MARKER:
            metadata_markers.append(name)
        if marker in JPEG_SOF_MARKERS:
            if marker != JPEG_BASELINE_SOF_MARKER:
                raise ListingImageError(
                    "only baseline JPEG screenshots can be sanitized losslessly"
                )
            if width is not None or height is not None:
                raise ListingImageError("JPEG contains multiple SOF segments")
            if len(payload) < 5:
                raise ListingImageError("truncated JPEG SOF payload")
            height = int.from_bytes(payload[1:3], "big")
            width = int.from_bytes(payload[3:5], "big")
        offset = segment_end

    if scan_offset is None:
        raise ListingImageError("JPEG has no SOS scan")
    if width is None or height is None or width <= 0 or height <= 0:
        raise ListingImageError("JPEG has no valid SOF dimensions")

    _, _, scan_data_offset, _ = _jpeg_segment(data, scan_offset)
    offset = scan_data_offset
    eoi_offset: int | None = None
    while offset < len(data):
        marker_start = data.find(b"\xff", offset)
        if marker_start < 0:
            break
        marker_offset = marker_start + 1
        while marker_offset < len(data) and data[marker_offset] == 0xFF:
            marker_offset += 1
        if marker_offset >= len(data):
            break
        marker = data[marker_offset]
        if marker == 0x00 or 0xD0 <= marker <= 0xD7:
            offset = marker_offset + 1
            continue
        if marker == JPEG_EOI_MARKER:
            eoi_offset = marker_start
            break
        raise ListingImageError(
            f"unexpected JPEG marker 0x{marker:02X} after SOS at byte {marker_start}"
        )

    if eoi_offset is None:
        raise ListingImageError("JPEG scan has no EOI marker")
    trailing_bytes = len(data) - (eoi_offset + len(JPEG_EOI))
    if trailing_bytes:
        raise ListingImageError(
            f"JPEG contains {trailing_bytes} trailing byte(s) after EOI"
        )

    return {
        "format": "jpeg",
        "width": width,
        "height": height,
        "markers_before_scan": markers,
        "metadata_markers": metadata_markers,
        "scan_offset": scan_offset,
        "trailing_bytes": trailing_bytes,
    }


def sanitize_jpeg_bytes(data: bytes) -> bytes:
    inspection = inspect_jpeg_bytes(data)
    output = bytearray(JPEG_SOI)
    offset = len(JPEG_SOI)

    while offset < inspection["scan_offset"]:
        marker, marker_start, segment_end, _ = _jpeg_segment(data, offset)
        if marker not in JPEG_APP_MARKERS and marker != JPEG_COM_MARKER:
            output.extend(data[marker_start:segment_end])
        offset = segment_end

    output.extend(data[inspection["scan_offset"] :])
    sanitized = bytes(output)
    verified = inspect_jpeg_bytes(sanitized)
    if verified["metadata_markers"]:
        raise ListingImageError("JPEG metadata markers remain after sanitization")
    return sanitized


def _png_chunks(data: bytes) -> list[tuple[bytes, bytes, bytes]]:
    if not data.startswith(PNG_SIGNATURE):
        raise ListingImageError("missing PNG signature")
    offset = len(PNG_SIGNATURE)
    chunks: list[tuple[bytes, bytes, bytes]] = []
    saw_iend = False

    while offset < len(data):
        if offset + 12 > len(data):
            raise ListingImageError("truncated PNG chunk")
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk_end = offset + 12 + length
        if chunk_end > len(data):
            raise ListingImageError("PNG chunk extends past end of file")
        payload = data[offset + 8 : offset + 8 + length]
        stored_crc = data[offset + 8 + length : chunk_end]
        expected_crc = struct.pack(">I", zlib.crc32(chunk_type + payload) & 0xFFFFFFFF)
        if stored_crc != expected_crc:
            raise ListingImageError(
                f"invalid PNG CRC for {chunk_type.decode('ascii', errors='replace')}"
            )
        raw = data[offset:chunk_end]
        chunks.append((chunk_type, payload, raw))
        offset = chunk_end
        if chunk_type == b"IEND":
            saw_iend = True
            break

    if not saw_iend:
        raise ListingImageError("PNG has no IEND chunk")
    if offset != len(data):
        raise ListingImageError("PNG contains trailing bytes after IEND")
    return chunks


def inspect_png_bytes(data: bytes) -> dict[str, Any]:
    chunks = _png_chunks(data)
    if not chunks or chunks[0][0] != b"IHDR":
        raise ListingImageError("PNG must begin with IHDR")
    if chunks[-1][0] != b"IEND":
        raise ListingImageError("PNG must end with IEND")
    if not any(chunk_type == b"IDAT" for chunk_type, _, _ in chunks):
        raise ListingImageError("PNG has no IDAT payload")
    if len(chunks[0][1]) != 13:
        raise ListingImageError("invalid PNG IHDR length")
    width, height = struct.unpack(">II", chunks[0][1][:8])
    color_type = chunks[0][1][9]
    if width <= 0 or height <= 0:
        raise ListingImageError("PNG has invalid dimensions")
    if color_type not in {2, 6}:
        raise ListingImageError(
            "only truecolor RGB or RGBA PNG screenshots can be sanitized safely"
        )
    chunk_names = [chunk_type.decode("ascii") for chunk_type, _, _ in chunks]
    ancillary = [
        name
        for name in chunk_names
        if name.encode("ascii")[0] & 0x20
    ]
    return {
        "format": "png",
        "width": width,
        "height": height,
        "color_type": color_type,
        "chunks": chunk_names,
        "metadata_chunks": ancillary,
        "trailing_bytes": 0,
    }


def sanitize_png_bytes(data: bytes) -> bytes:
    chunks = _png_chunks(data)
    inspect_png_bytes(data)
    unsafe_ancillary = [
        chunk_type.decode("ascii", errors="replace")
        for chunk_type, _, _ in chunks
        if chunk_type[0] & 0x20 and chunk_type not in PNG_SAFE_METADATA_CHUNKS
    ]
    if unsafe_ancillary:
        raise ListingImageError(
            "refusing to strip PNG chunks which can affect rendering: "
            f"{unsafe_ancillary}"
        )
    unsupported_critical = [
        chunk_type.decode("ascii", errors="replace")
        for chunk_type, _, _ in chunks
        if not (chunk_type[0] & 0x20) and chunk_type not in PNG_ALLOWED_CHUNKS
    ]
    if unsupported_critical:
        raise ListingImageError(
            f"unsupported critical PNG chunks: {unsupported_critical}"
        )
    output = bytearray(PNG_SIGNATURE)
    for chunk_type, _, raw in chunks:
        if chunk_type in PNG_ALLOWED_CHUNKS:
            output.extend(raw)
    sanitized = bytes(output)
    verified = inspect_png_bytes(sanitized)
    if verified["metadata_chunks"]:
        raise ListingImageError("PNG metadata chunks remain after sanitization")
    return sanitized


def inspect_image_bytes(data: bytes) -> dict[str, Any]:
    if data.startswith(JPEG_SOI):
        return inspect_jpeg_bytes(data)
    if data.startswith(PNG_SIGNATURE):
        return inspect_png_bytes(data)
    raise ListingImageError("listing images must be JPEG or PNG")


def sanitize_image_bytes(data: bytes) -> bytes:
    if data.startswith(JPEG_SOI):
        return sanitize_jpeg_bytes(data)
    if data.startswith(PNG_SIGNATURE):
        return sanitize_png_bytes(data)
    raise ListingImageError("listing images must be JPEG or PNG")


def _write_atomic(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def _verify_paths(paths: list[Path]) -> int:
    failed = False
    for path in paths:
        try:
            inspection = inspect_image_bytes(path.read_bytes())
            metadata = inspection.get("metadata_markers", inspection.get("metadata_chunks", []))
            if metadata:
                raise ListingImageError(f"metadata remains: {metadata}")
            print(json.dumps({"path": str(path), **inspection}, sort_keys=True))
        except (OSError, ListingImageError) as exc:
            failed = True
            print(json.dumps({"path": str(path), "error": str(exc)}, sort_keys=True))
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    sanitize_parser = subparsers.add_parser("sanitize")
    sanitize_parser.add_argument("source", type=Path)
    sanitize_parser.add_argument("target", type=Path)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()

    if args.command == "verify":
        return _verify_paths(args.paths)

    source = args.source.resolve()
    target = args.target.resolve()
    if source == target:
        raise ListingImageError("source and target must be different paths")
    sanitized = sanitize_image_bytes(source.read_bytes())
    _write_atomic(target, sanitized)
    return _verify_paths([target])


if __name__ == "__main__":
    raise SystemExit(main())
