from __future__ import annotations

from dataclasses import dataclass
import re


_BUILD_PATTERNS = (
    r"\bzbuduj(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b",
    r"\bstwórz(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b",
    r"\butwórz(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b",
    r"\bzrób(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b",
    r"\bbuild(?:\s+an?)?\s+app\b",
)

_ANDROID_WORDS = ("android", "apk", "aplikację android", "aplikacja android")
_WEB_WORDS = ("web", "stronę", "strona", "aplikację web", "aplikacja web")


@dataclass(frozen=True)
class VoiceCommand:
    action: str
    instruction: str
    name: str
    platform: str


def parse_voice_command(text: str) -> VoiceCommand | None:
    clean = " ".join(text.strip().split())
    if not clean:
        return None

    lowered = clean.casefold()
    if not any(re.search(pattern, lowered) for pattern in _BUILD_PATTERNS):
        return None

    platform = "android" if any(word in lowered for word in _ANDROID_WORDS) else "web"
    name = _extract_name(clean, platform)
    return VoiceCommand(action="autonomous_build", instruction=clean, name=name, platform=platform)


def _extract_name(text: str, platform: str) -> str:
    lowered = text.casefold()
    match = None
    for pattern in _BUILD_PATTERNS:
        match = re.search(pattern, lowered)
        if match:
            break

    remainder = text[match.end():].strip() if match else text.strip()
    remainder = re.sub(r"\b(?:na|w|dla)\s+(?:android|web)\b.*$", "", remainder, flags=re.I).strip()
    remainder = re.sub(r"\b(?:android|apk|web)\b", "", remainder, flags=re.I).strip()
    remainder = re.sub(r"^[\s:,-]+|[\s:,-]+$", "", remainder)

    if not remainder:
        return "ODYN App" if platform == "web" else "ODYN Android App"

    return remainder[:80]
