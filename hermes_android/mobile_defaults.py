from __future__ import annotations

from typing import Any

from hermes_android.python_path import prefer_hermes_package_root

prefer_hermes_package_root()

from hermes_cli.config import load_config, save_config
DEFAULT_ANDROID_API_SERVER_TOOLSETS = ["hermes-android-app"]

_PROCESS_BACKED_API_MODES = {"acp", "codex_app_server"}
_PROCESS_BACKED_URL_PREFIXES = ("acp://", "acp+tcp://")
_PROCESS_BACKED_PROVIDERS = {
    "copilot-acp",
    "github-copilot-acp",
    "copilot-acp-agent",
}


def _process_backed_provider_reason(entry: Any) -> str | None:
    if not isinstance(entry, dict):
        return None
    provider = str(entry.get("provider") or "").strip().lower()
    base_url = str(entry.get("base_url") or "").strip().lower()
    api_mode = str(entry.get("api_mode") or "").strip().lower()
    if provider in _PROCESS_BACKED_PROVIDERS:
        return f"process-backed provider '{provider}'"
    if base_url.startswith(_PROCESS_BACKED_URL_PREFIXES):
        return f"process-backed provider URL '{base_url}'"
    if api_mode in _PROCESS_BACKED_API_MODES:
        return f"process-backed API mode '{api_mode}'"
    if entry.get("command") or entry.get("args"):
        return "provider command/args"
    return None


def validate_android_provider_runtime(
    runtime_kwargs: dict[str, Any],
    fallback_model: list | dict | None,
) -> None:
    """Reject provider transports whose child-process lifetime is not owned."""
    entries: list[Any] = [("primary", runtime_kwargs)]
    if isinstance(fallback_model, list):
        entries.extend((f"fallback[{index}]", value) for index, value in enumerate(fallback_model))
    elif fallback_model is not None:
        entries.append(("fallback", fallback_model))
    for label, entry in entries:
        reason = _process_backed_provider_reason(entry)
        if reason:
            raise RuntimeError(
                f"Android embedded runtime rejects {label} {reason}; "
                "select an HTTP provider or an on-device backend instead"
            )


def _configured_api_server_toolsets(config: dict[str, Any] | None) -> list[str] | None:
    if not isinstance(config, dict):
        return None
    platform_toolsets = config.get("platform_toolsets")
    if not isinstance(platform_toolsets, dict):
        return None
    configured = platform_toolsets.get("api_server")
    if not isinstance(configured, list):
        return None
    cleaned = [str(item).strip() for item in configured if str(item).strip()]
    return cleaned or None


def should_force_android_api_server_toolsets(config: dict[str, Any] | None) -> bool:
    configured = _configured_api_server_toolsets(config)
    return configured != DEFAULT_ANDROID_API_SERVER_TOOLSETS


def resolved_android_api_server_toolsets(config: dict[str, Any] | None) -> list[str]:
    del config
    return list(DEFAULT_ANDROID_API_SERVER_TOOLSETS)


def ensure_android_defaults(config: dict[str, Any] | None = None, *, persist: bool = True) -> dict[str, Any]:
    loaded = load_config() if config is None else config
    platform_toolsets = loaded.setdefault("platform_toolsets", {})
    if not isinstance(platform_toolsets, dict):
        platform_toolsets = {}
        loaded["platform_toolsets"] = platform_toolsets

    current = _configured_api_server_toolsets(loaded)
    if current != DEFAULT_ANDROID_API_SERVER_TOOLSETS:
        platform_toolsets["api_server"] = list(DEFAULT_ANDROID_API_SERVER_TOOLSETS)
        if persist:
            save_config(loaded)
    return loaded
