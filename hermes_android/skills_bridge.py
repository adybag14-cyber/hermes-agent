"""List (and best-effort toggle) Hermes skills for the Android settings UI.

Scans ``hermes_home/skills`` plus common skill directories (bundled / optional).
Builtin/bundled trees may be read-only; listing still works.

Enabled state is best-effort via ``skills.disabled`` / ``skills.enabled`` in
``config.yaml`` when that config is available. Otherwise every skill is
reported as enabled and the note explains that toggle requires config.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, Iterable

from hermes_android.python_path import prefer_hermes_package_root

prefer_hermes_package_root()

_EXCLUDED_DIRS = frozenset({".git", ".github", ".hub", ".archive", "__pycache__"})
_NAME_RE = re.compile(r"^name\s*:\s*(.+)$", re.IGNORECASE)
_DESC_RE = re.compile(r"^description\s*:\s*(.+)$", re.IGNORECASE)


def _ok(**payload: Any) -> str:
    return json.dumps({"ok": True, **payload}, ensure_ascii=False)


def _err(message: str, **payload: Any) -> str:
    return json.dumps({"ok": False, "error": message, **payload}, ensure_ascii=False)


def _hermes_home(hermes_home: str | None) -> Path:
    if hermes_home and str(hermes_home).strip():
        home = Path(os.path.expanduser(str(hermes_home).strip()))
    else:
        home = Path(os.path.expanduser(os.environ.get("HERMES_HOME", "~/.hermes")))
    # Always pin HERMES_HOME so load_config/save_config hit the Android home.
    os.environ["HERMES_HOME"] = str(home)
    return home


def _strip_yaml_scalar(value: str) -> str:
    text = value.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in {'"', "'"}:
        return text[1:-1].strip()
    return text


def _read_skill_md_header(skill_md: Path) -> tuple[str, str]:
    """Return (name, description) from the first 20 lines of SKILL.md."""
    name = skill_md.parent.name
    description = ""
    try:
        with skill_md.open("r", encoding="utf-8", errors="replace") as handle:
            lines = []
            for _ in range(20):
                line = handle.readline()
                if not line:
                    break
                lines.append(line.rstrip("\n"))
    except OSError:
        return name, description

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped == "---":
            continue
        name_match = _NAME_RE.match(stripped)
        if name_match:
            candidate = _strip_yaml_scalar(name_match.group(1))
            if candidate:
                name = candidate
            continue
        desc_match = _DESC_RE.match(stripped)
        if desc_match and not description:
            candidate = _strip_yaml_scalar(desc_match.group(1))
            if candidate:
                description = candidate
            continue

    if not description:
        in_frontmatter = False
        saw_frontmatter = False
        for line in lines:
            stripped = line.strip()
            if stripped == "---":
                if not saw_frontmatter:
                    in_frontmatter = True
                    saw_frontmatter = True
                    continue
                in_frontmatter = False
                continue
            if in_frontmatter:
                continue
            if stripped.startswith("#"):
                continue
            if stripped:
                description = stripped
                break
    return name, description


def _skill_roots(home: Path) -> list[Path]:
    roots: list[Path] = []
    seen: set[Path] = set()

    def add(path: Path | None) -> None:
        if path is None:
            return
        try:
            resolved = path.expanduser().resolve()
        except OSError:
            return
        if not resolved.is_dir() or resolved in seen:
            return
        seen.add(resolved)
        roots.append(resolved)

    add(home / "skills")
    for env_name in ("HERMES_BUNDLED_SKILLS", "HERMES_OPTIONAL_SKILLS"):
        raw = os.environ.get(env_name, "").strip()
        if raw:
            add(Path(raw))
    try:
        from hermes_android.bundled_assets import bundled_skills_dir, optional_skills_dir

        add(bundled_skills_dir())
        add(optional_skills_dir())
    except Exception:
        pass
    return roots


def _iter_skill_md(skills_dir: Path) -> Iterable[Path]:
    matches: list[Path] = []
    try:
        for root, dirs, files in os.walk(skills_dir, followlinks=True):
            dirs[:] = [d for d in dirs if d not in _EXCLUDED_DIRS]
            if "SKILL.md" in files:
                matches.append(Path(root) / "SKILL.md")
    except OSError:
        return []
    return sorted(matches, key=lambda p: str(p.relative_to(skills_dir)).lower())


def _load_skills_config(home: Path) -> tuple[dict[str, Any] | None, str]:
    """Load config.yaml skills section. Returns (skills_cfg_or_None, note)."""
    os.environ["HERMES_HOME"] = str(home)
    try:
        from hermes_cli.config import load_config

        config = load_config()
        if not isinstance(config, dict):
            return None, "toggle requires config"
        skills_cfg = config.get("skills")
        if not isinstance(skills_cfg, dict):
            return {}, ""
        return skills_cfg, ""
    except Exception as exc:  # noqa: BLE001 — surface as no-toggle mode
        return None, f"toggle requires config ({exc.__class__.__name__})"


def _enabled_for_name(name: str, skills_cfg: dict[str, Any] | None) -> bool:
    if skills_cfg is None:
        return True
    enabled_list = skills_cfg.get("enabled")
    if isinstance(enabled_list, list):
        return name in {str(item).strip() for item in enabled_list if str(item).strip()}
    disabled = skills_cfg.get("disabled")
    if disabled is None:
        return True
    if isinstance(disabled, str):
        disabled_set = {disabled.strip()} if disabled.strip() else set()
    elif isinstance(disabled, list):
        disabled_set = {str(item).strip() for item in disabled if str(item).strip()}
    else:
        return True
    return name not in disabled_set


def _toggle_supported(skills_cfg: dict[str, Any] | None) -> bool:
    """True when runtime config is available so we can read/write enablement.

    Prefer ``skills.enabled`` (allowlist) when present; otherwise use Hermes'
    native ``skills.disabled`` denylist on toggle. If config cannot be loaded,
    listing still works with enabled=true for every skill.
    """
    return isinstance(skills_cfg, dict)


def list_skills(hermes_home: str | None = None) -> str:
    """Return JSON: {ok, skills:[{name, path, description, enabled}], note?, toggle_supported?}."""
    try:
        home = _hermes_home(hermes_home)
        skills_cfg, config_note = _load_skills_config(home)
        toggle_supported = _toggle_supported(skills_cfg)
        note = ""
        if not toggle_supported:
            note = config_note if config_note else "toggle requires config"

        skills: list[dict[str, Any]] = []
        seen_names: set[str] = set()
        for root in _skill_roots(home):
            for skill_md in _iter_skill_md(root):
                name, description = _read_skill_md_header(skill_md)
                if not name or name in seen_names:
                    continue
                seen_names.add(name)
                skills.append(
                    {
                        "name": name,
                        "path": str(skill_md.parent),
                        "description": description,
                        "enabled": _enabled_for_name(name, skills_cfg)
                        if toggle_supported
                        else True,
                    }
                )

        skills.sort(key=lambda item: item["name"].lower())
        payload: dict[str, Any] = {
            "skills": skills,
            "toggle_supported": toggle_supported,
            "count": len(skills),
        }
        if note:
            payload["note"] = note
        return _ok(**payload)
    except Exception as exc:  # pragma: no cover - surface to Android UI
        return _err(str(exc) or exc.__class__.__name__)


def set_skill_enabled(
    name: str,
    enabled: bool,
    hermes_home: str | None = None,
) -> str:
    """Best-effort toggle via skills.enabled / skills.disabled in config.yaml."""
    try:
        home = _hermes_home(hermes_home)
        skill_name = str(name or "").strip()
        if not skill_name:
            return _err("name is required")

        os.environ["HERMES_HOME"] = str(home)
        from hermes_cli.config import load_config, save_config

        config = load_config()
        if not isinstance(config, dict):
            return _err("toggle requires config")

        skills_cfg = config.setdefault("skills", {})
        if not isinstance(skills_cfg, dict):
            skills_cfg = {}
            config["skills"] = skills_cfg

        if isinstance(skills_cfg.get("enabled"), list):
            enabled_set = {
                str(item).strip()
                for item in skills_cfg.get("enabled") or []
                if str(item).strip()
            }
            if enabled:
                enabled_set.add(skill_name)
            else:
                enabled_set.discard(skill_name)
            skills_cfg["enabled"] = sorted(enabled_set)
        else:
            disabled_raw = skills_cfg.get("disabled") or []
            if isinstance(disabled_raw, str):
                disabled_set = {disabled_raw.strip()} if disabled_raw.strip() else set()
            elif isinstance(disabled_raw, list):
                disabled_set = {
                    str(item).strip() for item in disabled_raw if str(item).strip()
                }
            else:
                disabled_set = set()
            if enabled:
                disabled_set.discard(skill_name)
            else:
                disabled_set.add(skill_name)
            skills_cfg["disabled"] = sorted(disabled_set)

        save_config(config)
        return list_skills(str(home))
    except Exception as exc:  # pragma: no cover
        return _err(
            str(exc) or exc.__class__.__name__,
            note="toggle requires config",
        )
