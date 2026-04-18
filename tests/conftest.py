import asyncio

import pytest

from gateway.session_context import _UNSET, _VAR_MAP
import tools.approval as approval_module


<<<<<<< HEAD
# ── Credential env-var filter ──────────────────────────────────────────────
#
# Any env var in the current process matching ONE of these patterns is
# unset for every test. Developers' local keys cannot leak into assertions
# about "auto-detect provider when key present".

_CREDENTIAL_SUFFIXES = (
    "_API_KEY",
    "_TOKEN",
    "_SECRET",
    "_PASSWORD",
    "_CREDENTIALS",
    "_ACCESS_KEY",
    "_SECRET_ACCESS_KEY",
    "_PRIVATE_KEY",
    "_OAUTH_TOKEN",
    "_WEBHOOK_SECRET",
    "_ENCRYPT_KEY",
    "_APP_SECRET",
    "_CLIENT_SECRET",
    "_CORP_SECRET",
    "_AES_KEY",
)

# Explicit names (for ones that don't fit the suffix pattern)
_CREDENTIAL_NAMES = frozenset({
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "ANTHROPIC_TOKEN",
    "FAL_KEY",
    "GH_TOKEN",
    "GITHUB_TOKEN",
    "OPENAI_API_KEY",
    "OPENROUTER_API_KEY",
    "NOUS_API_KEY",
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
    "GROQ_API_KEY",
    "XAI_API_KEY",
    "MISTRAL_API_KEY",
    "DEEPSEEK_API_KEY",
    "KIMI_API_KEY",
    "MOONSHOT_API_KEY",
    "GLM_API_KEY",
    "ZAI_API_KEY",
    "MINIMAX_API_KEY",
    "OLLAMA_API_KEY",
    "OPENVIKING_API_KEY",
    "COPILOT_API_KEY",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "BROWSERBASE_API_KEY",
    "FIRECRAWL_API_KEY",
    "PARALLEL_API_KEY",
    "EXA_API_KEY",
    "TAVILY_API_KEY",
    "WANDB_API_KEY",
    "ELEVENLABS_API_KEY",
    "HONCHO_API_KEY",
    "MEM0_API_KEY",
    "SUPERMEMORY_API_KEY",
    "RETAINDB_API_KEY",
    "HINDSIGHT_API_KEY",
    "HINDSIGHT_LLM_API_KEY",
    "TINKER_API_KEY",
    "DAYTONA_API_KEY",
    "TWILIO_AUTH_TOKEN",
    "TELEGRAM_BOT_TOKEN",
    "DISCORD_BOT_TOKEN",
    "SLACK_BOT_TOKEN",
    "SLACK_APP_TOKEN",
    "MATTERMOST_TOKEN",
    "MATRIX_ACCESS_TOKEN",
    "MATRIX_PASSWORD",
    "MATRIX_RECOVERY_KEY",
    "HASS_TOKEN",
    "EMAIL_PASSWORD",
    "BLUEBUBBLES_PASSWORD",
    "FEISHU_APP_SECRET",
    "FEISHU_ENCRYPT_KEY",
    "FEISHU_VERIFICATION_TOKEN",
    "DINGTALK_CLIENT_SECRET",
    "QQ_CLIENT_SECRET",
    "QQ_STT_API_KEY",
    "WECOM_SECRET",
    "WECOM_CALLBACK_CORP_SECRET",
    "WECOM_CALLBACK_TOKEN",
    "WECOM_CALLBACK_ENCODING_AES_KEY",
    "WEIXIN_TOKEN",
    "MODAL_TOKEN_ID",
    "MODAL_TOKEN_SECRET",
    "TERMINAL_SSH_KEY",
    "SUDO_PASSWORD",
    "GATEWAY_PROXY_KEY",
    "API_SERVER_KEY",
    "TOOL_GATEWAY_USER_TOKEN",
    "TELEGRAM_WEBHOOK_SECRET",
    "WEBHOOK_SECRET",
    "AI_GATEWAY_API_KEY",
    "VOICE_TOOLS_OPENAI_KEY",
    "BROWSER_USE_API_KEY",
    "CUSTOM_API_KEY",
    "GATEWAY_PROXY_URL",
    "GEMINI_BASE_URL",
    "OPENAI_BASE_URL",
    "OPENROUTER_BASE_URL",
    "OLLAMA_BASE_URL",
    "GROQ_BASE_URL",
    "XAI_BASE_URL",
    "AI_GATEWAY_BASE_URL",
    "ANTHROPIC_BASE_URL",
})


def _looks_like_credential(name: str) -> bool:
    """True if env var name matches a credential-shaped pattern."""
    if name in _CREDENTIAL_NAMES:
        return True
    return any(name.endswith(suf) for suf in _CREDENTIAL_SUFFIXES)


