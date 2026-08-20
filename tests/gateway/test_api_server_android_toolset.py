import asyncio
import threading
from contextlib import contextmanager
from unittest.mock import MagicMock, patch

import pytest

from gateway.config import PlatformConfig


def _release_adapter_task_pins(adapter):
    from tools.process_registry import process_registry

    for task_id in list(adapter._owned_task_ids):
        process_registry.release_task_ownership(task_id)


@pytest.mark.asyncio
async def test_cancelled_android_request_never_constructs_agent_after_runtime_lease_releases(
    monkeypatch,
):
    from gateway.platforms.api_server import APIServerAdapter
    from tools.environments.android_linux import android_embedded_runtime_work_guard

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    adapter = APIServerAdapter(PlatformConfig())
    worker_waiting = threading.Event()
    worker_admitted = threading.Event()
    original_register = adapter._register_owned_task_id
    real_guard = android_embedded_runtime_work_guard

    @contextmanager
    def observable_guard():
        worker_waiting.set()
        with real_guard():
            yield

    def register_task_id(task_id):
        original_register(task_id)
        worker_admitted.set()

    try:
        with (
            patch.object(adapter, "_register_owned_task_id", side_effect=register_task_id),
            patch.object(adapter, "_create_agent") as create_agent,
            patch(
                "tools.environments.android_linux.android_embedded_runtime_work_guard",
                observable_guard,
            ),
        ):
            with real_guard():
                request_task = asyncio.create_task(
                    adapter._run_agent(
                        user_message="queued request",
                        conversation_history=[],
                        session_id="cancel-before-construction",
                        agent_ref=[None],
                    )
                )
                assert await asyncio.to_thread(worker_waiting.wait, 2.0)
                request_task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await request_task

            assert await asyncio.to_thread(worker_admitted.wait, 2.0)
            create_agent.assert_not_called()
    finally:
        _release_adapter_task_pins(adapter)


class TestHermesAndroidAppToolset:
    def test_toolset_exists_and_is_narrow(self):
        from toolsets import get_toolset, resolve_toolset

        toolset = get_toolset("hermes-android-app")
        assert toolset is not None

        resolved = resolve_toolset("hermes-android-app", include_registry=False)
        for expected in [
            "terminal",
            "process_manage",
            "android_device_status",
            "android_system_action",
            "android_shared_folder_list",
            "android_shared_folder_read",
            "android_shared_folder_write",
            "android_ui_snapshot",
            "android_ui_action",
            "read_file",
            "write_file",
            "patch",
            "search_files",
            "skills_list",
            "skill_view",
            "skill_manage",
            "todo_list",
            "memory",
            "session_search",
        ]:
            assert expected in resolved

        for blocked in [
            "image_generate",
            "execute_code",
            "delegate_task",
            "cronjob_manage",
            "web_extract",
            "vision_analyze",
            "web_search",
        ]:
            assert blocked not in resolved


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_forces_android_default_when_bootstrap_env_and_no_config(monkeypatch):
    from gateway.platforms.api_server import APIServerAdapter

    adapter = APIServerAdapter(PlatformConfig())
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

    with patch("gateway.run._resolve_runtime_agent_kwargs") as mock_kwargs, \
         patch("gateway.run._resolve_gateway_model") as mock_model, \
         patch("gateway.run._load_gateway_config") as mock_config, \
         patch("run_agent.AIAgent") as mock_agent_cls:
        mock_kwargs.return_value = {
            "api_key": "***",
            "base_url": None,
            "provider": None,
            "api_mode": None,
            "command": None,
            "args": [],
        }
        mock_model.return_value = "test/model"
        mock_config.return_value = {}
        mock_agent_cls.return_value = MagicMock()

        adapter._create_agent()

        call_kwargs = mock_agent_cls.call_args.kwargs
        assert call_kwargs["enabled_toolsets"] == ["hermes-android-app"]


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_rejects_valid_but_unowned_android_config_override(monkeypatch):
    from gateway.platforms.api_server import APIServerAdapter

    adapter = APIServerAdapter(PlatformConfig())
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

    with patch("gateway.run._resolve_runtime_agent_kwargs") as mock_kwargs, \
         patch("gateway.run._resolve_gateway_model") as mock_model, \
         patch("gateway.run._load_gateway_config") as mock_config, \
         patch("run_agent.AIAgent") as mock_agent_cls:
        mock_kwargs.return_value = {
            "api_key": "***",
            "base_url": None,
            "provider": None,
            "api_mode": None,
            "command": None,
            "args": [],
        }
        mock_model.return_value = "test/model"
        mock_config.return_value = {
            "platform_toolsets": {"api_server": ["hermes-android-app"]},
            "mcp_servers": {"unowned-mcp": {"enabled": True}},
        }
        mock_agent_cls.return_value = MagicMock()

        adapter._create_agent()

        call_kwargs = mock_agent_cls.call_args.kwargs
        assert call_kwargs["enabled_toolsets"] == ["hermes-android-app"]


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_rejects_process_backed_android_fallback_before_construction(monkeypatch):
    from gateway.platforms.api_server import APIServerAdapter

    adapter = APIServerAdapter(PlatformConfig())
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    with (
        patch(
            "gateway.run._resolve_runtime_agent_kwargs",
            return_value={
                "api_key": "***",
                "base_url": "https://openrouter.ai/api/v1",
                "provider": "openrouter",
                "api_mode": "openai",
                "command": None,
                "args": [],
            },
        ),
        patch("gateway.run._resolve_gateway_model", return_value="safe/model"),
        patch("gateway.run._load_gateway_config", return_value={}),
        patch(
            "gateway.run.GatewayRunner._load_fallback_model",
            return_value=[{"provider": "copilot-acp", "model": "unsafe/model"}],
        ),
        patch("run_agent.AIAgent") as agent_cls,
        pytest.raises(RuntimeError, match="fallback.*process-backed"),
    ):
        adapter._create_agent()

    agent_cls.assert_not_called()


