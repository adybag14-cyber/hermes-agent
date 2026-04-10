"""ChatGPT Web media behavior for the agent."""

from __future__ import annotations

import os
import re
from hermes_cli import chatgpt_web as _chatgpt_web
import json
from agent.prompt_builder import DEFAULT_AGENT_IDENTITY
from agent.chatgpt_web_parsing import _CHATGPT_WEB_HERMES_INTRO
from pathlib import Path
from hermes_constants import get_hermes_home
import uuid


class ChatGPTWebMediaMixin:
    @staticmethod
    def _chatgpt_web_extract_local_path(text: str, *, extensions: Optional[tuple[str, ...]] = None) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        stripped = text.strip()
        candidates: list[str] = []
        for pattern in (
            r'"((?:[A-Za-z]:[\\/]|~|/)[^"\n]+)"',
            r"'((?:[A-Za-z]:[\\/]|~|/)[^'\n]+)'",
            r"`((?:[A-Za-z]:[\\/]|~|/)[^`\n]+)`",
            r"(?<![A-Za-z0-9_.-])((?:[A-Za-z]:[\\/]|~|/)[A-Za-z0-9_./:\\\\ -]+?)(?=[\s,;!?)]|$)",
        ):
            for match in re.finditer(pattern, stripped):
                candidate = match.group(1).strip().rstrip('.,;:!')
                if candidate:
                    candidates.append(candidate)
        seen: set[str] = set()
        for candidate in candidates:
            normalized = os.path.expanduser(candidate)
            if normalized in seen:
                continue
            seen.add(normalized)
            if normalized.startswith("//") and re.match(r"^//[^/\s]+\.[^/\s]+(?:/|$)", normalized):
                continue
            if extensions is not None:
                lowered = normalized.lower()
                if not lowered.endswith(tuple(ext.lower() for ext in extensions)):
                    continue
            return normalized
        return None

    @staticmethod
    def _chatgpt_web_extract_image_input_path(text: str) -> Optional[str]:
        from run_agent import AIAgent
        return AIAgent._chatgpt_web_extract_local_path(
            text,
            extensions=(".png", ".jpg", ".jpeg", ".webp", ".gif"),
        )

    @staticmethod
    def _chatgpt_web_build_multimodal_user_content(text: str) -> Any:
        from run_agent import AIAgent
        if not _chatgpt_web._chatgpt_web_debug_base():
            return text
        image_path = AIAgent._chatgpt_web_extract_image_input_path(text)
        if not image_path:
            return text
        prompt_text = str(text or "")
        replacements = [
            json.dumps(image_path),
            f'"{image_path}"',
            f"'{image_path}'",
            image_path,
        ]
        for replacement in replacements:
            prompt_text = prompt_text.replace(replacement, "the attached image")
        prompt_text = re.sub(
            r"(?i)\blocal image\s*:\s*the attached image\b",
            "the attached image",
            prompt_text,
        )
        prompt_text = re.sub(r"\s{2,}", " ", prompt_text).strip()
        return [
            {"type": "text", "text": prompt_text or "Please analyze the attached image."},
            {"type": "input_image", "image_url": image_path},
        ]

    @staticmethod
    def _chatgpt_web_extract_symbol_target(text: str) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        stopwords = {"and", "the", "a", "an", "where", "with", "this", "that", "it", "in"}
        patterns = (
            r"\bwhere\s+([A-Za-z_][A-Za-z0-9_]*)\s+is\s+defined\b",
            r"\b([A-Za-z_][A-Za-z0-9_]*)\s+is\s+defined\b",
            r"\bdefinition\s+of\s+[`\"']?([A-Za-z_][A-Za-z0-9_]*)",
            r"\bwhere\s+[`\"']?([A-Za-z_][A-Za-z0-9_]*)\s+is\s+defined",
            r"\b(?:define|defines|defined)\s+[`\"']?([A-Za-z_][A-Za-z0-9_]*)",
            r"\bfor\s+([A-Za-z_][A-Za-z0-9_]*)",
        )
        for pattern in patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if not match:
                continue
            candidate = match.group(1)
            if candidate.lower() in stopwords:
                continue
            return candidate
        return None

    @staticmethod
    def _chatgpt_web_sanitize_skill_name(name: str) -> str:
        sanitized = re.sub(r"[^a-z0-9_-]+", "-", str(name or "").strip().lower())
        sanitized = sanitized.strip("-_")
        return sanitized[:64]

    def _chatgpt_web_build_skill_content(self, name: str, description: str) -> str:
        clean_name = self._chatgpt_web_sanitize_skill_name(name) or "chatgpt-web-temp-skill"
        clean_desc = (description or "Temporary skill created from a ChatGPT Web request.").strip()
        body_desc = clean_desc.rstrip(".") + "."
        return (
            f"---\n"
            f"name: {clean_name}\n"
            f"description: {clean_desc}\n"
            f"version: 1.0.0\n"
            f"author: Hermes Agent\n"
            f"license: MIT\n"
            f"---\n\n"
            f"# {clean_name}\n\n"
            f"## Purpose\n"
            f"{body_desc}\n\n"
            f"## When To Use\n"
            f"- Use this skill when the request matches this workflow or description.\n"
            f"- Prefer this skill over re-deriving the same steps from scratch.\n\n"
            f"## Inputs\n"
            f"- Confirm the target files, commands, environment, or system scope before changing anything.\n"
            f"- Gather any missing prerequisites with Hermes tools before acting.\n\n"
            f"## Workflow\n"
            f"1. Restate the concrete goal in one sentence.\n"
            f"2. Inspect the relevant files, commands, or runtime state before editing or executing.\n"
            f"3. Make the smallest concrete change that satisfies the request.\n"
            f"4. Verify the result with the most direct test, command, or inspection available.\n"
            f"5. Report what changed, what was verified, and any remaining risk.\n\n"
            f"## Validation\n"
            f"- Re-run the exact command, test, or inspection that proves the workflow succeeded.\n"
            f"- If verification is not possible, say precisely what is missing.\n\n"
            f"## Pitfalls\n"
            f"- Do not assume paths, dependencies, or credentials without checking them.\n"
            f"- Update this skill when you discover a better command, a missing step, or a new failure mode.\n"
        )

    def _chatgpt_web_enrich_instructions(self, instructions: str) -> str:
        base = str(instructions or "").strip() or DEFAULT_AGENT_IDENTITY
        if _CHATGPT_WEB_HERMES_INTRO in base:
            return base
        return f"{base}\n\n{_CHATGPT_WEB_HERMES_INTRO}"

    @staticmethod
    def _chatgpt_web_default_image_download_dir() -> Path:
        termux_dir = Path.home() / "storage" / "downloads" / "chatgpt-web-images"
        if termux_dir.parent.exists():
            return termux_dir
        return get_hermes_home() / "downloads" / "chatgpt-web-images"

    def _chatgpt_web_requested_image_download_dir(self, original_request: str) -> Optional[Path]:
        if not isinstance(original_request, str) or not original_request.strip():
            return None
        lowered = original_request.lower()
        if not any(keyword in lowered for keyword in ("save", "download", "store", "upload")):
            return None
        explicit_path = re.search(
            r"\b(?:save|download|store|upload)(?:\s+it)?\s+to\s+(.+?)(?:\.\s*answer only.*|$)",
            original_request,
            re.IGNORECASE | re.DOTALL,
        )
        if explicit_path:
            parsed_path = self._chatgpt_web_extract_local_path(explicit_path.group(1))
            if parsed_path:
                return Path(os.path.expanduser(parsed_path))
        if "downloads" in lowered or "chatgpt-web-images" in lowered or "chatgpt web images" in lowered:
            return self._chatgpt_web_default_image_download_dir()
        return None

    @staticmethod
    def _chatgpt_web_extract_image_url_from_text(text: str) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        markdown_match = re.search(r"!\[[^\]]*\]\((https?://[^)\s]+)\)", text)
        if markdown_match:
            return markdown_match.group(1)
        estuary_match = re.search(r"(https?://[^\s)]+/backend-api/estuary/content\?[^\s)]+)", text, re.IGNORECASE)
        if estuary_match:
            return estuary_match.group(1)
        bare_match = re.search(r"(https?://\S+?\.(?:png|jpe?g|gif|webp)(?:\?\S*)?)", text, re.IGNORECASE)
        if bare_match:
            return bare_match.group(1)
        return None

    def _chatgpt_web_extract_generated_image_url(self, final_response: str, messages: list[dict[str, Any]]) -> Optional[str]:
        direct = self._chatgpt_web_extract_image_url_from_text(final_response)
        if direct:
            return direct
        for item in reversed(messages):
            if not isinstance(item, dict) or item.get("role") != "tool":
                continue
            tool_content = str(item.get("content") or "")
            tool_payload = self._chatgpt_web_parse_tool_payload(tool_content)
            if isinstance(tool_payload, dict):
                image_url = tool_payload.get("image")
                if isinstance(image_url, str) and image_url.strip():
                    return image_url.strip()
                images = tool_payload.get("images")
                if isinstance(images, list):
                    for image in images:
                        if isinstance(image, str) and image.strip():
                            return image.strip()
                        if isinstance(image, dict):
                            candidate = image.get("url")
                            if isinstance(candidate, str) and candidate.strip():
                                return candidate.strip()
            fallback = self._chatgpt_web_extract_image_url_from_text(tool_content)
            if fallback:
                return fallback
        return None

    def _chatgpt_web_download_image_to_dir(self, image_url: str, target_dir: Path) -> Path:
        import httpx
        from urllib.parse import unquote, urlparse

        target_dir = Path(target_dir).expanduser()
        target_dir.mkdir(parents=True, exist_ok=True)

        parsed = urlparse(str(image_url or ""))
        candidate_name = Path(parsed.path).name or f"chatgpt-web-image-{uuid.uuid4().hex[:8]}.png"
        candidate_name = re.sub(r"[^A-Za-z0-9._-]+", "-", candidate_name).strip("-._") or f"chatgpt-web-image-{uuid.uuid4().hex[:8]}.png"

        request_headers = None
        if (
            self.api_mode == "chatgpt_web"
            and parsed.scheme in {"http", "https"}
            and parsed.netloc.lower().endswith("chatgpt.com")
            and parsed.path.startswith("/backend-api/")
        ):
            request_headers = _chatgpt_web._build_chatgpt_web_headers(
                access_token=self.api_key,
                session_token=self._chatgpt_web_session_token,
                cookie_header=self._chatgpt_web_cookie_header,
                browser_cookies=self._chatgpt_web_browser_cookies,
                user_agent=self._chatgpt_web_user_agent,
                device_id=self._chatgpt_web_device_id,
                accept="*/*",
            )
            request_headers.pop("Content-Type", None)

        response = httpx.get(image_url, headers=request_headers, timeout=60.0, follow_redirects=True)
        response.raise_for_status()

        content_disposition = str(response.headers.get("content-disposition") or "")
        disposition_match = re.search(r"filename\*=UTF-8''([^;]+)", content_disposition)
        if disposition_match:
            disposition_name = unquote(disposition_match.group(1))
            candidate_name = disposition_name.strip() or candidate_name
        else:
            fallback_match = re.search(r'filename="?([^";]+)"?', content_disposition)
            if fallback_match:
                candidate_name = fallback_match.group(1).strip() or candidate_name

        candidate_name = re.sub(r"[^A-Za-z0-9._-]+", "-", candidate_name).strip("-._") or f"chatgpt-web-image-{uuid.uuid4().hex[:8]}.png"

        suffix = Path(candidate_name).suffix.lower()
        if not suffix:
            content_type = str(response.headers.get("content-type") or "").lower()
            if "jpeg" in content_type or "jpg" in content_type:
                suffix = ".jpg"
            elif "webp" in content_type:
                suffix = ".webp"
            elif "gif" in content_type:
                suffix = ".gif"
            else:
                suffix = ".png"
            candidate_name += suffix

        destination = target_dir / candidate_name
        if destination.exists():
            destination = target_dir / f"{destination.stem}-{uuid.uuid4().hex[:8]}{destination.suffix}"

        destination.write_bytes(response.content)
        return destination

    def _chatgpt_web_postprocess_generated_image_response(
        self,
        original_request: str,
        final_response: str,
        messages: list[dict[str, Any]],
    ) -> str:
        download_dir = self._chatgpt_web_requested_image_download_dir(original_request)
        if download_dir is None:
            return final_response
        image_url = self._chatgpt_web_extract_generated_image_url(final_response, messages)
        if not image_url:
            return final_response
        try:
            saved_path = self._chatgpt_web_download_image_to_dir(image_url, download_dir)
        except Exception:
            return final_response
        if self._chatgpt_web_answer_only_mode(original_request) == "path":
            return str(saved_path)
        return f"{final_response}\n\nSaved image to {saved_path}"