# HERMES_* vars that change test behavior by being set. Unset all of these
# unconditionally — individual tests that need them set do so explicitly.
_HERMES_BEHAVIORAL_VARS = frozenset({
    "HERMES_YOLO_MODE",
    "HERMES_INTERACTIVE",
    "HERMES_QUIET",
    "HERMES_TOOL_PROGRESS",
    "HERMES_TOOL_PROGRESS_MODE",
    "HERMES_MAX_ITERATIONS",
    "HERMES_SESSION_PLATFORM",
    "HERMES_SESSION_CHAT_ID",
    "HERMES_SESSION_CHAT_NAME",
    "HERMES_SESSION_THREAD_ID",
    "HERMES_SESSION_SOURCE",
    "HERMES_SESSION_KEY",
    "HERMES_GATEWAY_SESSION",
    "HERMES_PLATFORM",
    "HERMES_INFERENCE_PROVIDER",
    "HERMES_MANAGED",
    "HERMES_DEV",
    "HERMES_CONTAINER",
    "HERMES_EPHEMERAL_SYSTEM_PROMPT",
    "HERMES_TIMEZONE",
    "HERMES_REDACT_SECRETS",
    "HERMES_BACKGROUND_NOTIFICATIONS",
    "HERMES_EXEC_ASK",
    "HERMES_HOME_MODE",
    "BROWSER_CDP_URL",
    "CAMOFOX_URL",
    # Platform allowlists — not credentials, but if set from any source
    # (user shell, earlier leaky test, CI env), they change gateway auth
    # behavior and flake button-authorization tests.
    "TELEGRAM_ALLOWED_USERS",
    "DISCORD_ALLOWED_USERS",
    "WHATSAPP_ALLOWED_USERS",
    "SLACK_ALLOWED_USERS",
    "SIGNAL_ALLOWED_USERS",
    "SIGNAL_GROUP_ALLOWED_USERS",
    "EMAIL_ALLOWED_USERS",
    "SMS_ALLOWED_USERS",
    "MATTERMOST_ALLOWED_USERS",
    "MATRIX_ALLOWED_USERS",
    "DINGTALK_ALLOWED_USERS",
    "FEISHU_ALLOWED_USERS",
    "WECOM_ALLOWED_USERS",
    "GATEWAY_ALLOWED_USERS",
    "GATEWAY_ALLOW_ALL_USERS",
    "TELEGRAM_ALLOW_ALL_USERS",
    "DISCORD_ALLOW_ALL_USERS",
    "WHATSAPP_ALLOW_ALL_USERS",
    "SLACK_ALLOW_ALL_USERS",
    "SIGNAL_ALLOW_ALL_USERS",
    "EMAIL_ALLOW_ALL_USERS",
    "SMS_ALLOW_ALL_USERS",
})
=======
def _reset_approval_module_state() -> None:
    for attr in (
        "_gateway_queues",
        "_gateway_notify_cbs",
        "_session_approved",
        "_permanent_approved",
        "_pending",
        "_session_yolo",
    ):
        try:
            getattr(approval_module, attr).clear()
        except Exception:
            pass
    approval_module._approval_session_key.set("")
>>>>>>> 9dc97295 (Fix post-rebase regressions)


@pytest.fixture(autouse=True)
def _reset_shared_contextvars(tmp_path, monkeypatch):
    """Reset cross-test contextvars that otherwise leak within one thread."""
    hermes_home = tmp_path / "hermes_test_home"
    hermes_home.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("HERMES_HOME", str(hermes_home))

    created_loop = None
    try:
<<<<<<< HEAD
        import hermes_cli.plugins as _plugins_mod
        monkeypatch.setattr(_plugins_mod, "_plugin_manager", None)
    except Exception:
        pass


# Backward-compat alias — old tests reference this fixture name. Keep it
# as a no-op wrapper so imports don't break.
@pytest.fixture(autouse=True)
def _isolate_hermes_home(_hermetic_environment):
    """Alias preserved for any test that yields this name explicitly."""
    return None


# ── Module-level state reset ───────────────────────────────────────────────
#
# Python modules are singletons per process, and pytest-xdist workers are
# long-lived. Module-level dicts/sets (tool registries, approval state,
# interrupt flags) and ContextVars persist across tests in the same worker,
# causing tests that pass alone to fail when run with siblings.
#
# Each entry in this fixture clears state that belongs to a specific module.
# New state buckets go here too — this is the single gate that prevents
# "works alone, flakes in CI" bugs from state leakage.
#
# The skill `test-suite-cascade-diagnosis` documents the concrete patterns
# this closes; the running example was `test_command_guards` failing 12/15
# CI runs because ``tools.approval._session_approved`` carried approvals
# from one test's session into another's.

