"""Local browser bootstrap for ChatGPT Web authentication."""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import time
from types import SimpleNamespace
from typing import Any
import urllib.request

from hermes_constants import get_hermes_home

try:
    import websockets
except ImportError:
    websockets = None


def _is_termux() -> bool:
    prefix = os.getenv("PREFIX", "")
    return bool(os.getenv("TERMUX_VERSION") or "com.termux/files/usr" in prefix)


def _is_windows() -> bool:
    return os.name == "nt"


def _is_wsl() -> bool:
    if _is_windows() or _is_termux():
        return False
    if os.getenv("WSL_INTEROP") or os.getenv("WSL_DISTRO_NAME"):
        return True
    try:
        return "microsoft" in Path("/proc/version").read_text(encoding="utf-8").lower()
    except Exception:
        return False


def _find_windows_browser_command() -> str | None:
    for candidate in (
        shutil.which("msedge.exe"),
        shutil.which("chrome.exe"),
        shutil.which("chromium.exe"),
    ):
        if candidate:
            return candidate
    common_paths = [
        Path(os.getenv("ProgramFiles", "")) / "Microsoft" / "Edge" / "Application" / "msedge.exe",
        Path(os.getenv("ProgramFiles(x86)", "")) / "Microsoft" / "Edge" / "Application" / "msedge.exe",
        Path(os.getenv("ProgramFiles", "")) / "Google" / "Chrome" / "Application" / "chrome.exe",
        Path(os.getenv("ProgramFiles(x86)", "")) / "Google" / "Chrome" / "Application" / "chrome.exe",
    ]
    for path in common_paths:
        if str(path) and path.exists():
            return str(path)
    return None


def _find_desktop_browser_command() -> str | None:
    if _is_windows():
        return _find_windows_browser_command()
    return (
        shutil.which("chromium-browser")
        or shutil.which("chromium")
        or shutil.which("google-chrome")
        or shutil.which("microsoft-edge")
        or shutil.which("microsoft-edge-stable")
    )


def _chatgpt_web_browser_base_dir(browser_command: str | None = None) -> Path:
    override = os.getenv("HERMES_CHATGPT_WEB_BROWSER_BASE_DIR", "").strip()
    if override:
        return Path(override).expanduser()
    command = str(browser_command or "").strip()
    if command.startswith("/snap/bin/"):
        return Path.home() / "hermes-chatgpt-web-browser"
    return get_hermes_home() / "chatgpt-web-browser"


def _wsl_host_candidates() -> list[str]:
    candidates: list[str] = []
    try:
        resolv = Path("/etc/resolv.conf")
        if resolv.exists():
            for line in resolv.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith("nameserver "):
                    value = line.split(None, 1)[1].strip()
                    if value and value not in candidates:
                        candidates.append(value)
    except Exception:
        pass
    return candidates


def _debug_base_candidates(debug_port: int, *, expose_wsl_host: bool = False) -> list[str]:
    candidates = [f"http://127.0.0.1:{debug_port}", f"http://localhost:{debug_port}"]
    if expose_wsl_host:
        for host in _wsl_host_candidates():
            candidates.append(f"http://{host}:{debug_port}")
    seen: list[str] = []
    for item in candidates:
        if item not in seen:
            seen.append(item)
    return seen


def _launch_chatgpt_web_desktop_browser(
    browser_command: str,
    base_dir: Path,
    debug_port: int,
    *,
    expose_wsl_host: bool = False,
):
    base_dir.mkdir(parents=True, exist_ok=True)
    profile_dir = base_dir / "profile"
    logs_dir = base_dir / "logs"
    profile_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)
    log_handle = (logs_dir / "browser.log").open("ab")
    debug_address = "0.0.0.0" if expose_wsl_host else "127.0.0.1"
    command = [
        browser_command,
        f"--user-data-dir={profile_dir}",
        f"--remote-debugging-address={debug_address}",
        f"--remote-debugging-port={int(debug_port)}",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-fre",
        "--disable-session-crashed-bubble",
        "https://chatgpt.com",
    ]
    popen_kwargs = {
        "stdout": log_handle,
        "stderr": subprocess.STDOUT,
        "cwd": str(base_dir),
        "start_new_session": True,
    }
    if _is_windows():
        popen_kwargs["creationflags"] = (
            getattr(subprocess, "DETACHED_PROCESS", 0)
            | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        )
    proc = subprocess.Popen(command, **popen_kwargs)
    return proc, _debug_base_candidates(debug_port, expose_wsl_host=expose_wsl_host)


