import hashlib
import importlib.util
import struct
import zlib
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts/sanitize_android_listing_image.py"
LISTING_DIR = REPO_ROOT / "fastlane/metadata/android/en-US/images/phoneScreenshots"
LEGACY_LISTING_DIR = REPO_ROOT / "metadata/com.nousresearch.hermesagent"
CURRENT_PUBLIC_SCREENSHOT_NAMES = {
    "00-chat.jpg",
    "01-accounts.jpg",
    "02-portal.jpg",
    "03-device.jpg",
    "04-settings.jpg",
    "1.jpg",
    "2.jpg",
    "3.jpg",
    "4.jpg",
    "5.jpg",
}


def _load_sanitizer():
    spec = importlib.util.spec_from_file_location("sanitize_android_listing_image", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _png_chunk(chunk_type: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + chunk_type
        + payload
        + struct.pack(">I", zlib.crc32(chunk_type + payload) & 0xFFFFFFFF)
    )


def test_fdroid_listing_images_are_current_upstream_metadata_free_and_unique():
    sanitizer = _load_sanitizer()
    assert LISTING_DIR.is_dir()
    assert not any(path.is_file() for path in LEGACY_LISTING_DIR.rglob("*"))

    screenshots = sorted(
        path
        for path in LISTING_DIR.iterdir()
        if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png"}
    )
    # F-Droid can retain deleted screenshot identities (fdroidserver #490).
    # Overwrite every name currently public for Hermes instead of adding a
    # third set or trusting central cleanup which has not happened yet.
    assert {path.name for path in screenshots} == CURRENT_PUBLIC_SCREENSHOT_NAMES
    assert len({path.stem for path in screenshots}) == len(screenshots)

    dimensions = set()
    digests = set()
    for screenshot in screenshots:
        payload = screenshot.read_bytes()
        inspection = sanitizer.inspect_image_bytes(payload)
        dimensions.add((inspection["width"], inspection["height"]))
        digests.add(hashlib.sha256(payload).hexdigest())
        assert inspection["height"] > inspection["width"]
        assert inspection["trailing_bytes"] == 0
        assert not inspection.get("metadata_markers", [])
        assert not inspection.get("metadata_chunks", [])

    assert len(dimensions) == 1
    assert len(digests) == len(screenshots)


def test_jpeg_sanitizer_removes_metadata_without_changing_encoded_scan():
    sanitizer = _load_sanitizer()
    clean = next(LISTING_DIR.glob("*.jpg")).read_bytes()
    app_payload = b"Exif\x00\x00device-software"
    comment_payload = b"private comment"

    def segment(marker: int, payload: bytes) -> bytes:
        return b"\xff" + bytes([marker]) + (len(payload) + 2).to_bytes(2, "big") + payload

    polluted = (
        clean[:2]
        + segment(0xE1, app_payload)
        + segment(0xFE, comment_payload)
        + clean[2:]
    )
    assert sanitizer.inspect_jpeg_bytes(polluted)["metadata_markers"] == [
        "APP1",
        "COM",
    ]
    assert sanitizer.sanitize_jpeg_bytes(polluted) == clean


def test_jpeg_sanitizer_rejects_post_scan_metadata_and_trailing_payloads():
    sanitizer = _load_sanitizer()
    clean = next(LISTING_DIR.glob("*.jpg")).read_bytes()

    def segment(marker: int, payload: bytes) -> bytes:
        return b"\xff" + bytes([marker]) + (len(payload) + 2).to_bytes(2, "big") + payload

    post_scan_comment = clean[:-2] + segment(0xFE, b"private") + clean[-2:]
    with pytest.raises(sanitizer.ListingImageError, match="after SOS"):
        sanitizer.sanitize_jpeg_bytes(post_scan_comment)

    early_eoi_with_trailing_payload = clean + b"private trailing bytes" + sanitizer.JPEG_EOI
    with pytest.raises(sanitizer.ListingImageError, match="trailing byte"):
        sanitizer.sanitize_jpeg_bytes(early_eoi_with_trailing_payload)


def test_png_sanitizer_removes_all_ancillary_chunks():
    sanitizer = _load_sanitizer()
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    scanline = b"\x00\x11\x22\x33"
    polluted = (
        sanitizer.PNG_SIGNATURE
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"tEXt", b"Software\x00private-device")
        + _png_chunk(b"IDAT", zlib.compress(scanline))
        + _png_chunk(b"IEND", b"")
    )
    assert sanitizer.inspect_png_bytes(polluted)["metadata_chunks"] == ["tEXt"]
    clean = sanitizer.sanitize_png_bytes(polluted)
    assert sanitizer.inspect_png_bytes(clean)["chunks"] == ["IHDR", "IDAT", "IEND"]


def test_png_sanitizer_rejects_rendering_chunks():
    sanitizer = _load_sanitizer()
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    scanline = b"\x00\x11\x22\x33"
    transparent = (
        sanitizer.PNG_SIGNATURE
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"tRNS", b"\x00\x11\x00\x22\x00\x33")
        + _png_chunk(b"IDAT", zlib.compress(scanline))
        + _png_chunk(b"IEND", b"")
    )

    with pytest.raises(sanitizer.ListingImageError, match="affect rendering"):
        sanitizer.sanitize_png_bytes(transparent)
