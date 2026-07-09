from __future__ import annotations

import os
import secrets
import socket
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from hermes_android.linux_subsystem import apply_linux_subsystem_env


@dataclass
class AndroidRuntimeEnv:
    files_dir: Path
    hermes_home: Path
    api_server_host: str
    api_server_port: int
    api_server_key: str
    api_server_model_name: str

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        return {key: str(value) if isinstance(value, Path) else value for key, value in payload.items()}


def _find_free_port(host: str) -> int:
    bind_host = host if host not in {"", "0.0.0.0"} else "127.0.0.1"
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((bind_host, 0))
        return int(sock.getsockname()[1])


def guess_lan_ipv4() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            candidate = sock.getsockname()[0]
            if candidate and not candidate.startswith("127."):
                return candidate
    except OSError:
        pass
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            candidate = info[4][0]
            if candidate and not candidate.startswith("127."):
                return candidate
    except OSError:
        pass
    return None


def loopback_base_url(host: str, port: int) -> str:
    return f"http://127.0.0.1:{port}"


def lan_base_url(host: str, port: int) -> str | None:
    if host in {"127.0.0.1", "localhost"}:
        return None
    lan_ip = guess_lan_ipv4()
    if not lan_ip:
        return None
    return f"http://{lan_ip}:{port}"


def prepare_runtime_env(
    files_dir: str | Path,
    *,
    api_server_host: str = "0.0.0.0",
    api_server_port: int | None = None,
    api_server_key: str | None = None,
    api_server_model_name: str = "hermes-agent-android",
) -> AndroidRuntimeEnv:
    files_path = Path(files_dir).expanduser().resolve()
    hermes_home = files_path / "hermes-home"
    hermes_home.mkdir(parents=True, exist_ok=True)
    for child in ("logs", "sessions", "skills", "downloads", "workspace"):
        (hermes_home / child).mkdir(parents=True, exist_ok=True)

    port = api_server_port or _find_free_port(api_server_host)
    key = api_server_key or secrets.token_urlsafe(32)

    os.environ["HERMES_HOME"] = str(hermes_home)
    os.environ["HERMES_ANDROID_BOOTSTRAP"] = "1"
    os.environ["API_SERVER_HOST"] = api_server_host
    os.environ["API_SERVER_PORT"] = str(port)
    os.environ["API_SERVER_KEY"] = key
    os.environ["API_SERVER_MODEL_NAME"] = api_server_model_name

    for env_key, env_value in apply_linux_subsystem_env(files_path).items():
        if env_value:
            os.environ[env_key] = env_value

    return AndroidRuntimeEnv(
        files_dir=files_path,
        hermes_home=hermes_home,
        api_server_host=api_server_host,
        api_server_port=port,
        api_server_key=key,
        api_server_model_name=api_server_model_name,
    )
