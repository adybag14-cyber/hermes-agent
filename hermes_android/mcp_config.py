"""Bounded, credential-safe inputs for the Full edition's owned MCP client."""
from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import ipaddress
import json
import re
from urllib.parse import urlsplit

MAX_SERVERS = 8
MAX_TOOLS = 128
MAX_SCHEMA_BYTES = 256 * 1024
MAX_CATALOG_BYTES = 1024 * 1024
MAX_RESULT_CHARS = 64000


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def validate_schema(value, *, depth=0) -> None:
    if depth > 32:
        raise ValueError("MCP schema nesting exceeds the limit")
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"$ref", "$dynamicRef", "$recursiveRef"} and (
                not isinstance(child, str) or not child.startswith("#")
            ):
                raise ValueError("MCP schemas must not fetch external references")
            validate_schema(child, depth=depth + 1)
    elif isinstance(value, list):
        for child in value:
            validate_schema(child, depth=depth + 1)


def _strings(value, *, maximum: int, label: str) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict) or len(value) > maximum:
        raise ValueError(f"Invalid MCP {label}")
    result = {}
    for key, item in value.items():
        if not isinstance(key, str) or not isinstance(item, str) or not key or len(key) > 256 or len(item) > 8192:
            raise ValueError(f"Invalid MCP {label} entry")
        if any(char in key + item for char in "\x00\r\n"):
            raise ValueError(f"Invalid control character in MCP {label}")
        result[key] = item
    return result


@dataclass(frozen=True)
class ServerConfig:
    name: str
    transport: str
    fingerprint: str
    argv: tuple[str, ...] = ()
    url: str = field(default="", repr=False)
    headers: dict[str, str] = field(default_factory=dict, repr=False)
    environment: dict[str, str] = field(default_factory=dict, repr=False)


def parse_servers(config: dict) -> dict[str, ServerConfig]:
    if not isinstance(config, dict) or len(canonical(config).encode()) > 200000:
        raise ValueError("MCP configuration is invalid or too large")
    raw_servers = config.get("mcpServers", config.get("mcp_servers", {}))
    if not isinstance(raw_servers, dict):
        raise ValueError("MCP servers must be an object")
    result = {}
    for name, raw in raw_servers.items():
        if not isinstance(name, str) or not 1 <= len(name) <= 128 or not isinstance(raw, dict):
            raise ValueError("Invalid MCP server entry")
        if any(key in raw and not isinstance(raw[key], bool) for key in ("enabled", "autoStart")):
            raise ValueError("MCP enabled and autoStart fields must be JSON booleans")
        if raw.get("enabled", True) is False or raw.get("transport") == "native":
            continue
        transport = str(raw.get("transport") or ("http" if raw.get("url") else "stdio")).lower()
        transport = {"streamable_http": "http", "streamable-http": "http"}.get(transport, transport)
        if transport not in {"stdio", "sse", "http"}:
            raise ValueError("Unsupported MCP transport")
        headers = _strings(raw.get("headers"), maximum=32, label="headers")
        environment = _strings(raw.get("env"), maximum=64, label="environment")
        argv, url = (), ""
        if transport == "stdio":
            command, args = raw.get("command"), raw.get("args", [])
            if not isinstance(command, str) or not command.strip() or len(command) > 4096 or "\x00" in command:
                raise ValueError("MCP stdio requires a valid executable")
            if not isinstance(args, list) or len(args) > 128 or any(
                not isinstance(arg, str) or len(arg) > 8192 or "\x00" in arg for arg in args
            ):
                raise ValueError("Invalid MCP command arguments")
            argv = (command, *args)
        else:
            url = raw.get("url", "")
            if not isinstance(url, str) or len(url) > 4096:
                raise ValueError("Invalid MCP URL")
            parsed = urlsplit(url)
            if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
                raise ValueError("MCP requires an HTTP(S) URL without embedded credentials or fragments")
            try:
                loopback = ipaddress.ip_address(parsed.hostname).is_loopback
            except ValueError:
                loopback = parsed.hostname == "localhost"
            if parsed.scheme == "http" and not loopback and (headers or parsed.query):
                raise ValueError("MCP credentials require HTTPS outside loopback")
        identity = canonical([name, transport, argv, url, headers, environment])
        result[name] = ServerConfig(name, transport, hashlib.sha256(identity.encode()).hexdigest(),
                                    argv, url, headers, environment)
        if len(result) > MAX_SERVERS:
            raise ValueError("Too many enabled external MCP servers")
    return result


def tool_binding(config: ServerConfig, tool) -> dict:
    raw = tool.model_dump(by_alias=True, exclude_none=True)
    if len(canonical(raw).encode()) > MAX_SCHEMA_BYTES:
        raise ValueError("MCP tool schema exceeds the size limit")
    validate_schema(raw.get("inputSchema", {}))
    validate_schema(raw.get("outputSchema", {}))
    original_name = raw["name"]
    if not isinstance(original_name, str) or not 1 <= len(original_name) <= 256:
        raise ValueError("Invalid MCP tool name")
    digest = hashlib.sha256(canonical([config.fingerprint, raw]).encode()).hexdigest()[:16]
    label = re.sub(r"[^a-zA-Z0-9_-]", "_", original_name)[:36]
    name = "mcp_" + label + "_" + digest
    return {"server": config.name, "fingerprint": config.fingerprint, "tool": original_name,
            "definition": {"type": "function", "function": {
                "name": name, "description": str(raw.get("description", ""))[:8192],
                "parameters": raw["inputSchema"],
            }}}


def tool_result_text(result) -> str:
    # Embedded media and links are descriptions only: this bridge never fetches
    # resources, writes files, samples a model, or opens a server-provided URL.
    parts = []
    remaining = MAX_RESULT_CHARS
    if result.result_type == "input_required":
        return canonical({"is_error": True, "text": "This MCP tool requires interactive input, which this Android bridge does not provide."})
    for item in result.content:
        if item.type == "text":
            text = item.text[:remaining]
            parts.append(text)
            remaining -= len(text)
        elif remaining > 100:
            parts.append(f"[{item.type} content omitted by the Android text-tool bridge]")
            remaining -= len(parts[-1])
        if remaining <= 0:
            break
    if not parts and result.structured_content is not None:
        structured = canonical(result.structured_content)
        parts.append(structured[:remaining])
        remaining -= min(len(structured), remaining)
    return canonical({"is_error": result.is_error, "result_type": result.result_type,
                      "text": "\n".join(parts), "truncated": remaining <= 0})