def test_android_bootstrap_disables_general_plugin_hooks_and_context_engines(monkeypatch):
    import hermes_cli.plugins as plugins

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    manager = MagicMock()
    manager.invoke_hook.return_value = ["unsafe callback ran"]
    manager._context_engine = MagicMock()

    with patch.object(plugins, "get_plugin_manager", return_value=manager):
        plugins.discover_plugins(force=True)
        assert plugins.invoke_hook("pre_llm_call", user_message="hello") == []
        assert plugins.get_plugin_context_engine() is None
        assert plugins.get_plugin_command_handler("unsafe") is None

    manager.discover_and_load.assert_not_called()
    manager.invoke_hook.assert_not_called()


def test_android_context_engine_policy_forces_builtin_compressor(monkeypatch):
    from hermes_android.agent_lifecycle import _context_engine_name_for_runtime

    config = {"context": {"engine": "user-plugin-engine"}}
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    assert _context_engine_name_for_runtime(config) == "compressor"

    monkeypatch.delenv("HERMES_ANDROID_BOOTSTRAP")
    assert _context_engine_name_for_runtime(config) == "user-plugin-engine"


def test_android_provider_discovery_skips_user_and_legacy_imports(monkeypatch, tmp_path):
    import providers

    bundled_root = tmp_path / "bundled"
    user_root = tmp_path / "user"
    bundled = bundled_root / "bundled-provider"
    user = user_root / "user-provider"
    bundled.mkdir(parents=True)
    user.mkdir(parents=True)
    calls = []
    previous_discovered = providers._discovered
    previous_registry = dict(providers._REGISTRY)
    previous_aliases = dict(providers._ALIASES)

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    monkeypatch.setattr(providers, "_BUNDLED_PLUGINS_DIR", bundled_root)
    monkeypatch.setattr(providers, "_user_plugins_dir", lambda: user_root)
    monkeypatch.setattr(
        providers,
        "_import_plugin_dir",
        lambda path, source: calls.append((path, source)),
    )
    providers._discovered = False
    providers._REGISTRY.clear()
    providers._ALIASES.clear()
    try:
        providers._discover_providers()
    finally:
        providers._REGISTRY.clear()
        providers._REGISTRY.update(previous_registry)
        providers._ALIASES.clear()
        providers._ALIASES.update(previous_aliases)
        providers._discovered = previous_discovered

    assert calls == [(bundled, "bundled")]


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_forces_android_default_for_invalid_override(monkeypatch):
    from gateway.platforms.api_server import APIServerAdapter

    adapter = APIServerAdapter(PlatformConfig())
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

    with patch("gateway.run._resolve_runtime_agent_kwargs") as mock_kwargs, \
         patch("gateway.run._resolve_gateway_model") as mock_model, \
         patch("gateway.run._load_gateway_config") as mock_config, \
         patch("run_agent.AIAgent") as mock_agent_cls:
        mock_kwargs.return_value = {
            "api_key": "***",
            "base_url": None,
            "provider": None,
            "api_mode": None,
            "command": None,
            "args": [],
        }
        mock_model.return_value = "test/model"
        mock_config.return_value = {"platform_toolsets": {"api_server": ["does-not-exist"]}}
        mock_agent_cls.return_value = MagicMock()

        adapter._create_agent()

        call_kwargs = mock_agent_cls.call_args.kwargs
        assert call_kwargs["enabled_toolsets"] == ["hermes-android-app"]


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_fails_closed_when_android_safe_toolset_policy_cannot_load(monkeypatch):
    from gateway.platforms.api_server import APIServerAdapter

    adapter = APIServerAdapter(PlatformConfig())
    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")

    with (
        patch("gateway.run._resolve_runtime_agent_kwargs") as mock_kwargs,
        patch("gateway.run._resolve_gateway_model", return_value="test/model"),
            patch("gateway.run._load_gateway_config", return_value={}),
            patch(
                "hermes_android.mobile_defaults.resolved_android_api_server_toolsets",
                side_effect=RuntimeError("policy import failed"),
            ),
        patch("run_agent.AIAgent") as mock_agent_cls,
        pytest.raises(RuntimeError, match="safe toolset policy is unavailable"),
    ):
        mock_kwargs.return_value = {
            "api_key": "***",
            "base_url": None,
            "provider": None,
            "api_mode": None,
            "command": None,
            "args": [],
        }
        adapter._create_agent()

    mock_agent_cls.assert_not_called()


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_rejects_process_poison_before_agent_or_provider_work(monkeypatch):
    from gateway.platforms.api_server import APIServerAdapter

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    adapter = APIServerAdapter(PlatformConfig())

    with (
        patch(
            "tools.environments.android_linux.android_command_execution_requires_restart",
            return_value="Force stop and reopen Hermes before another request",
        ),
        patch("run_agent.AIAgent") as mock_agent_cls,
        pytest.raises(RuntimeError, match="Force stop and reopen"),
    ):
        adapter._create_agent()

    mock_agent_cls.assert_not_called()


