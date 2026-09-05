"""Browser discovery must not block the loop or select a lookalike origin."""

import io
import json
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from hermes_cli import auth_browser, chatgpt_web


@pytest.mark.asyncio
@pytest.mark.parametrize("surface", ["auth", "fetch"])
async def test_page_discovery_is_off_loop_and_rejects_spoofed_origins(monkeypatch, surface):
    loop_thread = threading.get_ident()
    readers = []
    pages = [{"type": "page", "url": url, "webSocketDebuggerUrl": "ws://spoof.invalid"} for url in (
        "https://chatgpt.com.attacker.invalid/", "https://other.invalid/?next=chatgpt.com",
        "http://chatgpt.com/", "file:///tmp/chatgpt.com",
    )]

    def read_pages(*args, **kwargs):
        readers.append(threading.get_ident())
        return io.StringIO(json.dumps(pages))

    def forbidden(*args, **kwargs):
        raise AssertionError("a spoofed page must never receive browser commands")

    monkeypatch.setattr(chatgpt_web.urllib.request, "urlopen", read_pages)
    monkeypatch.setattr(auth_browser, "websockets", SimpleNamespace(connect=forbidden))
    monkeypatch.setattr(chatgpt_web, "websockets", SimpleNamespace(connect=forbidden))
    if surface == "auth":
        assert await auth_browser._get_chatgpt_web_browser_auth_state("http://127.0.0.1:1") is None
    else:
        create = AsyncMock(return_value="new-target")
        monkeypatch.setattr(chatgpt_web, "_chatgpt_web_browser_create_target", create)
        monkeypatch.setattr(chatgpt_web, "_chatgpt_web_browser_page_target", lambda *_: {})
        with pytest.raises(RuntimeError, match="has no DevTools websocket"):
            await chatgpt_web._chatgpt_web_browser_fetch(debug_base="http://127.0.0.1:1", url="https://chatgpt.com/")
        create.assert_awaited_once_with("http://127.0.0.1:1", "https://chatgpt.com/")
    assert readers and all(thread != loop_thread for thread in readers)


def test_only_the_exact_https_chatgpt_page_is_eligible():
    assert chatgpt_web._is_chatgpt_browser_page({"type": "page", "url": "https://chatgpt.com/c/example"})
    assert not chatgpt_web._is_chatgpt_browser_page({"type": "worker", "url": "https://chatgpt.com/"})