def _termux_x11_android_app_installed() -> bool:
    pm_command = "/system/bin/pm"
    if not Path(pm_command).exists():
        return False
    result = subprocess.run(
        [pm_command, "list", "packages", "com.termux.x11"],
        capture_output=True,
        text=True,
        check=False,
        env={k: v for k, v in os.environ.items() if k != "LD_PRELOAD"},
    )
    return "package:com.termux.x11" in (result.stdout or "")


def _find_termux_x11_command() -> str | None:
    return shutil.which("termux-x11")


def _find_chromium_browser_command() -> str | None:
    return shutil.which("chromium-browser") or shutil.which("chromium")


def _write_chatgpt_web_browser_launch_scripts(
    base_dir: Path,
    termux_x11_command: str,
    browser_command: str,
    debug_port: int,
) -> tuple[Path, Path]:
    base_dir.mkdir(parents=True, exist_ok=True)
    startup_script = base_dir / "startup.sh"
    launcher_script = base_dir / "launch.sh"

    startup_script.write_text(
        "#!/data/data/com.termux/files/usr/bin/bash\n"
        "set -euo pipefail\n\n"
        f"BASE_DIR={shlex.quote(str(base_dir))}\n"
        "PROFILE_DIR=\"$BASE_DIR/profile\"\n"
        "LOG_DIR=\"$BASE_DIR/logs\"\n"
        "mkdir -p \"$PROFILE_DIR\" \"$LOG_DIR\"\n\n"
        "export DISPLAY=\"${DISPLAY:-:0}\"\n"
        "export XDG_RUNTIME_DIR=\"${TMPDIR:-$PREFIX/tmp}\"\n"
        f"exec {shlex.quote(browser_command)} \\\n"
        "  --no-sandbox \\\n"
        "  --password-store=basic \\\n"
        "  --user-data-dir=\"$PROFILE_DIR\" \\\n"
        "  --remote-debugging-address=127.0.0.1 \\\n"
        f"  --remote-debugging-port={int(debug_port)} \\\n"
        "  --no-first-run \\\n"
        "  --no-default-browser-check \\\n"
        "  --disable-fre \\\n"
        "  --disable-crash-reporter \\\n"
        "  --disable-session-crashed-bubble \\\n"
        "  --window-size=1280,900 \\\n"
        "  https://chatgpt.com \\\n"
        "  >>\"$LOG_DIR/chromium.log\" 2>&1\n",
        encoding="utf-8",
    )

    launcher_script.write_text(
        "#!/data/data/com.termux/files/usr/bin/bash\n"
        "set -euo pipefail\n\n"
        f"BASE_DIR={shlex.quote(str(base_dir))}\n"
        "DISPLAY_FILE=\"$BASE_DIR/display\"\n"
        "mkdir -p \"$BASE_DIR\"\n"
        "rm -f \"$DISPLAY_FILE\"\n\n"
        "exec 3<>\"$DISPLAY_FILE\"\n"
        f"exec {shlex.quote(termux_x11_command)} -displayfd 3 -noreset -xstartup {shlex.quote(str(startup_script))}\n",
        encoding="utf-8",
    )

    startup_script.chmod(0o755)
    launcher_script.chmod(0o755)
    return launcher_script, startup_script


def _launch_chatgpt_web_browser(launcher_script: Path, base_dir: Path):
    log_path = base_dir / "termux-x11.log"
    with log_path.open("ab") as handle:
        return subprocess.Popen(
            [str(launcher_script)],
            stdout=handle,
            stderr=subprocess.STDOUT,
            cwd=str(base_dir),
            start_new_session=True,
        )


def _wait_for_debugger(debug_base: str, timeout: float = 30.0) -> None:
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{debug_base}/json/version", timeout=5) as response:
                if response.status == 200:
                    return
        except Exception as exc:
            last_error = exc
        time.sleep(1)
    raise SystemExit(f"Timed out waiting for Chromium DevTools at {debug_base}: {last_error}")


