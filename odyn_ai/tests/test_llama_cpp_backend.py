import json
import unittest
from unittest.mock import patch

from odyn_ai.cognition.backends import (
    LlamaCppInferenceBackend,
    LlamaCppEndpoint,
)


class LlamaCppInferenceBackendTests(unittest.TestCase):
    def test_generates_from_openai_compatible_chat_completion_endpoint(self):
        backend = LlamaCppInferenceBackend(
            LlamaCppEndpoint(
                base_url="http://127.0.0.1:8080/v1",
                model="primary.gguf",
                api_key="secret",
            )
        )

        response_payload = {
            "choices": [{"message": {"content": "ODYN response"}}],
            "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19},
        }

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return json.dumps(response_payload).encode("utf-8")

            def getcode(self):
                return 200

        with patch("odyn_ai.cognition.backends.urllib.request.urlopen", return_value=FakeResponse()) as opened:
            result = backend.generate("Test prompt", context={"evidence": "source"})

        self.assertEqual(result, "ODYN response")
        request = opened.call_args.args[0]
        body = json.loads(request.data.decode("utf-8"))
        self.assertEqual(body["model"], "primary.gguf")
        self.assertEqual(body["messages"][0]["role"], "user")
        self.assertIn("Test prompt", body["messages"][0]["content"])
        self.assertIn("evidence", body["messages"][0]["content"])
        self.assertEqual(request.get_header("Authorization"), "Bearer secret")

    def test_fails_with_clear_error_on_http_failure(self):
        backend = LlamaCppInferenceBackend(
            LlamaCppEndpoint(base_url="http://127.0.0.1:8080/v1", model="critic.gguf")
        )

        class FakeError:
            code = 401

            def read(self):
                return b'{"error":{"message":"unauthorized"}}'

            def close(self):
                pass

        from urllib.error import HTTPError

        with patch(
            "odyn_ai.cognition.backends.urllib.request.urlopen",
            side_effect=HTTPError(
                "http://127.0.0.1:8080/v1/chat/completions",
                401,
                "Unauthorized",
                {},
                FakeError(),
            ),
        ):
            with self.assertRaises(RuntimeError) as error:
                backend.generate("prompt")

        self.assertIn("401", str(error.exception))
        self.assertIn("unauthorized", str(error.exception).lower())

    def test_rejects_empty_completion(self):
        backend = LlamaCppInferenceBackend(
            LlamaCppEndpoint(base_url="http://127.0.0.1:8080/v1", model="critic.gguf")
        )

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return b'{"choices":[{"message":{"content":""}}]}'

        with patch("odyn_ai.cognition.backends.urllib.request.urlopen", return_value=FakeResponse()):
            with self.assertRaises(RuntimeError):
                backend.generate("prompt")


if __name__ == "__main__":
    unittest.main()
