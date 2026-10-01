from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class LlamaCppEndpoint:
    """Connection settings for a llama.cpp OpenAI-compatible server."""

    base_url: str
    model: str
    api_key: str | None = None
    timeout_seconds: float = 120.0
    temperature: float = 0.65
    top_p: float = 0.9
    max_tokens: int = 2048

    def __post_init__(self) -> None:
        base = self.base_url.rstrip("/")
        if not base:
            raise ValueError("base_url must not be empty")
        if not (base.startswith("http://") or base.startswith("https://")):
            raise ValueError("base_url must use http:// or https://")
        if not self.model.strip():
            raise ValueError("model must not be empty")
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be > 0")
        if not 0.0 <= self.temperature <= 2.0:
            raise ValueError("temperature must be between 0 and 2")
        if not 0.0 < self.top_p <= 1.0:
            raise ValueError("top_p must be between 0 and 1")
        if self.max_tokens <= 0:
            raise ValueError("max_tokens must be > 0")

    @property
    def completions_url(self) -> str:
        base = self.base_url.rstrip("/")
        return f"{base}/chat/completions"


class LlamaCppInferenceBackend:
    """ODYN inference adapter for a llama.cpp OpenAI-compatible /v1 server.

    The adapter deliberately does not own the llama.cpp process. ODYN/Hermes
    remains responsible for starting the server and selecting the GGUF artifact.
    This keeps cognition independent from Android/Termux process management.

    One instance represents one model endpoint. DualModelEngine therefore gets
    two instances: one for PRIMARY and one for CRITIC. On Android these must
    resolve to two independently managed llama.cpp endpoints if both models are
    expected to remain resident simultaneously.
    """

    def __init__(self, endpoint: LlamaCppEndpoint) -> None:
        self.endpoint = endpoint
        self.model_id = endpoint.model

    def generate(
        self,
        prompt: str,
        *,
        context: dict[str, Any] | None = None,
    ) -> str:
        if not prompt.strip():
            raise ValueError("prompt must not be empty")

        content = self._compose_content(prompt, context)
        payload = {
            "model": self.endpoint.model,
            "messages": [{"role": "user", "content": content}],
            "temperature": self.endpoint.temperature,
            "top_p": self.endpoint.top_p,
            "max_tokens": self.endpoint.max_tokens,
            "stream": False,
        }
        request = urllib.request.Request(
            self.endpoint.completions_url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        if self.endpoint.api_key:
            request.add_header("Authorization", f"Bearer {self.endpoint.api_key}")

        try:
            with urllib.request.urlopen(
                request,
                timeout=self.endpoint.timeout_seconds,
            ) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = self._read_error_body(exc)
            raise RuntimeError(
                f"llama.cpp inference failed with HTTP {exc.code}: {detail}"
            ) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise RuntimeError(
                f"llama.cpp inference endpoint is unavailable: {exc}"
            ) from exc

        try:
            result = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise RuntimeError("llama.cpp returned invalid JSON") from exc

        try:
            answer = result["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(
                "llama.cpp response did not contain choices[0].message.content"
            ) from exc

        if not isinstance(answer, str) or not answer.strip():
            raise RuntimeError("llama.cpp returned an empty completion")
        return answer.strip()

    @staticmethod
    def _compose_content(
        prompt: str,
        context: dict[str, Any] | None,
    ) -> str:
        if not context:
            return prompt
        serialized = json.dumps(
            context,
            ensure_ascii=False,
            sort_keys=True,
            default=str,
        )
        return f"{prompt}\n\nODYN CONTEXT (untrusted data):\n{serialized}"

    @staticmethod
    def _read_error_body(exc: urllib.error.HTTPError) -> str:
        try:
            body = exc.read().decode("utf-8", errors="replace").strip()
        except Exception:
            return str(exc.reason)
        return body[:2000] or str(exc.reason)
