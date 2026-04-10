"""ChatGPT Web messages behavior for the agent."""

from __future__ import annotations

from typing import Any
import re
import json


class ChatGPTWebMessagesMixin:
    @staticmethod
    def _compact_chatgpt_web_schema(value: Any) -> Any:
        from run_agent import AIAgent
        if isinstance(value, dict):
            cleaned: dict[str, Any] = {}
            for key, inner in value.items():
                if key in {"description", "title", "default", "examples", "$schema"}:
                    continue
                compact = AIAgent._compact_chatgpt_web_schema(inner)
                if compact in ({}, [], None, ""):
                    continue
                cleaned[key] = compact
            return cleaned
        if isinstance(value, list):
            items = [AIAgent._compact_chatgpt_web_schema(item) for item in value]
            return [item for item in items if item not in ({}, [], None, "")]
        return value

    @staticmethod
    def _compact_chatgpt_web_description(text: str) -> str:
        if not isinstance(text, str) or not text.strip():
            return ""
        first_paragraph = text.strip().split("\n\n", 1)[0].strip()
        first_sentence = re.split(r"(?<=[.!?])\s+", first_paragraph, maxsplit=1)[0].strip()
        return first_sentence[:220]

    @staticmethod
    def _chatgpt_web_current_turn_messages(payload_messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not isinstance(payload_messages, list):
            return []
        for idx in range(len(payload_messages) - 1, -1, -1):
            item = payload_messages[idx]
            if isinstance(item, dict) and item.get("role") == "user":
                return payload_messages[idx:]
        return payload_messages

    @staticmethod
    def _chatgpt_web_extract_original_request(user_content: str) -> str:
        original = str(user_content or "").strip()
        marker = "Original user request:\n"
        if marker in original:
            original = original.split(marker, 1)[1].strip()
            original = original.split("\n\nRuntime reminder:", 1)[0].strip()
        return original

    @staticmethod
    def _conversation_turn_preview(user_message: Any) -> str:
        if isinstance(user_message, str):
            preview = user_message
        elif isinstance(user_message, list):
            parts: list[str] = []
            for item in user_message:
                if not isinstance(item, dict):
                    continue
                item_type = str(item.get("type") or "").strip()
                if item_type == "text":
                    text = str(item.get("text") or "").strip()
                    if text:
                        parts.append(text)
                elif item_type == "input_image":
                    image_url = str(item.get("image_url") or "").strip()
                    parts.append(f"[image:{image_url or 'attached'}]")
            preview = " ".join(part for part in parts if part) or str(user_message)
        else:
            preview = str(user_message)
        preview = preview.replace("\n", " ")
        return (preview[:80] + "...") if len(preview) > 80 else preview

    def _chatgpt_web_original_user_request(self, payload_messages: list[dict[str, Any]]) -> str:
        current_turn_messages = self._chatgpt_web_current_turn_messages(payload_messages)
        for item in reversed(current_turn_messages):
            if isinstance(item, dict) and item.get("role") == "user":
                return self._chatgpt_web_extract_original_request(str(item.get("content") or ""))
        return ""

    @staticmethod
    def _chatgpt_web_answer_only_mode(original_request: str) -> str:
        lowered = str(original_request or "").strip().lower()
        if "answer only" not in lowered:
            return ""
        if (("yes/no" in lowered) or ("yes or no" in lowered)) and "matching path" in lowered:
            return "yes_no_path"
        if (
            "answer only the exact line" in lowered
            or "answer only with the exact line" in lowered
            or "answer only exact line" in lowered
            or "answer only the first line" in lowered
            or "answer only with the first line" in lowered
            or "answer only first line" in lowered
            or "answer only the def line" in lowered
            or "answer only with the def line" in lowered
            or "answer only exact def line" in lowered
            or "answer only the exact def line" in lowered
            or "answer only with the exact def line" in lowered
        ):
            return "line"
        if (
            "answer only the path" in lowered
            or "answer only path" in lowered
            or "answer only the saved path" in lowered
            or "answer only saved path" in lowered
            or "answer only the exact path" in lowered
            or "answer only with the exact path" in lowered
            or "answer only the exact repo path" in lowered
            or "answer only with the exact repo path" in lowered
        ):
            return "path"
        if "answer only the result" in lowered or "answer only the output" in lowered:
            return "result"
        if "answer only yes/no" in lowered or "answer only yes or no" in lowered:
            return "yes_no"
        if (
            "answer only the value" in lowered
            or "answer only value" in lowered
            or "answer only the branch name" in lowered
            or "answer only branch name" in lowered
        ):
            return "value"
        if "answer only saved" in lowered:
            return "saved"
        if "answer only created" in lowered:
            return "created"
        if "answer only removed" in lowered:
            return "removed"
        if "answer only deleted" in lowered:
            return "deleted"
        if (
            "answer only verified" in lowered
            or "answer only 'verified'" in lowered
            or 'answer only "verified"' in lowered
        ):
            return "verified"
        return ""

    def _chatgpt_web_final_answer_example(self, original_request: str) -> str:
        mode = self._chatgpt_web_answer_only_mode(original_request)
        if mode == "path":
            return "Final answer format example:\n/data/data/com.termux/files/home/project"
        if mode == "line":
            return "Final answer format example:\n_SANE_PATH = os.pathsep.join(_SANE_PATH_DIRS)"
        if mode == "result":
            return "Final answer format example:\n42"
        if mode == "yes_no":
            return "Final answer format example:\nyes"
        if mode == "yes_no_path":
            return "Final answer format example when a match exists:\nyes\nrun_agent.py\nIf no match exists, answer:\nno"
        if mode == "removed":
            return "Final answer format example:\nremoved"
        if mode == "deleted":
            return "Final answer format example:\ndeleted"
        if mode == "verified":
            return "Final answer format example:\nverified"
        return ""

    @staticmethod
    def _chatgpt_web_extract_path_candidate(text: str) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        normalized = re.sub(r"/\s+", "/", text.strip())
        for pattern in (
            r"(/(?:[A-Za-z0-9._-]+/?)+)",
            r"\b([A-Za-z0-9_./-]+\.[A-Za-z0-9_]+)\b",
        ):
            match = re.search(pattern, normalized)
            if match:
                candidate = match.group(1).strip().strip('"\'`')
                return candidate.rstrip('.,;:!')
        return None

    @staticmethod
    def _chatgpt_web_extract_simple_result(text: str) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        stripped = text.strip().strip('"\'`')
        number_match = re.search(r"(?<!\w)(-?\d+(?:\.\d+)?)(?!\w)", stripped)
        if number_match:
            return number_match.group(1)
        lines = [line.strip() for line in stripped.splitlines() if line.strip()]
        if len(lines) == 1 and lines[0] and not any(ch in lines[0] for ch in "<>`"):
            return lines[0].strip('"\'')
        return None

    @staticmethod
    def _chatgpt_web_strip_terminal_noise(text: str) -> str:
        if not isinstance(text, str) or not text:
            return ""
        cleaned_lines: list[str] = []
        for raw_line in text.splitlines():
            line = str(raw_line or "").rstrip()
            lowered = line.strip().lower()
            if not lowered:
                continue
            if "screen size is bogus" in lowered:
                continue
            cleaned_lines.append(line)
        return "\n".join(cleaned_lines)

    @classmethod
    def _chatgpt_web_terminal_output_lines(cls, text: str) -> list[str]:
        cleaned = cls._chatgpt_web_strip_terminal_noise(text)
        return [line.strip() for line in cleaned.splitlines() if line.strip()]

    @classmethod
    def _chatgpt_web_extract_top_process_name(cls, text: str) -> Optional[str]:
        lines = cls._chatgpt_web_terminal_output_lines(text)
        if not lines:
            return None
        header_index = -1
        for idx, line in enumerate(lines):
            if re.match(r"^USER\s+PID\s+%CPU\s+%MEM\b", line):
                header_index = idx
                break
        candidate_rows = lines[header_index + 1:] if header_index >= 0 else lines
        for row in candidate_rows:
            if re.match(r"^USER\s+PID\s+%CPU\s+%MEM\b", row):
                continue
            parts = re.split(r"\s+", row, maxsplit=10)
            if len(parts) < 11:
                continue
            command = parts[10].strip()
            if not command:
                continue
            first_token = command.split()[0].strip()
            if not first_token:
                continue
            normalized = first_token.rstrip(",:;")
            normalized = normalized.replace("\\", "/")
            base_name = normalized.rsplit("/", 1)[-1].strip()
            return base_name or normalized
        return None

    @staticmethod
    def _chatgpt_web_request_mentions_top_process(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        if not lowered:
            return False
        return any(
            keyword in lowered for keyword in (
                "top process",
                "top processes",
                "most memory",
                "memory usage",
                "%mem",
            )
        )

    @staticmethod
    def _chatgpt_web_request_mentions_pwd(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        if not lowered:
            return False
        return any(
            keyword in lowered for keyword in (
                " pwd",
                "pwd",
                "working directory",
                "current directory",
            )
        )

    @staticmethod
    def _chatgpt_web_request_mentions_whoami(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        return bool(lowered and "whoami" in lowered)

    @staticmethod
    def _chatgpt_web_request_mentions_marker_verification(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        if not lowered or "marker" not in lowered or "exist" not in lowered:
            return False
        return any(keyword in lowered for keyword in ("verify", "confirm", "check"))

    @staticmethod
    def _chatgpt_web_request_mentions_current_branch(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        if not lowered:
            return False
        return any(
            keyword in lowered for keyword in (
                "current branch",
                "branch name",
                "print the current branch",
                "show the current branch",
                "what branch",
            )
        )

    @staticmethod
    def _chatgpt_web_request_mentions_browser_title(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        if not lowered:
            return False
        return any(
            keyword in lowered for keyword in (
                "visible title",
                "page title",
                "title from the screenshot",
                "read the title",
                "what is the title",
            )
        )

    @staticmethod
    def _chatgpt_web_extract_browser_url(original_request: str) -> Optional[str]:
        request_text = str(original_request or "").strip()
        if not request_text:
            return None
        url_matches = re.findall(r"(https?://[^\s)]+)", request_text)
        if not url_matches:
            return None
        return url_matches[-1].rstrip(".,;:")

    @classmethod
    def _chatgpt_web_extract_marker_search_request(cls, original_request: str) -> Optional[tuple[str, str]]:
        request_text = str(original_request or "").strip()
        if not request_text:
            return None
        lowered = request_text.lower()
        if "exist" not in lowered or not any(keyword in lowered for keyword in ("readme", "marker", "contains")):
            return None
        repo_path = cls._chatgpt_web_extract_local_path(request_text)
        if not repo_path:
            return None
        marker_match = re.search(
            r"\bcheck\s+whether\s+(.+?)\s+exists\s+in\s+(?:the\s+)?(?:repo\s+)?readme\b",
            request_text,
            re.IGNORECASE | re.DOTALL,
        )
        if not marker_match:
            marker_match = re.search(
                r"\bcheck\s+whether\s+(.+?)\s+is\s+present\s+in\s+(?:the\s+)?(?:repo\s+)?readme\b",
                request_text,
                re.IGNORECASE | re.DOTALL,
            )
        if not marker_match:
            marker_match = re.search(
                r"\bdoes\s+(?:the\s+)?(?:repo\s+)?readme\s+contain\s+(.+?)(?:[.?!]|$)",
                request_text,
                re.IGNORECASE | re.DOTALL,
            )
        if not marker_match:
            return None
        marker_text = marker_match.group(1).strip().strip("\"'`").rstrip(".,;:")
        if not marker_text:
            return None
        return marker_text, repo_path

    @classmethod
    def _chatgpt_web_infer_last_tool_name(
        cls,
        payload_messages: list[dict[str, Any]],
        last_tool_payload: Any,
    ) -> str:
        saw_tool_message = False
        for item in reversed(payload_messages):
            if not isinstance(item, dict):
                continue
            role = item.get("role")
            if role == "tool":
                saw_tool_message = True
                tool_name = str(item.get("tool_name") or "").strip()
                if tool_name:
                    return tool_name
                continue
            if saw_tool_message and role == "assistant":
                tool_calls = item.get("tool_calls")
                if isinstance(tool_calls, list) and tool_calls:
                    last_call = tool_calls[-1]
                    if isinstance(last_call, dict):
                        function = last_call.get("function")
                        if isinstance(function, dict):
                            tool_name = str(function.get("name") or "").strip()
                            if tool_name:
                                return tool_name
                break
        if isinstance(last_tool_payload, dict):
            if any(key in last_tool_payload for key in ("matches", "files", "total_count")):
                return "search_files"
            if any(key in last_tool_payload for key in ("content", "truncated", "hint")):
                return "read_file"
            if any(key in last_tool_payload for key in ("output", "exit_code", "error")):
                return "terminal"
            if any(key in last_tool_payload for key in ("jobs", "entries", "schedule")):
                return "cronjob"
            if any(key in last_tool_payload for key in ("screenshot_path", "analysis", "answer")):
                return "browser_vision"
            if any(key in last_tool_payload for key in ("url", "title")):
                return "browser_navigate"
        return ""

    @staticmethod
    def _chatgpt_web_shell_quote(value: str) -> str:
        text = str(value or "")
        return "'" + text.replace("'", "'\"'\"'") + "'"

    @classmethod
    def _chatgpt_web_extract_path_exists_target(cls, original_request: str) -> Optional[str]:
        request_text = str(original_request or "").strip()
        if not request_text:
            return None
        lowered = request_text.lower()
        if "exist" not in lowered:
            return None
        if not any(keyword in lowered for keyword in ("check whether", "whether", "verify", "confirm", "does", "is there")):
            return None
        return cls._chatgpt_web_extract_local_path(request_text)

    @classmethod
    def _chatgpt_web_extract_append_request(cls, original_request: str) -> Optional[tuple[str, str]]:
        request_text = str(original_request or "").strip()
        if not request_text:
            return None
        target_path = cls._chatgpt_web_extract_local_path(request_text)
        if not target_path:
            return None
        marker_match = re.search(
            r"\bappend\s+the\s+exact\s+text\s+(.+?)(?:\s+to\s+|\s+at\s+the\s+end\s+of\b)",
            request_text,
            re.IGNORECASE | re.DOTALL,
        )
        if not marker_match:
            return None
        marker_text = marker_match.group(1).strip().strip("\"'`").rstrip(".,;:")
        if not marker_text:
            return None
        return marker_text, target_path

    @classmethod
    def _chatgpt_web_extract_clone_request(cls, original_request: str) -> Optional[tuple[str, str, Optional[int]]]:
        request_text = str(original_request or "").strip()
        if not request_text:
            return None
        repo_match = re.search(
            r"\bclone\b(?:\s+(?:the|this|that|github|git|repository|repo|project))*\s+(https?://\S+)",
            request_text,
            re.IGNORECASE,
        )
        if not repo_match and re.search(r"\bclone\b", request_text, re.IGNORECASE):
            repo_match = re.search(r"(https?://\S+)", request_text, re.IGNORECASE)
        if not repo_match:
            return None
        into_match = re.search(r"\binto\s+(.+?)(?:\.\s|\.?$|$)", request_text, re.IGNORECASE | re.DOTALL)
        target_path = cls._chatgpt_web_extract_local_path(into_match.group(1)) if into_match else None
        if not target_path:
            target_path = cls._chatgpt_web_extract_local_path(request_text)
        if not target_path:
            return None
        depth_match = re.search(r"\bdepth\s+(\d+)\b", request_text, re.IGNORECASE)
        depth = int(depth_match.group(1)) if depth_match else None
        return repo_match.group(1).rstrip(".,;:"), target_path, depth

    @classmethod
    def _chatgpt_web_extract_terminal_completion(
        cls,
        original_request: str,
        messages: list[dict[str, Any]],
    ) -> Optional[str]:
        request_text = str(original_request or "").strip()
        if not request_text:
            return None

        tool_outputs: list[str] = []
        for item in messages:
            if not isinstance(item, dict) or item.get("role") != "tool":
                continue
            tool_payload = cls._chatgpt_web_parse_tool_payload(item.get("content"))
            if not isinstance(tool_payload, dict):
                continue
            output_text = tool_payload.get("output")
            if isinstance(output_text, str) and output_text.strip():
                cleaned = cls._chatgpt_web_strip_terminal_noise(output_text)
                if cleaned:
                    tool_outputs.append(cleaned)
        if not tool_outputs:
            return None

        answer_only_mode = cls._chatgpt_web_answer_only_mode(request_text)
        if answer_only_mode == "yes_no" and cls._chatgpt_web_extract_path_exists_target(request_text):
            for output_text in reversed(tool_outputs):
                yes_no = cls._chatgpt_web_extract_yes_no(output_text)
                if yes_no:
                    return yes_no
        if answer_only_mode == "path" and cls._chatgpt_web_extract_clone_request(request_text):
            for output_text in reversed(tool_outputs):
                clone_match = re.search(r"Cloning into ['\"]([^'\"]+)['\"]", output_text)
                if clone_match:
                    return clone_match.group(1).strip()
                lines = cls._chatgpt_web_terminal_output_lines(output_text)
                for line in reversed(lines):
                    candidate = cls._chatgpt_web_extract_path_candidate(line)
                    if candidate:
                        return candidate
        if answer_only_mode == "verified" and cls._chatgpt_web_extract_append_request(request_text):
            for output_text in reversed(tool_outputs):
                simple_value = cls._chatgpt_web_extract_simple_value(output_text)
                if simple_value and simple_value.lower() == "verified":
                    return "verified"
        if answer_only_mode == "value" and cls._chatgpt_web_request_mentions_current_branch(request_text):
            for output_text in reversed(tool_outputs):
                simple_value = cls._chatgpt_web_extract_simple_value(output_text)
                if (
                    simple_value
                    and re.fullmatch(r"[A-Za-z0-9._/-]+", simple_value)
                    and not simple_value.startswith(("/", "~"))
                    and not re.match(r"^[A-Za-z]:[\\/]", simple_value)
                ):
                    return simple_value

        needs_topproc = cls._chatgpt_web_request_mentions_top_process(request_text)
        needs_whoami = cls._chatgpt_web_request_mentions_whoami(request_text)
        needs_pwd = cls._chatgpt_web_request_mentions_pwd(request_text)

        top_process = None
        if needs_topproc:
            for output_text in reversed(tool_outputs):
                top_process = cls._chatgpt_web_extract_top_process_name(output_text)
                if top_process:
                    break
            if top_process and (
                "top process name only" in request_text.lower()
                or "answer with the top process name only" in request_text.lower()
            ):
                return top_process

        user_value = None
        pwd_value = None
        if needs_whoami or needs_pwd:
            for output_text in tool_outputs:
                lines = cls._chatgpt_web_terminal_output_lines(output_text)
                if not lines:
                    continue
                if needs_whoami and user_value is None:
                    for idx, line in enumerate(lines):
                        if re.match(r"^USER\s+PID\s+%CPU\s+%MEM\b", line):
                            continue
                        if re.fullmatch(r"[A-Za-z0-9._-]+", line):
                            if needs_pwd and idx + 1 < len(lines) and (
                                lines[idx + 1].startswith("/")
                                or re.match(r"^[A-Za-z]:[\\/]", lines[idx + 1])
                            ):
                                user_value = line
                                break
                            if not needs_pwd:
                                user_value = line
                                break
                if needs_pwd and pwd_value is None:
                    for line in lines:
                        if line.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", line):
                            pwd_value = line
                            break
                if (not needs_whoami or user_value) and (not needs_pwd or pwd_value):
                    break

        if (
            "final answer exactly as three lines" in request_text.lower()
            and needs_whoami
            and needs_pwd
            and needs_topproc
            and user_value
            and pwd_value
            and top_process
        ):
            return f"USER={user_value}\nPWD={pwd_value}\nTOPPROC={top_process}"

        if answer_only_mode in {"result", "value"} and needs_whoami and not needs_pwd and not needs_topproc and user_value:
            return user_value
        if answer_only_mode in {"result", "path"} and needs_pwd and not needs_whoami and not needs_topproc and pwd_value:
            return pwd_value
        if needs_topproc and top_process:
            if "answer briefly" in request_text.lower():
                return top_process

        return None

    @staticmethod
    def _chatgpt_web_extract_simple_value(text: str) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        stripped = text.strip()
        quoted = re.findall(r'"([^"]+)"', stripped)
        if quoted:
            return quoted[-1].strip()
        single_quoted = re.findall(r"'([^']+)'", stripped)
        if single_quoted:
            return single_quoted[-1].strip()
        value_match = re.search(r"\b(?:is|as|saved as)\s+([A-Za-z0-9._-]+)\b", stripped, re.IGNORECASE)
        if value_match:
            candidate = value_match.group(1).strip().rstrip('.,;:!')
            if candidate.lower() not in {"a", "an", "the"}:
                return candidate
        lines = [line.strip() for line in stripped.splitlines() if line.strip()]
        if len(lines) == 1 and lines[0] and re.fullmatch(r"[A-Za-z0-9._-]+", lines[0]):
            return lines[0]
        return None

    @staticmethod
    def _chatgpt_web_extract_yes_no(text: str) -> Optional[str]:
        if not isinstance(text, str) or not text.strip():
            return None
        lowered = text.strip().lower()
        if re.match(r"^yes\b", lowered):
            return "yes"
        if re.match(r"^no\b", lowered):
            return "no"
        return None

    @staticmethod
    def _chatgpt_web_parse_tool_payload(tool_content: Any) -> Any:
        if not isinstance(tool_content, str):
            return None
        try:
            return json.loads(tool_content)
        except Exception:
            repaired = re.sub(
                r'("(?:path|image_url|file|directory)"\s*:\s*")([^"]*)(")',
                lambda match: (
                    match.group(1)
                    + match.group(2).replace("\\", "\\\\")
                    + match.group(3)
                ),
                tool_content,
            )
            if repaired != tool_content:
                try:
                    return json.loads(repaired)
                except Exception:
                    pass
            repaired = re.sub(r'\\(?!["\\/bfnrtu])', r"\\\\", tool_content)
            if repaired != tool_content:
                try:
                    return json.loads(repaired)
                except Exception:
                    return None
            return None

    def _chatgpt_web_extract_path_from_tool_payload(self, tool_payload: Any, tool_content: str) -> Optional[str]:
        if isinstance(tool_payload, dict):
            results = tool_payload.get("results")
            if isinstance(results, list) and results:
                first = results[0]
                if isinstance(first, dict):
                    summary = first.get("summary")
                    if isinstance(summary, str) and summary.strip():
                        path = self._chatgpt_web_extract_path_candidate(summary)
                        if path:
                            return path
            direct_path = tool_payload.get("path")
            if isinstance(direct_path, str) and direct_path.strip():
                return direct_path.strip()
            matches = tool_payload.get("matches")
            if isinstance(matches, list) and matches:
                first = matches[0]
                if isinstance(first, dict):
                    path = first.get("path")
                    if isinstance(path, str) and path.strip():
                        return path.strip()
            files = tool_payload.get("files")
            if isinstance(files, list) and files:
                first = files[0]
                if isinstance(first, str) and first.strip():
                    return first.strip()
            output = tool_payload.get("output")
            if isinstance(output, str) and output.strip():
                path = self._chatgpt_web_extract_path_candidate(output)
                if path:
                    return path
        return self._chatgpt_web_extract_path_candidate(tool_content)

    def _chatgpt_web_extract_result_from_tool_payload(self, tool_payload: Any, tool_content: str) -> Optional[str]:
        if isinstance(tool_payload, dict):
            results = tool_payload.get("results")
            if isinstance(results, list) and results:
                first = results[0]
                if isinstance(first, dict):
                    summary = first.get("summary")
                    if isinstance(summary, str):
                        result = self._chatgpt_web_extract_simple_result(summary)
                        if result:
                            return result
            output = tool_payload.get("output")
            if isinstance(output, str):
                result = self._chatgpt_web_extract_simple_result(output)
                if result:
                    return result
        return self._chatgpt_web_extract_simple_result(tool_content)

    @staticmethod
    def _chatgpt_web_extract_exact_line_from_text(text: str) -> Optional[str]:
        from run_agent import AIAgent
        if not isinstance(text, str) or not text.strip():
            return None
        fence_match = re.search(r"```(?:[A-Za-z0-9_+-]+)?\n([^`]+?)\n```", text, re.DOTALL)
        if fence_match:
            fenced_lines = [line.strip() for line in fence_match.group(1).splitlines() if line.strip()]
            if fenced_lines:
                return fenced_lines[0]
        for raw_line in text.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            numbered = re.match(r"^\d+\|(.*)$", line)
            if numbered:
                candidate = numbered.group(1).strip()
                if candidate:
                    return candidate
            if line.startswith(("def ", "class ", "_", "@")) or " = " in line:
                return line.strip('"\'`')
        simple = AIAgent._chatgpt_web_extract_simple_result(text)
        if simple and "\n" not in simple:
            return simple
        return None

    def _chatgpt_web_extract_line_from_tool_payload(self, tool_payload: Any, tool_content: str) -> Optional[str]:
        if isinstance(tool_payload, dict):
            results = tool_payload.get("results")
            if isinstance(results, list) and results:
                first = results[0]
                if isinstance(first, dict):
                    summary = first.get("summary")
                    if isinstance(summary, str):
                        line = self._chatgpt_web_extract_exact_line_from_text(summary)
                        if line:
                            return line
            matches = tool_payload.get("matches")
            if isinstance(matches, list) and matches:
                first = matches[0]
                if isinstance(first, dict):
                    line = self._chatgpt_web_extract_exact_line_from_text(str(first.get("content") or ""))
                    if line:
                        return line
            for key in ("content", "output"):
                value = tool_payload.get(key)
                if isinstance(value, str):
                    line = self._chatgpt_web_extract_exact_line_from_text(value)
                    if line:
                        return line
        return self._chatgpt_web_extract_exact_line_from_text(tool_content)

    @staticmethod
    def _chatgpt_web_plaintext_from_read_file_content(text: str) -> str:
        if not isinstance(text, str) or not text:
            return ""
        plain_lines: list[str] = []
        for raw_line in text.splitlines():
            match = re.match(r"^\s*\d+\|(.*)$", raw_line)
            plain_lines.append(match.group(1) if match else raw_line)
        return "\n".join(plain_lines)

    @staticmethod
    def _chatgpt_web_extract_yes_no_from_tool_payload(tool_payload: Any) -> Optional[str]:
        if isinstance(tool_payload, dict):
            total_count = tool_payload.get("total_count")
            if isinstance(total_count, int):
                return "yes" if total_count > 0 else "no"
            for key in ("matches", "files"):
                value = tool_payload.get(key)
                if isinstance(value, list):
                    return "yes" if value else "no"
        return None

    def _chatgpt_web_repair_answer_only_response(
        self,
        original_request: str,
        final_response: str,
        messages: list[dict[str, Any]],
    ) -> str:
        repaired = str(final_response or "").strip()
        mode = self._chatgpt_web_answer_only_mode(original_request)
        if not mode:
            return repaired

        last_tool_content = ""
        for item in reversed(messages):
            if isinstance(item, dict) and item.get("role") == "tool":
                last_tool_content = str(item.get("content") or "")
                break

        tool_payload = self._chatgpt_web_parse_tool_payload(last_tool_content) if last_tool_content else None
        if mode == "path":
            image_url = self._chatgpt_web_extract_image_url_from_text(repaired)
            if image_url and any(keyword in str(original_request or "").lower() for keyword in ("save", "download", "store")):
                return image_url
            payload_path = self._chatgpt_web_extract_path_from_tool_payload(tool_payload, last_tool_content) if last_tool_content else None
            if payload_path and (
                self._chatgpt_web_extract_clone_request(original_request)
                or "exact path" in str(original_request or "").lower()
                or "exact repo path" in str(original_request or "").lower()
            ):
                return payload_path
            repaired_path = self._chatgpt_web_extract_path_candidate(repaired)
            if repaired_path:
                return repaired_path
            return payload_path or repaired
        if mode == "line":
            line = (
                self._chatgpt_web_extract_line_from_tool_payload(tool_payload, last_tool_content) if last_tool_content else None
            ) or self._chatgpt_web_extract_exact_line_from_text(repaired)
            return line or repaired
        if mode == "result":
            result = self._chatgpt_web_extract_simple_result(repaired) or (
                self._chatgpt_web_extract_result_from_tool_payload(tool_payload, last_tool_content) if last_tool_content else None
            )
            return result or repaired
        if mode == "yes_no":
            verdict = self._chatgpt_web_extract_yes_no(repaired) or self._chatgpt_web_extract_yes_no_from_tool_payload(tool_payload)
            return verdict or repaired
        if mode == "yes_no_path":
            verdict = self._chatgpt_web_extract_yes_no(repaired) or self._chatgpt_web_extract_yes_no_from_tool_payload(tool_payload)
            path = self._chatgpt_web_extract_path_candidate(repaired) or (
                self._chatgpt_web_extract_path_from_tool_payload(tool_payload, last_tool_content) if last_tool_content else None
            )
            if verdict == "no":
                return "no"
            if path:
                return f"yes\n{path}"
        if mode == "value":
            value = self._chatgpt_web_extract_simple_value(repaired) or (
                self._chatgpt_web_extract_result_from_tool_payload(tool_payload, last_tool_content) if last_tool_content else None
            )
            return value or repaired
        if mode == "saved":
            if isinstance(tool_payload, dict) and tool_payload.get("success") is True:
                return "saved"
            if last_tool_content and ("entry added" in last_tool_content.lower() or "saved" in last_tool_content.lower()):
                return "saved"
            if "saved" in repaired.lower():
                return "saved"
        if mode == "verified":
            append_request = self._chatgpt_web_extract_append_request(original_request)
            if append_request:
                marker_text, _target_path = append_request
                if last_tool_content and marker_text in last_tool_content:
                    return "verified"
            if "verified" in repaired.lower():
                return "verified"
        if mode == "created":
            if last_tool_content and (" created" in last_tool_content.lower() or " updated" in last_tool_content.lower()):
                return "created"
            if "created" in repaired.lower() or "updated" in repaired.lower():
                return "created"
        if mode == "removed":
            if isinstance(tool_payload, dict) and tool_payload.get("success") is True:
                return "removed"
            if last_tool_content and ("removed" in last_tool_content.lower() or "deleted" in last_tool_content.lower()):
                return "removed"
            if "removed" in repaired.lower() or "deleted" in repaired.lower():
                return "removed"
        if mode == "deleted":
            if last_tool_content and ("deleted" in last_tool_content.lower() or "removed" in last_tool_content.lower()):
                return "deleted"
            if "deleted" in repaired.lower() or "removed" in repaired.lower():
                return "deleted"
        return repaired

    def _chatgpt_web_repair_terminal_completion_response(
        self,
        original_request: str,
        final_response: str,
        messages: list[dict[str, Any]],
    ) -> str:
        repaired = str(final_response or "").strip()
        synthesized = self._chatgpt_web_extract_terminal_completion(original_request, messages)
        if not synthesized:
            return repaired

        lowered = repaired.lower()
        if not lowered:
            return synthesized
        if self._chatgpt_web_response_signals_pending_tool_work(repaired):
            return synthesized
        if any(
            phrase in lowered for phrase in (
                "issue with executing the commands",
                "issue with the terminal",
                "unable to access the terminal",
                "terminal environment not responding",
                "would you like me to try again later",
                "assist you in another way",
                "tools are unavailable",
                "tool isn't available",
                "tool is not available",
            )
        ):
            return synthesized

        request_lower = str(original_request or "").strip().lower()
        if any(
            phrase in request_lower for phrase in (
                "top process name only",
                "answer with the top process name only",
                "answer briefly",
                "final answer exactly as three lines",
            )
        ):
            return synthesized
        answer_only_mode = self._chatgpt_web_answer_only_mode(original_request)
        if answer_only_mode in {"yes_no", "verified", "path"}:
            return synthesized
        if answer_only_mode == "result" and (
            self._chatgpt_web_request_mentions_whoami(original_request)
            or self._chatgpt_web_request_mentions_pwd(original_request)
        ):
            return synthesized
        if (
            answer_only_mode == "value"
            and self._chatgpt_web_request_mentions_current_branch(original_request)
        ):
            return synthesized
        return repaired