async def _get_chatgpt_web_browser_auth_state(debug_base: str) -> dict[str, Any] | None:
    if websockets is None:
        raise SystemExit("Python package 'websockets' is required for browser auth.")

    with urllib.request.urlopen(f"{debug_base}/json/list", timeout=5) as response:
        pages = json.load(response)

    page = None
    for item in pages:
        if item.get("type") == "page" and "chatgpt.com" in str(item.get("url") or ""):
            page = item
            break
    if page is None:
        return None

    ws_url = str(page.get("webSocketDebuggerUrl") or "").strip()
    if not ws_url:
        return None

    async with websockets.connect(ws_url, max_size=20_000_000) as ws:
        next_id = 1

        async def send(method: str, params: dict | None = None):
            nonlocal next_id
            payload = {"id": next_id, "method": method}
            if params is not None:
                payload["params"] = params
            await ws.send(json.dumps(payload))
            my_id = next_id
            next_id += 1
            while True:
                message = json.loads(await ws.recv())
                if message.get("id") == my_id:
                    return message

        await send("Network.enable")
        await send("Runtime.enable")
        result = await send("Network.getCookies", {"urls": ["https://chatgpt.com/", "https://auth.openai.com/"]})
        cookies = result.get("result", {}).get("cookies", [])
        from hermes_cli import chatgpt_web as chatgpt_web_mod

        normalized_cookies = chatgpt_web_mod._normalize_browser_cookies(cookies)
        cookie_header = chatgpt_web_mod._build_cookie_header(
            browser_cookies=normalized_cookies,
        )
        session_token = ""
        device_id = ""
        for cookie in normalized_cookies:
            name = str(cookie.get("name") or "").strip()
            value = str(cookie.get("value") or "").strip()
            if name == "__Secure-next-auth.session-token" and value:
                session_token = value
            elif name == "oai-did" and value:
                device_id = value
        if not session_token:
            return None
        user_agent = ""
        try:
            result = await send(
                "Runtime.evaluate",
                {"expression": "navigator.userAgent", "returnByValue": True},
            )
            user_agent = str(
                result.get("result", {})
                .get("result", {})
                .get("value")
                or ""
            ).strip()
        except Exception:
            user_agent = ""
        return {
            "session_token": session_token,
            "cookie_header": cookie_header,
            "browser_cookies": normalized_cookies,
            "device_id": device_id,
            "user_agent": user_agent,
        }
    return None


def _wait_for_chatgpt_web_browser_auth_state(
    debug_base: str,
    *,
    timeout_seconds: int = 15 * 60,
    poll_seconds: int = 5,
) -> dict[str, Any] | None:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        try:
            state = asyncio.run(_get_chatgpt_web_browser_auth_state(debug_base))
        except Exception:
            state = None
        if isinstance(state, dict) and str(state.get("session_token") or "").strip():
            return state
        print("waiting for ChatGPT login in browser...")
        time.sleep(poll_seconds)
    return None