@pytest.fixture(autouse=True)
def _reset_module_state():
    """Clear module-level mutable state and ContextVars between tests.

    Keeps state from leaking across tests on the same xdist worker. Modules
    that don't exist yet (test collection before production import) are
    skipped silently — production import later creates fresh empty state.
    """
    # --- tools.approval — the single biggest source of cross-test pollution ---
    try:
        from tools import approval as _approval_mod
        _approval_mod._session_approved.clear()
        _approval_mod._session_yolo.clear()
        _approval_mod._permanent_approved.clear()
        _approval_mod._pending.clear()
        _approval_mod._gateway_queues.clear()
        _approval_mod._gateway_notify_cbs.clear()
        # ContextVar: reset to empty string so get_current_session_key()
        # falls through to the env var / default path, matching a fresh
        # process.
        _approval_mod._approval_session_key.set("")
    except Exception:
        pass

    # --- tools.interrupt — per-thread interrupt flag set ---
    try:
        from tools import interrupt as _interrupt_mod
        with _interrupt_mod._lock:
            _interrupt_mod._interrupted_threads.clear()
    except Exception:
        pass

    # --- gateway.session_context — 9 ContextVars that represent
    #     the active gateway session. If set in one test and not reset,
    #     the next test's get_session_env() reads stale values.
    try:
        from gateway import session_context as _sc_mod
        for _cv in (
            _sc_mod._SESSION_PLATFORM,
            _sc_mod._SESSION_CHAT_ID,
            _sc_mod._SESSION_CHAT_NAME,
            _sc_mod._SESSION_THREAD_ID,
            _sc_mod._SESSION_USER_ID,
            _sc_mod._SESSION_USER_NAME,
            _sc_mod._SESSION_KEY,
            _sc_mod._CRON_AUTO_DELIVER_PLATFORM,
            _sc_mod._CRON_AUTO_DELIVER_CHAT_ID,
            _sc_mod._CRON_AUTO_DELIVER_THREAD_ID,
        ):
            _cv.set(_sc_mod._UNSET)
    except Exception:
        pass

    # --- tools.env_passthrough — ContextVar<set[str]> with no default ---
    # LookupError is normal if the test never set it. Setting it to an
    # empty set unconditionally normalizes the starting state.
    try:
        from tools import env_passthrough as _envp_mod
        _envp_mod._allowed_env_vars_var.set(set())
    except Exception:
        pass

    # --- tools.credential_files — ContextVar<dict> ---
    try:
        from tools import credential_files as _credf_mod
        _credf_mod._registered_files_var.set({})
    except Exception:
        pass

    # --- tools.file_tools — per-task read history + file-ops cache ---
    # _read_tracker accumulates per-task_id read history for loop detection,
    # capped by _READ_HISTORY_CAP. If entries from a prior test persist, the
    # cap is hit faster than expected and capacity-related tests flake.
    try:
        from tools import file_tools as _ft_mod
        with _ft_mod._read_tracker_lock:
            _ft_mod._read_tracker.clear()
        with _ft_mod._file_ops_lock:
            _ft_mod._file_ops_cache.clear()
    except Exception:
        pass

    yield


@pytest.fixture()
def tmp_dir(tmp_path):
    """Provide a temporary directory that is cleaned up automatically."""
    return tmp_path


@pytest.fixture()
def mock_config():
    """Return a minimal hermes config dict suitable for unit tests."""
    return {
        "model": "test/mock-model",
        "toolsets": ["terminal", "file"],
        "max_turns": 10,
        "terminal": {
            "backend": "local",
            "cwd": "/tmp",
            "timeout": 30,
        },
        "compression": {"enabled": False},
        "memory": {"memory_enabled": False, "user_profile_enabled": False},
        "command_allowlist": [],
    }


# ── Global test timeout ─────────────────────────────────────────────────────
# Kill any individual test that takes longer than 30 seconds.
# Prevents hanging tests (subprocess spawns, blocking I/O) from stalling the
# entire test suite.

def _timeout_handler(signum, frame):
    raise TimeoutError("Test exceeded 30 second timeout")

@pytest.fixture(autouse=True)
def _ensure_current_event_loop(request):
    """Provide a default event loop for sync tests that call get_event_loop().

    Python 3.11+ no longer guarantees a current loop for plain synchronous tests.
    A number of gateway tests still use asyncio.get_event_loop().run_until_complete(...).
    Ensure they always have a usable loop without interfering with pytest-asyncio's
    own loop management for @pytest.mark.asyncio tests.
    """
    if request.node.get_closest_marker("asyncio") is not None:
        yield
        return

    try:
        loop = asyncio.get_event_loop_policy().get_event_loop()
=======
        asyncio.get_event_loop()
>>>>>>> 9dc97295 (Fix post-rebase regressions)
    except RuntimeError:
        created_loop = asyncio.new_event_loop()
        asyncio.set_event_loop(created_loop)

    for var in _VAR_MAP.values():
        var.set(_UNSET)
    _reset_approval_module_state()
    yield
    for var in _VAR_MAP.values():
        var.set(_UNSET)
    _reset_approval_module_state()
    if created_loop is not None:
        try:
            pending = [task for task in asyncio.all_tasks(created_loop) if not task.done()]
            for task in pending:
                task.cancel()
            if pending:
                created_loop.run_until_complete(
                    asyncio.gather(*pending, return_exceptions=True)
                )
        except Exception:
            pass
        finally:
            asyncio.set_event_loop(None)
            created_loop.close()