@patch("gateway.platforms.api_server.AIOHTTP_AVAILABLE", True)
def test_create_agent_retains_and_stops_agent_when_android_publication_is_interrupted(
    monkeypatch,
):
    from gateway.platforms.api_server import APIServerAdapter

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    adapter = APIServerAdapter(PlatformConfig())
    agent = MagicMock()
    agent.session_id = "publication-interrupted"

    with (
        patch(
            "gateway.run._resolve_runtime_agent_kwargs",
            return_value={
                "api_key": "***",
                "base_url": None,
                "provider": None,
                "api_mode": None,
                "command": None,
                "args": [],
            },
        ),
        patch("gateway.run._resolve_gateway_model", return_value="test/model"),
        patch("gateway.run._load_gateway_config", return_value={}),
        patch("run_agent.AIAgent", return_value=agent),
        patch.object(
            adapter,
            "_register_owned_agent",
            side_effect=KeyboardInterrupt("publication interrupted"),
        ),
        pytest.raises(KeyboardInterrupt, match="publication interrupted"),
    ):
        adapter._create_agent()

    assert agent in adapter._owned_agents
    agent.begin_owned_worker_shutdown.assert_called_once_with(
        "API server agent publication failed"
    )


def test_register_owned_task_id_keeps_inventory_when_registry_retain_is_interrupted(
    monkeypatch,
):
    from gateway.platforms.api_server import APIServerAdapter
    from tools.process_registry import process_registry

    monkeypatch.setenv("HERMES_ANDROID_BOOTSTRAP", "1")
    adapter = APIServerAdapter(PlatformConfig())
    task_id = "partially-retained-task"
    retain_task_ownership = process_registry.retain_task_ownership

    def retain_then_interrupt(candidate_task_id):
        retain_task_ownership(candidate_task_id)
        raise KeyboardInterrupt("retain interrupted")

    try:
        with (
            patch.object(
                process_registry,
                "retain_task_ownership",
                side_effect=retain_then_interrupt,
            ),
            pytest.raises(KeyboardInterrupt, match="retain interrupted"),
        ):
            adapter._register_owned_task_id(task_id)

        assert adapter._owned_task_ids == {task_id}
    finally:
        process_registry.release_task_ownership(task_id)