def _terminate_process(proc, timeout: float = 5.0) -> None:
    if proc is None:
        return
    try:
        if proc.poll() is not None:
            return
    except Exception:
        pass
    try:
        proc.terminate()
        proc.wait(timeout=timeout)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def auth_browser_command(args) -> None:
    from hermes_cli.auth_commands import _normalize_provider, auth_add_command

    provider = _normalize_provider(getattr(args, "provider", "") or "chatgpt-web")
    if provider != "chatgpt-web":
        raise SystemExit("Browser auth currently supports only chatgpt-web.")
    if websockets is None:
        raise SystemExit("Python package 'websockets' is required for browser auth.")
    timeout_seconds = max(30, int(getattr(args, "timeout", None) or 15 * 60))
    debug_port = max(1024, int(getattr(args, "debug_port", None) or 9222))
    keep_open = bool(getattr(args, "keep_open", False))
    if _is_termux():
        if not _termux_x11_android_app_installed():
            raise SystemExit("Termux:X11 Android app (com.termux.x11) is not installed.")
        termux_x11_command = _find_termux_x11_command()
        if not termux_x11_command:
            raise SystemExit("termux-x11 command not found. Install `termux-x11-nightly`.")
        browser_command = _find_chromium_browser_command()
        if not browser_command:
            raise SystemExit("Chromium command not found. Install `chromium`.")
        base_root = _chatgpt_web_browser_base_dir(browser_command)
        base_root.mkdir(parents=True, exist_ok=True)
        base_dir = Path(tempfile.mkdtemp(prefix="login-", dir=base_root))
        label = (getattr(args, "label", None) or "termux-x11-browser").strip() or "termux-x11-browser"
        launcher_script, _startup_script = _write_chatgpt_web_browser_launch_scripts(
            base_dir,
            termux_x11_command,
            browser_command,
            debug_port,
        )
        proc = _launch_chatgpt_web_browser(launcher_script, base_dir)
        debug_base = _debug_base_candidates(debug_port)[0]
        print("Started local Termux browser for ChatGPT Web auth.")
        print("Open the Termux:X11 Android app manually, then finish logging into ChatGPT in Chromium.")
        success_message = "Stored chatgpt-web credential from Termux browser."
    else:
        browser_command = _find_desktop_browser_command()
        if not browser_command:
            if _is_windows():
                raise SystemExit("No supported browser found. Install Microsoft Edge, Google Chrome, or Chromium.")
            if _is_wsl():
                raise SystemExit("No supported browser found in WSL. Install Chromium/Chrome in WSLg or run this command from native Windows.")
            raise SystemExit("No supported browser found. Install Chromium, Chrome, or Edge.")
        base_root = _chatgpt_web_browser_base_dir(browser_command)
        base_root.mkdir(parents=True, exist_ok=True)
        base_dir = Path(tempfile.mkdtemp(prefix="login-", dir=base_root))
        label_default = "windows-browser" if _is_windows() else ("wsl-browser" if _is_wsl() else "desktop-browser")
        label = (getattr(args, "label", None) or label_default).strip() or label_default
        proc, debug_bases = _launch_chatgpt_web_desktop_browser(
            browser_command,
            base_dir,
            debug_port,
            expose_wsl_host=_is_wsl(),
        )
        if _is_windows():
            print("Started local Windows browser for ChatGPT Web auth.")
            print("Finish logging into ChatGPT in the launched browser window.")
            success_message = "Stored chatgpt-web credential from Windows browser."
        elif _is_wsl():
            print("Started local WSL browser for ChatGPT Web auth.")
            print("Finish logging into ChatGPT in the launched browser window (or WSLg session).")
            success_message = "Stored chatgpt-web credential from WSL browser."
        else:
            print("Started local browser for ChatGPT Web auth.")
            print("Finish logging into ChatGPT in the launched browser window.")
            success_message = "Stored chatgpt-web credential from desktop browser."

    try:
        if _is_termux():
            _wait_for_debugger(debug_base, timeout=min(60.0, float(timeout_seconds)))
        else:
            debug_base = _wait_for_any_debugger(debug_bases, timeout=min(60.0, float(timeout_seconds)))
        browser_auth_state = _wait_for_chatgpt_web_browser_auth_state(
            debug_base,
            timeout_seconds=timeout_seconds,
        )
        if not browser_auth_state:
            raise SystemExit("Timed out waiting for __Secure-next-auth.session-token from Chromium.")
        session_token = str(browser_auth_state.get("session_token") or "").strip()

        auth_add_command(SimpleNamespace(
            provider="chatgpt-web",
            auth_type="api-key",
            api_key=session_token,
            label=label,
            token_mode="session_token",
            cookie_header=str(browser_auth_state.get("cookie_header") or "").strip(),
            browser_cookies=browser_auth_state.get("browser_cookies"),
            device_id=str(browser_auth_state.get("device_id") or "").strip(),
            user_agent=str(browser_auth_state.get("user_agent") or "").strip(),
            portal_url=None,
            inference_url=None,
            client_id=None,
            scope=None,
            no_browser=False,
            timeout=None,
            insecure=False,
            ca_bundle=None,
        ))
        print(success_message)
        print(f'Added it to the credential pool as "{label}".')
        print("Verify with: hermes auth list")
    finally:
        if not keep_open:
            _terminate_process(proc)
            shutil.rmtree(base_dir, ignore_errors=True)


def _wait_for_any_debugger(debug_bases: list[str], timeout: float = 30.0) -> str:
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        for debug_base in debug_bases:
            try:
                with urllib.request.urlopen(f"{debug_base}/json/version", timeout=5) as response:
                    if response.status == 200:
                        return debug_base
            except Exception as exc:
                last_error = exc
        time.sleep(1)
    joined = ", ".join(debug_bases)
    raise SystemExit(f"Timed out waiting for Chromium DevTools at any of [{joined}]: {last_error}")
