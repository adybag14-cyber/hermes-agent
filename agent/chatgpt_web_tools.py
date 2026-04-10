"""ChatGPT Web tools behavior for the agent."""

from __future__ import annotations

from hermes_cli import chatgpt_web as _chatgpt_web
import re
import json
from agent.chatgpt_web_parsing import _extract_xml_tool_calls_from_text
from agent.chatgpt_web_parsing import _parse_tool_call_arguments


class ChatGPTWebToolsMixin:
    def _select_chatgpt_web_tools(self, payload_messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not self.tools:
            return []

        tools_by_name = {
            str(tool.get("function", {}).get("name") or "").strip(): tool
            for tool in self.tools
            if isinstance(tool, dict) and isinstance(tool.get("function"), dict)
        }
        tools_by_name = {name: tool for name, tool in tools_by_name.items() if name}
        if not tools_by_name:
            return []

        user_messages = [
            str(msg.get("content") or "")
            for msg in payload_messages
            if isinstance(msg, dict) and msg.get("role") == "user"
        ]
        user_text = user_messages[-1] if user_messages else ""
        used_tool_count = sum(
            1 for msg in payload_messages
            if isinstance(msg, dict) and msg.get("role") == "tool"
        )
        last_tool_content = ""
        for item in reversed(payload_messages):
            if isinstance(item, dict) and item.get("role") == "tool":
                last_tool_content = str(item.get("content") or "")
                break
        last_tool_payload = self._chatgpt_web_parse_tool_payload(last_tool_content) if last_tool_content else None
        last_tool_name = self._chatgpt_web_infer_last_tool_name(payload_messages, last_tool_payload)
        terminal_completion = self._chatgpt_web_extract_terminal_completion(user_text, payload_messages) if used_tool_count > 0 else None
        if terminal_completion:
            return []

        lowered = user_text.lower()
        if any(keyword in lowered for keyword in ("cron", "schedule job", "scheduled job", "create job", "list jobs", "remind me every")):
            cron_tool = tools_by_name.get("cronjob")
            if cron_tool is not None:
                return [cron_tool]

        explicit_pattern = re.compile(
            r"\b(" + "|".join(re.escape(name) for name in sorted(tools_by_name, key=len, reverse=True)) + r")\b",
            re.IGNORECASE,
        )
        explicit_sequence: list[str] = []
        for match in explicit_pattern.finditer(user_text):
            tool_name = str(match.group(1) or "").strip()
            prefix = user_text[max(0, match.start() - 48):match.start()].lower()
            suffix = user_text[match.end():min(len(user_text), match.end() + 16)].lower()
            if re.match(r"^\s*or\b", suffix):
                continue
            if re.search(
                r"(?:^|[\s,;:(-])(?:use|using|with|via|through|invoke|invoking|call|calling|tool|tools|first|then|next|after that|and then)\s*$",
                prefix,
            ) or re.search(r"(?:tool available(?: for this turn)? is:\s*)$", prefix) or re.match(r"^\s*(?:tool|tools)\b", suffix):
                explicit_sequence.append(tool_name)
        if explicit_sequence:
            next_index = min(used_tool_count, len(explicit_sequence) - 1)
            next_name = explicit_sequence[next_index]
            for candidate_name, tool in tools_by_name.items():
                if candidate_name.lower() == next_name.lower():
                    return [tool]

        heuristic_names: list[str] = []
        explicit_local_path = self._chatgpt_web_extract_local_path(user_text)
        relative_path_match = re.search(r"\b([A-Za-z0-9_./-]+\.[A-Za-z0-9_]+)\b", user_text)
        path_match = explicit_local_path or (relative_path_match.group(1) if relative_path_match else None)
        explicit_symbol_target = self._chatgpt_web_extract_symbol_target(user_text)
        answer_only_mode = self._chatgpt_web_answer_only_mode(user_text)
        browser_url = self._chatgpt_web_extract_browser_url(user_text)
        marker_search_request = self._chatgpt_web_extract_marker_search_request(user_text)
        image_generation_request = (
            any(keyword in lowered for keyword in ("generate", "create", "draw", "make", "illustrate", "paint"))
            and any(keyword in lowered for keyword in ("image", "picture", "photo", "illustration", "drawing", "logo"))
        )
        image_analysis_request = (
            bool(self._chatgpt_web_extract_image_input_path(user_text))
            or (
                not browser_url
                and any(
                    keyword in lowered for keyword in (
                        "look at this image",
                        "look at this local image",
                        "analyze this image",
                        "describe this image",
                        "what is in this image",
                        "dominant color",
                        "photo",
                        "picture",
                    )
                )
            )
        )
        memory_request = any(
            keyword in lowered for keyword in (
                "remember that",
                "remember this",
                "save this to memory",
                "store this in memory",
                "don't forget",
                "forget that",
                "forget this",
                "remove from memory",
                "delete from memory",
                "my preference",
                "my favorite",
                "my timezone",
                "my name is",
            )
        )
        skill_request = any(
            keyword in lowered for keyword in (
                "create a skill",
                "temporary skill",
                "save as a skill",
                "skill named",
                "skill called",
                "workflow skill",
            )
        )
        delegation_request = any(
            keyword in lowered for keyword in (
                "delegate_task",
                "delegate task",
                "delegate this",
                "delegate that",
                "subagent",
            )
        )

        if image_generation_request:
            image_tool = tools_by_name.get("image_generate")
            return [image_tool] if image_tool is not None else []
        if image_analysis_request:
            if _chatgpt_web._chatgpt_web_debug_base():
                return []
            vision_tool = tools_by_name.get("vision_analyze")
            return [vision_tool] if vision_tool is not None else []
        if used_tool_count > 0 and self._chatgpt_web_answer_only_mode(user_text) == "line":
            last_tool_content = ""
            for item in reversed(payload_messages):
                if isinstance(item, dict) and item.get("role") == "tool":
                    last_tool_content = str(item.get("content") or "")
                    break
            last_tool_payload = self._chatgpt_web_parse_tool_payload(last_tool_content) if last_tool_content else None
            if self._chatgpt_web_extract_line_from_tool_payload(last_tool_payload, last_tool_content):
                return []
        if delegation_request:
            delegate_tool = tools_by_name.get("delegate_task")
            return [delegate_tool] if delegate_tool is not None else []
        if used_tool_count > 0 and answer_only_mode == "line" and isinstance(last_tool_payload, dict):
            matches = last_tool_payload.get("matches")
            if isinstance(matches, list) and len(matches) == 1 and isinstance(matches[0], dict):
                match_content = str(matches[0].get("content") or "").strip()
                if match_content and (
                    not explicit_symbol_target
                    or explicit_symbol_target in match_content
                    or match_content.startswith(("def ", "async def ", "class "))
                ):
                    return []
            payload_content = str(last_tool_payload.get("content") or "")
            numbered_line_match = re.search(r"(?m)^\s*\d+\|([^\n]+)", payload_content)
            if numbered_line_match:
                exact_line = numbered_line_match.group(1).strip()
                if exact_line and (
                    not explicit_symbol_target
                    or explicit_symbol_target in exact_line
                    or exact_line.startswith(("def ", "async def ", "class "))
                ):
                    return []
        if used_tool_count == 0 and path_match and explicit_symbol_target and any(
            keyword in lowered for keyword in ("find", "search", "grep", "symbol", "definition", "define", "defines", "defined")
        ):
            search_tool = tools_by_name.get("search_files")
            if search_tool is not None:
                return [search_tool]
        if used_tool_count == 0:
            path_exists_target = self._chatgpt_web_extract_path_exists_target(user_text)
            if path_exists_target:
                terminal_tool = tools_by_name.get("terminal")
                if terminal_tool is not None:
                    return [terminal_tool]
        if used_tool_count > 0 and last_tool_name == "patch" and self._chatgpt_web_request_mentions_marker_verification(user_text):
            terminal_tool = tools_by_name.get("terminal")
            if terminal_tool is not None:
                return [terminal_tool]
        if marker_search_request and used_tool_count > 0 and last_tool_name == "terminal":
            search_tool = tools_by_name.get("search_files")
            if search_tool is not None:
                return [search_tool]
        if browser_url and used_tool_count > 0 and last_tool_name in {"terminal", "search_files"}:
            navigate_tool = tools_by_name.get("browser_navigate")
            if navigate_tool is not None:
                return [navigate_tool]
        if (
            browser_url
            and self._chatgpt_web_request_mentions_browser_title(user_text)
            and used_tool_count > 0
            and last_tool_name == "browser_navigate"
        ):
            vision_tool = tools_by_name.get("browser_vision")
            if vision_tool is not None:
                return [vision_tool]
        if path_match and any(
            keyword in lowered for keyword in ("read", "first line", "exact def line", "open the file", "show the file", "inspect", "summarize", "report")
        ):
            read_tool = tools_by_name.get("read_file")
            if read_tool is not None:
                return [read_tool]
        if explicit_local_path and any(keyword in lowered for keyword in ("contains exactly", "with content", "containing")):
            write_tool = tools_by_name.get("write_file")
            if write_tool is not None:
                return [write_tool]
        if memory_request:
            memory_tool = tools_by_name.get("memory")
            return [memory_tool] if memory_tool is not None else []
        if skill_request:
            skill_tool = tools_by_name.get("skill_manage")
            return [skill_tool] if skill_tool is not None else []
        if self._chatgpt_web_extract_clone_request(user_text):
            terminal_tool = tools_by_name.get("terminal")
            if terminal_tool is not None:
                return [terminal_tool]
        if explicit_local_path and self._chatgpt_web_request_mentions_current_branch(user_text):
            terminal_tool = tools_by_name.get("terminal")
            if terminal_tool is not None:
                return [terminal_tool]
        if self._chatgpt_web_infer_terminal_command(user_text) or any(
            keyword in lowered for keyword in (
                "port",
                "process",
            )
        ):
            heuristic_names.append("terminal")
        if any(keyword in lowered for keyword in ("python", "script", "calculate", "math", "compute", "sum", "product", "multiply")):
            heuristic_names.append("execute_code")
        if any(keyword in lowered for keyword in ("find", "search", "grep", "file", "files", "path", "repo", "symbol", "definition")):
            heuristic_names.extend(["search_files", "read_file"])
        if any(keyword in lowered for keyword in ("shell", "command", "run ")):
            heuristic_names.append("terminal")
        if explicit_local_path and any(keyword in lowered for keyword in ("edit", "modify", "change", "patch", "fix", "write")) and "contains exactly" in lowered:
            heuristic_names.append("write_file")
        if any(keyword in lowered for keyword in ("edit", "modify", "change", "patch", "fix", "write")):
            heuristic_names.extend(["patch", "write_file"])

        for name in heuristic_names:
            tool = tools_by_name.get(name)
            if tool is not None:
                return [tool]

        return []

    @classmethod
    def _chatgpt_web_infer_terminal_command(cls, user_text: str) -> Optional[str]:
        text = str(user_text or "").strip()
        if not text:
            return None
        lowered = text.lower()
        path_exists_target = cls._chatgpt_web_extract_path_exists_target(text)
        if path_exists_target:
            quoted_path = cls._chatgpt_web_shell_quote(path_exists_target)
            return f"[ -e {quoted_path} ] && echo yes || echo no"
        append_request = cls._chatgpt_web_extract_append_request(text)
        if append_request:
            marker_text, target_path = append_request
            quoted_marker = cls._chatgpt_web_shell_quote(marker_text)
            quoted_path = cls._chatgpt_web_shell_quote(target_path)
            return f"printf '%s\\n' {quoted_marker} >> {quoted_path}"
        clone_request = cls._chatgpt_web_extract_clone_request(text)
        if clone_request:
            repo_url, target_path, depth = clone_request
            quoted_repo = cls._chatgpt_web_shell_quote(repo_url)
            quoted_path = cls._chatgpt_web_shell_quote(target_path)
            clone_parts = ["git", "clone"]
            if depth is not None:
                clone_parts.extend(["--depth", str(depth)])
            clone_parts.extend([quoted_repo, quoted_path])
            return " ".join(clone_parts)
        explicit_local_path = cls._chatgpt_web_extract_local_path(text)
        if explicit_local_path and cls._chatgpt_web_request_mentions_current_branch(text):
            quoted_path = cls._chatgpt_web_shell_quote(explicit_local_path)
            return f"git -C {quoted_path} rev-parse --abbrev-ref HEAD"

        top_process_requested = any(
            keyword in lowered for keyword in (
                "top process",
                "top processes",
                "most memory",
                "memory usage",
                "%mem",
            )
        )
        pwd_requested = any(
            keyword in lowered for keyword in (
                " pwd",
                "pwd",
                "working directory",
                "current directory",
            )
        )
        whoami_requested = "whoami" in lowered
        if whoami_requested and pwd_requested and top_process_requested:
            return "whoami && pwd && ps aux --sort=-%mem | head -n 2"
        if whoami_requested and pwd_requested:
            return "whoami && pwd"

        explicit_run = re.search(r"\brun\s+(.+?)(?:\.\s*answer only.*|$)", text, re.IGNORECASE | re.DOTALL)
        if explicit_run:
            command = explicit_run.group(1).strip().strip('"\'`').rstrip(".")
            command_lower = command.lower()
            if re.search(r"\b(?:python|script)\b.*\bthat\b", command_lower):
                command = ""
            if command:
                return command

        backtick_match = re.search(r"`([^`]+)`", text)
        if backtick_match:
            command = backtick_match.group(1).strip().strip('"\'`').rstrip(".")
            if command:
                return command

        common_commands: list[tuple[str, str]] = [
            (r"\bwhoami\b", "whoami"),
            (r"\bhostname\b", "hostname"),
            (r"\bpwd\b", "pwd"),
            (r"\buname(?:\s+-a)?\b", "uname -a"),
            (r"\bdate\b", "date"),
            (r"\bls\b", "ls"),
            (r"\bdir\b", "dir"),
            (r"\bfree\s+-h\b", "free -h"),
            (r"\bdf\s+-h\b", "df -h"),
        ]
        for pattern, command in common_commands:
            if re.search(pattern, lowered):
                return command

        if "working directory" in lowered or "current directory" in lowered:
            return "pwd"
        if "list files" in lowered or "list the files" in lowered:
            return "ls"
        if any(
            keyword in lowered for keyword in (
                "platform details",
                "platform info",
                "platform information",
                "system details",
                "system info",
                "system information",
                "what system",
                "system you are running on",
                "what os",
                "operating system",
                "kernel",
            )
        ):
            return "uname -a"
        if re.search(r"\b(?:what time is it|current time|time is it)\b", lowered):
            return "date"
        if any(
            keyword in lowered for keyword in (
                "free ram",
                "free memory",
                "available ram",
                "available memory",
                "memory free",
            )
        ):
            return "free -h"
        if any(
            keyword in lowered for keyword in (
                "top processes",
                "top memory processes",
                "most memory",
                "memory usage",
                "%mem",
            )
        ):
            if any(
                phrase in lowered for phrase in (
                    "top process name only",
                    "top process only",
                    "first process",
                    "name only",
                )
            ):
                return "ps aux --sort=-%mem | head -n 2"
            return "ps aux --sort=-%mem | head -n 10"
        if any(
            keyword in lowered for keyword in (
                "top cpu processes",
                "most cpu",
                "cpu usage",
                "%cpu",
            )
        ):
            return "ps aux --sort=-%cpu | head -n 10"
        if any(
            keyword in lowered for keyword in (
                "disk usage",
                "disk free",
                "filesystem usage",
            )
        ):
            return "df -h"

        return None

    def _chatgpt_web_tool_args(self, tool_name: str, payload_messages: list[dict[str, Any]]) -> Optional[dict[str, Any]]:
        if not isinstance(tool_name, str) or not tool_name.strip():
            return None
        raw_user_text = "\n".join(
            str(msg.get("content") or "")
            for msg in payload_messages
            if isinstance(msg, dict) and msg.get("role") == "user"
        )
        user_text = self._chatgpt_web_extract_original_request(raw_user_text) or raw_user_text
        lowered = user_text.lower()
        explicit_local_path = self._chatgpt_web_extract_local_path(user_text)
        explicit_symbol_target = self._chatgpt_web_extract_symbol_target(user_text)
        relative_path_match = re.search(r"\b([A-Za-z0-9_./-]+\.[A-Za-z0-9_]+)\b", user_text)
        path_match = explicit_local_path or (relative_path_match.group(1) if relative_path_match else None)
        used_tool_count = sum(
            1 for item in payload_messages
            if isinstance(item, dict) and item.get("role") == "tool"
        )
        last_tool_content = ""
        for item in reversed(payload_messages):
            if isinstance(item, dict) and item.get("role") == "tool":
                last_tool_content = str(item.get("content") or "")
                break
        last_tool_payload = self._chatgpt_web_parse_tool_payload(last_tool_content) if last_tool_content else None

        if tool_name == "search_files":
            marker_search_request = self._chatgpt_web_extract_marker_search_request(user_text)
            if marker_search_request:
                marker_text, repo_path = marker_search_request
                return {
                    "pattern": marker_text,
                    "target": "content",
                    "path": repo_path,
                    "limit": 20,
                }
            if path_match and explicit_symbol_target and any(
                keyword in lowered for keyword in ("find", "search", "grep", "symbol", "definition", "define", "defines", "defined")
            ):
                return {
                    "pattern": explicit_symbol_target,
                    "target": "content",
                    "path": path_match,
                }
            if "find" in lowered or "file named" in lowered or "named" in lowered:
                filename = path_match or "*.py"
                return {
                    "pattern": filename,
                    "target": "files",
                    "path": ".",
                    "output_mode": "files_only",
                    "limit": 20,
                }

        if tool_name == "read_file":
            if isinstance(last_tool_payload, dict):
                matches = last_tool_payload.get("matches")
                if isinstance(matches, list) and matches:
                    first = matches[0]
                    if isinstance(first, dict):
                        match_path = str(first.get("path") or "").strip()
                        match_line = first.get("line")
                        if match_path:
                            offset = int(match_line) if isinstance(match_line, int) and match_line > 0 else 1
                            return {"path": match_path, "offset": offset, "limit": 1}
                if (
                    path_match
                    and bool(last_tool_payload.get("truncated"))
                    and any(keyword in lowered for keyword in ("inspect", "summarize", "report", "where"))
                    and not any(keyword in lowered for keyword in ("first line", "exact def line", "exact line"))
                ):
                    hint_text = str(last_tool_payload.get("hint") or "")
                    hint_match = re.search(r"offset=(\d+)", hint_text)
                    next_offset = int(hint_match.group(1)) if hint_match else None
                    if next_offset is None:
                        content_text = str(last_tool_payload.get("content") or "")
                        numbered_lines = [
                            int(match.group(1))
                            for match in re.finditer(r"(?m)^\s*(\d+)\|", content_text)
                        ]
                        if numbered_lines:
                            next_offset = numbered_lines[-1] + 1
                    if next_offset and next_offset > 1:
                        return {"path": path_match, "offset": next_offset, "limit": 40}
            if path_match and any(keyword in lowered for keyword in ("read", "first line", "line", "open", "show", "inspect", "summarize", "report")):
                limit = 1 if any(keyword in lowered for keyword in ("first line", "exact def line", "exact line")) else 20
                return {"path": path_match, "offset": 1, "limit": limit}

        if tool_name == "delegate_task":
            delegation_request = any(
                keyword in lowered for keyword in (
                    "delegate_task",
                    "delegate task",
                    "delegate this",
                    "delegate that",
                    "subagent",
                )
            )
            if delegation_request:
                original_request = self._chatgpt_web_extract_original_request(user_text) or user_text.strip()
                cleaned_goal = re.sub(r"^\s*use\s+delegate_task\s+to\s+", "", original_request, flags=re.IGNORECASE).strip()
                cleaned_goal = re.sub(r"^\s*delegate\s+(?:this|that)\s+", "", cleaned_goal, flags=re.IGNORECASE).strip()
                cleaned_goal = cleaned_goal.rstrip(".")
                context_lines: list[str] = []
                if path_match:
                    context_lines.append(f"Inspect only this local file: {path_match}.")
                    context_lines.append("Do not search outside that file unless the file itself references another required location.")
                if explicit_symbol_target:
                    context_lines.append(f"The requested symbol/definition target is: {explicit_symbol_target}.")
                if path_match and explicit_symbol_target and any(
                    keyword in lowered for keyword in ("find", "search", "grep", "symbol", "definition", "define", "defines", "defined")
                ):
                    context_lines.append("Use search_files against that exact path first, then read_file on the matching line if needed.")
                if "answer only" in lowered:
                    context_lines.append("Final response must preserve the user's exact answer-only formatting requirement.")
                elif any(keyword in lowered for keyword in ("exact line", "exact def line", "first line")):
                    context_lines.append("Final response must be only the requested line, with no extra commentary.")
                context_lines.append("Use only the file toolset for this task.")
                return {
                    "goal": cleaned_goal or original_request,
                    "context": " ".join(context_lines).strip(),
                    "toolsets": ["file"],
                    "max_iterations": 4,
                }

        if tool_name == "memory":
            forget_match = re.search(r"\b(?:forget|remove|delete)(?:\s+that|\s+this)?\b\s*(.+?)(?:\.\s*answer only.*)?$", user_text, re.IGNORECASE | re.DOTALL)
            if forget_match:
                old_text = forget_match.group(1).strip().rstrip(".")
                if old_text:
                    return {"action": "remove", "target": "user", "old_text": old_text}
            remember_match = re.search(r"\bremember(?:\s+that|\s+this)?\b\s*(.+?)(?:\.\s*answer only.*)?$", user_text, re.IGNORECASE | re.DOTALL)
            if remember_match:
                content = remember_match.group(1).strip().rstrip(".")
                if content:
                    return {"action": "add", "target": "user", "content": content}

        if tool_name == "skill_manage":
            delete_match = re.search(
                r"\bdelete\s+(?:the\s+)?(?:temporary\s+)?skill\s+named\s+([A-Za-z0-9_.-]+)(?:\.\s*answer only.*)?$",
                user_text,
                re.IGNORECASE | re.DOTALL,
            )
            if delete_match:
                skill_name = self._chatgpt_web_sanitize_skill_name(delete_match.group(1))
                if skill_name:
                    return {"action": "delete", "name": skill_name}
            create_match = re.search(
                r"\b(?:create|save)\s+(?:a\s+)?(?:temporary\s+)?skill\s+named\s+([A-Za-z0-9_.-]+)(?:\s+describing\s+(.+?))?(?:\.\s*answer only.*)?$",
                user_text,
                re.IGNORECASE | re.DOTALL,
            )
            if create_match:
                raw_name = create_match.group(1)
                description = (create_match.group(2) or "Temporary skill created from a ChatGPT Web request.").strip().rstrip(".")
                skill_name = self._chatgpt_web_sanitize_skill_name(raw_name)
                if skill_name:
                    return {
                        "action": "create",
                        "name": skill_name,
                        "content": self._chatgpt_web_build_skill_content(skill_name, description),
                    }

        if tool_name == "vision_analyze":
            image_path = self._chatgpt_web_extract_image_input_path(user_text)
            if image_path:
                question = re.sub(r"\.\s*answer only.*$", "", user_text, flags=re.IGNORECASE).strip()
                return {"image_url": image_path, "question": question}

        if tool_name == "browser_navigate":
            url_match = re.search(r"(https?://[^\s)]+)", user_text)
            if url_match:
                return {"url": url_match.group(1).rstrip(".,;:")}

        if tool_name == "browser_vision":
            if any(keyword in lowered for keyword in ("page title", "title from the screenshot", "visible title")):
                return {"question": "What is the visible page title text in the screenshot?"}
            question = re.sub(r"\.\s*answer only.*$", "", user_text, flags=re.IGNORECASE).strip()
            return {"question": question or "What is visible in the current browser screenshot?"}

        if tool_name == "write_file":
            if explicit_local_path:
                content_match = re.search(r"contains exactly\s+(.+?)(?:\s+on one line|\.\s*then answer only.*|\.\s*answer only.*|$)", user_text, re.IGNORECASE | re.DOTALL)
                if not content_match:
                    content_match = re.search(r"(?:with content|containing)\s+(.+?)(?:\.\s*then answer only.*|\.\s*answer only.*|$)", user_text, re.IGNORECASE | re.DOTALL)
                if content_match:
                    content = content_match.group(1).strip().strip('"\'`')
                    if "on one line" in lowered and not content.endswith("\n"):
                        content += "\n"
                    return {"path": explicit_local_path, "content": content}

        if tool_name == "patch":
            append_request = self._chatgpt_web_extract_append_request(user_text)
            if append_request and explicit_local_path and isinstance(last_tool_payload, dict):
                marker_text, target_path = append_request
                current_content = self._chatgpt_web_plaintext_from_read_file_content(
                    str(last_tool_payload.get("content") or "")
                )
                if current_content:
                    new_content = current_content
                    if not new_content.endswith("\n"):
                        new_content += "\n"
                    new_content += marker_text
                    if not new_content.endswith("\n"):
                        new_content += "\n"
                    return {
                        "mode": "replace",
                        "path": target_path,
                        "old_string": current_content,
                        "new_string": new_content,
                    }

        if tool_name == "image_generate":
            if any(keyword in lowered for keyword in ("generate", "create", "draw", "make", "illustrate", "paint")) and any(
                keyword in lowered for keyword in ("image", "picture", "photo", "illustration", "drawing", "logo")
            ):
                prompt_match = re.search(
                    r"\b(?:generate|create|draw|make|illustrate|paint)\s+(?:a|an|the)?\s*(?:square|portrait|landscape)?\s*(?:image|picture|photo|illustration|drawing|logo)?\s*(?:of\s+)?(.+?)(?:\s+and\s+(?:save|download|store)|\.\s*answer only.*|$)",
                    user_text,
                    re.IGNORECASE | re.DOTALL,
                )
                prompt = (prompt_match.group(1).strip() if prompt_match else "").rstrip(".")
                if prompt:
                    aspect_ratio = "square" if "square" in lowered else ("portrait" if "portrait" in lowered else "landscape")
                    return {"prompt": prompt, "aspect_ratio": aspect_ratio}

        if tool_name == "execute_code":
            expr_match = re.search(r"([0-9][0-9\s\+\-\*\/\(\)]*[\+\-\*\/][0-9\s\+\-\*\/\(\)]*)", user_text)
            if expr_match:
                expr = expr_match.group(1).strip()
                return {"code": f"print({expr})"}

        if tool_name == "cronjob":
            remove_requested = (
                "cron" in lowered
                and any(keyword in lowered for keyword in ("remove", "delete"))
            )
            create_requested = (
                "cron" in lowered
                and any(keyword in lowered for keyword in ("create", "add", "schedule", "remind"))
            )
            list_requested = any(keyword in lowered for keyword in ("list cron", "show cron", "list jobs", "show jobs", "scheduled jobs"))
            name_match = re.search(r"\bnamed\s+([A-Za-z0-9_.-]+)", user_text, re.IGNORECASE)
            if remove_requested:
                job_name = name_match.group(1).strip().rstrip(".,;:") if name_match else "chatgpt-web-job"
                if isinstance(last_tool_payload, dict):
                    jobs = last_tool_payload.get("jobs")
                    if isinstance(jobs, list):
                        for job in jobs:
                            if not isinstance(job, dict):
                                continue
                            candidate_name = str(job.get("name") or "").strip().rstrip(".,;:")
                            candidate_id = str(job.get("id") or job.get("job_id") or "").strip()
                            if candidate_id and candidate_name.lower() == job_name.lower():
                                return {"action": "remove", "job_id": candidate_id}
                return {"action": "list"}
            if create_requested and used_tool_count == 0:
                schedule_match = re.search(
                    r"\b(every\s+\d+\s*(?:m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)|daily|hourly|weekly|monthly)\b",
                    lowered,
                )
                prompt_match = re.search(
                    r"\b(?:to|that)\s+(.+?)(?:\.\s*answer only.*|$)",
                    user_text,
                    re.IGNORECASE | re.DOTALL,
                )
                schedule = schedule_match.group(1) if schedule_match else "every 1h"
                prompt = (prompt_match.group(1).strip() if prompt_match else "Report job status.")
                prompt = re.split(r"\b(?:then|and then)\s+(?:list|show)\s+(?:jobs|cron)\b", prompt, maxsplit=1, flags=re.IGNORECASE)[0]
                prompt = re.split(r"\.\s*keep going\b", prompt, maxsplit=1, flags=re.IGNORECASE)[0]
                prompt = prompt.rstrip(" .,;:")
                job_name = (name_match.group(1).strip().rstrip(".,;:") if name_match else "chatgpt-web-job")
                return {
                    "action": "create",
                    "name": job_name,
                    "schedule": schedule,
                    "prompt": prompt,
                }
            if list_requested:
                return {"action": "list"}
            if create_requested:
                schedule_match = re.search(
                    r"\b(every\s+\d+\s*(?:m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)|daily|hourly|weekly|monthly)\b",
                    lowered,
                )
                prompt_match = re.search(
                    r"\b(?:to|that)\s+(.+?)(?:\.\s*answer only.*|$)",
                    user_text,
                    re.IGNORECASE | re.DOTALL,
                )
                schedule = schedule_match.group(1) if schedule_match else "every 1h"
                prompt = (prompt_match.group(1).strip() if prompt_match else "Report job status.")
                prompt = re.split(r"\b(?:then|and then)\s+(?:list|show)\s+(?:jobs|cron)\b", prompt, maxsplit=1, flags=re.IGNORECASE)[0]
                prompt = re.split(r"\.\s*keep going\b", prompt, maxsplit=1, flags=re.IGNORECASE)[0]
                prompt = prompt.rstrip(" .,;:")
                job_name = (name_match.group(1).strip().rstrip(".,;:") if name_match else "chatgpt-web-job")
                return {
                    "action": "create",
                    "name": job_name,
                    "schedule": schedule,
                    "prompt": prompt,
                }

        if tool_name == "terminal":
            append_request = self._chatgpt_web_extract_append_request(user_text)
            if append_request:
                marker_text, target_path = append_request
                quoted_marker = self._chatgpt_web_shell_quote(marker_text)
                quoted_path = self._chatgpt_web_shell_quote(target_path)
                if used_tool_count > 0 and self._chatgpt_web_request_mentions_marker_verification(user_text):
                    return {"command": f"grep -Fqx -- {quoted_marker} {quoted_path} && echo verified || echo missing"}
                return {"command": f"printf '%s\\n' {quoted_marker} >> {quoted_path}"}
            clone_request = self._chatgpt_web_extract_clone_request(user_text)
            if clone_request:
                repo_url, target_path, depth = clone_request
                quoted_repo = self._chatgpt_web_shell_quote(repo_url)
                quoted_path = self._chatgpt_web_shell_quote(target_path)
                if used_tool_count > 0 and self._chatgpt_web_request_mentions_current_branch(user_text):
                    return {"command": f"git -C {quoted_path} rev-parse --abbrev-ref HEAD"}
                clone_parts = ["git", "clone"]
                if depth is not None:
                    clone_parts.extend(["--depth", str(depth)])
                clone_parts.extend([quoted_repo, quoted_path])
                return {"command": " ".join(clone_parts)}
            if explicit_local_path and self._chatgpt_web_request_mentions_current_branch(user_text):
                quoted_path = self._chatgpt_web_shell_quote(explicit_local_path)
                return {"command": f"git -C {quoted_path} rev-parse --abbrev-ref HEAD"}
            command = self._chatgpt_web_infer_terminal_command(user_text)
            if command:
                return {"command": command}

        return None

    def _chatgpt_web_tool_hint(self, tool_name: str, payload_messages: list[dict[str, Any]]) -> str:
        args = self._chatgpt_web_tool_args(tool_name, payload_messages)
        if args is None:
            return ""
        return "Use these exact arguments for this turn: " + json.dumps(args, ensure_ascii=False)

    def _chatgpt_web_tool_call_example(self, tool_name: str, payload_messages: list[dict[str, Any]]) -> str:
        if not isinstance(tool_name, str) or not tool_name.strip():
            return ""
        args = self._chatgpt_web_tool_args(tool_name, payload_messages)
        if args is None:
            return ""
        return (
            "<tool_call>\n"
            + json.dumps({"name": tool_name, "arguments": args}, ensure_ascii=False)
            + "\n</tool_call>"
        )

    @staticmethod
    def _chatgpt_web_requests_consecutive_tool_flow(original_request: str) -> bool:
        lowered = str(original_request or "").strip().lower()
        if not lowered:
            return False
        patterns = (
            r"\bcontinue\b",
            r"\bkeep going\b",
            r"\bkeep using\b",
            r"\bconsecutive\b",
            r"\bdo not (?:reply|answer)\b",
            r"\bonly after\b",
            r"\bimmediate second attempt\b",
            r"\bguess the next tool call\b",
            r"\bnext \d+ turns?\b",
            r"\bno(?: real)? english answer\b",
        )
        return any(re.search(pattern, lowered) for pattern in patterns)

    @staticmethod
    def _chatgpt_web_response_signals_pending_tool_work(message_text: str) -> bool:
        lowered = str(message_text or "").strip().lower()
        if not lowered:
            return False
        patterns = (
            r"\bi(?:'ll| will)\s+(?:continue|retry|inspect|check|read|search|write|patch|test|debug|create|save|package|install|use|look)\b",
            r"\bi can\s+(?:continue|retry|inspect|check|read|search|write|patch|test|debug|create|save|package|install|use|look|run|open|browse)\b",
            r"\blet me\s+(?:continue|retry|inspect|check|read|search|write|patch|test|debug|create|save|package|install|use|look)\b",
            r"\b(?:next step|next up|continuing|retrying)\b",
            r"\bi need to\s+(?:continue|retry|inspect|check|read|search|write|patch|test|debug|create|save|package|install|use|look)\b",
            r"\bi(?: have|'ve)?\s+(?:made progress|found|identified).+\b(?:now|next)\b",
            r"\btechnical issue preventing the use of the terminal\b",
            r"\bissue with tool usage\b",
            r"\bcannot retrieve\b.{0,80}\b(?:tool|resources)\b",
            r"\btool is currently unavailable\b",
            r"\btool is unavailable\b",
            r"\bterminal tool is currently unavailable\b",
            r"\bterminal tool is unavailable\b",
            r"\bassist with another solution\b",
            r"\b(?:attempt|try)\s+(?:another|a different)\s+approach\b",
            r"\blet me\s+address\b.+\bproceed\b",
            r"\bproceed with the correct steps\b",
            r"\b(?:would you like|if you(?:'d| would)? like|if you want|let me know if you want)\b.{0,80}\b(?:continue|retry|inspect|check|read|search|write|patch|test|debug|create|save|package|install|use|look|run|open|browse)\b",
            r"\b(?:should i|shall i)\s+(?:continue|retry|inspect|check|read|search|write|patch|test|debug|create|save|package|install|use|look|run|open|browse)\b",
            r"\bi can also\b",
        )
        return any(re.search(pattern, lowered) for pattern in patterns)

    @staticmethod
    def _chatgpt_web_universal_tool_examples() -> str:
        return (
            "Universal tool-call cookbook (shape examples only; only use tool names currently listed in <tools> for this turn):\n"
            "0. If the user explicitly says to use terminal and check whoami, do it directly:\n"
            "<tool_call>\n"
            "{\"name\": \"terminal\", \"arguments\": {\"command\": \"whoami\"}}\n"
            "</tool_call>\n"
            "0b. If the user already approved a multi-step task, do the next obvious tool step immediately. Do not ask for permission again. Emit the next <tool_call> block now.\n"
            "0c. Natural-language repo tasks still require tools even when the user never says 'use terminal'. For a clone request, emit the guessed git clone call immediately:\n"
            "<tool_call>\n"
            "{\"name\": \"terminal\", \"arguments\": {\"command\": \"git clone --depth 1 'https://github.com/octocat/Hello-World.git' '/tmp/hello-world'\"}}\n"
            "</tool_call>\n"
            "0d. In a fresh chat, if the user asks for the current branch of a repo path, use terminal directly and do not ask permission again:\n"
            "<tool_call>\n"
            "{\"name\": \"terminal\", \"arguments\": {\"command\": \"git -C '/tmp/hello-world' rev-parse --abbrev-ref HEAD\"}}\n"
            "</tool_call>\n"
            "1. Search for code or text:\n"
            "<tool_call>\n"
            "{\"name\": \"search_files\", \"arguments\": {\"pattern\": \"stream_chatgpt_web_completion\", \"target\": \"content\", \"path\": \"hermes_cli/chatgpt_web.py\"}}\n"
            "</tool_call>\n"
            "2. Read a specific file or line range:\n"
            "<tool_call>\n"
            "{\"name\": \"read_file\", \"arguments\": {\"path\": \"run_agent.py\", \"offset\": 3900, \"limit\": 120}}\n"
            "</tool_call>\n"
            "3. Run a shell command in the workspace:\n"
            "<tool_call>\n"
            "{\"name\": \"terminal\", \"arguments\": {\"command\": \"rg -n \\\"tool_call\\\" run_agent.py\", \"working_dir\": \"/workspace/hermes-agent\"}}\n"
            "</tool_call>\n"
            "4. Run Python for inspection or generation:\n"
            "<tool_call>\n"
            "{\"name\": \"execute_code\", \"arguments\": {\"code\": \"from pathlib import Path\\nprint(Path('skills').exists())\"}}\n"
            "</tool_call>\n"
            "5. Create or update a skill/file, then verify it exists before claiming success:\n"
            "<tool_call>\n"
            "{\"name\": \"skill_manage\", \"arguments\": {\"action\": \"create\", \"name\": \"tool-customizability\", \"content\": \"---\\nname: tool-customizability\\n---\\n# Tool Customizability\\n...\"}}\n"
            "</tool_call>\n"
            "Verification follow-up shape:\n"
            "<tool_call>\n"
            "{\"name\": \"read_file\", \"arguments\": {\"path\": \"skills/tool-customizability/SKILL.md\", \"offset\": 1, \"limit\": 80}}\n"
            "</tool_call>\n"
            "5b. Schedule a simple cron job:\n"
            "<tool_call>\n"
            "{\"name\": \"cronjob\", \"arguments\": {\"action\": \"create\", \"name\": \"disk-check\", \"schedule\": \"every 1h\", \"prompt\": \"Use terminal to run df -h and report if any filesystem is above 90%.\"}}\n"
            "</tool_call>\n"
            "5c. Use browser tools in sequence when a web task needs several steps:\n"
            "<tool_call>\n"
            "{\"name\": \"browser_navigate\", \"arguments\": {\"url\": \"https://www.wikipedia.org\"}}\n"
            "</tool_call>\n"
            "After its <tool_response>, if the user still wants more, emit the next tool call immediately such as browser_snapshot, browser_click, browser_type, browser_press, or browser_vision.\n"
            "6. After a tool response, either guess the next tool call from the result or give the final answer. Never say tools are unavailable when a tool is listed in <tools>.\n"
            "To build your own next tool call, copy the shape above, replace name with a tool listed in <tools>, and provide an arguments object that matches that tool's schema exactly.\n"
            "If the task is still incomplete, do not ask the user whether to continue. Emit the single best next <tool_call> block immediately."
        )

    @staticmethod
    def _chatgpt_web_tool_guess_example(tool_name: str) -> str:
        examples = {
            "terminal": (
                "<tool_call>\n"
                "{\"name\": \"terminal\", \"arguments\": {\"command\": \"git status\"}}\n"
                "</tool_call>"
            ),
            "cronjob": (
                "<tool_call>\n"
                "{\"name\": \"cronjob\", \"arguments\": {\"action\": \"list\"}}\n"
                "</tool_call>"
            ),
            "browser_navigate": (
                "<tool_call>\n"
                "{\"name\": \"browser_navigate\", \"arguments\": {\"url\": \"https://www.wikipedia.org\"}}\n"
                "</tool_call>"
            ),
            "browser_snapshot": (
                "<tool_call>\n"
                "{\"name\": \"browser_snapshot\", \"arguments\": {\"full\": false}}\n"
                "</tool_call>"
            ),
            "browser_click": (
                "<tool_call>\n"
                "{\"name\": \"browser_click\", \"arguments\": {\"ref\": \"search-input\"}}\n"
                "</tool_call>"
            ),
            "browser_type": (
                "<tool_call>\n"
                "{\"name\": \"browser_type\", \"arguments\": {\"ref\": \"search-input\", \"text\": \"Hermes Agent\"}}\n"
                "</tool_call>"
            ),
            "browser_press": (
                "<tool_call>\n"
                "{\"name\": \"browser_press\", \"arguments\": {\"key\": \"Enter\"}}\n"
                "</tool_call>"
            ),
            "browser_vision": (
                "<tool_call>\n"
                "{\"name\": \"browser_vision\", \"arguments\": {\"question\": \"What is the visible page title text in the screenshot?\"}}\n"
                "</tool_call>"
            ),
            "search_files": (
                "<tool_call>\n"
                "{\"name\": \"search_files\", \"arguments\": {\"pattern\": \"TODO\", \"path\": \".\", \"target\": \"content\"}}\n"
                "</tool_call>"
            ),
            "read_file": (
                "<tool_call>\n"
                "{\"name\": \"read_file\", \"arguments\": {\"path\": \"README.md\", \"offset\": 1, \"limit\": 40}}\n"
                "</tool_call>"
            ),
            "write_file": (
                "<tool_call>\n"
                "{\"name\": \"write_file\", \"arguments\": {\"path\": \"notes.txt\", \"content\": \"done\\n\"}}\n"
                "</tool_call>"
            ),
            "patch": (
                "<tool_call>\n"
                "{\"name\": \"patch\", \"arguments\": {\"path\": \"README.md\", \"old\": \"before\", \"new\": \"after\"}}\n"
                "</tool_call>"
            ),
        }
        return examples.get(
            str(tool_name or "").strip(),
            "<tool_call>\n"
            + json.dumps(
                {
                    "name": str(tool_name or "").strip() or "tool_name",
                    "arguments": {"fill_required_fields": "with_real_values_from_the_user_request"},
                },
                ensure_ascii=False,
            )
            + "\n</tool_call>",
        )

    @classmethod
    def _chatgpt_web_missing_args_hint(cls, tool_name: str) -> str:
        tool_name = str(tool_name or "").strip()
        if not tool_name:
            return ""
        return (
            f"Hermes did not prefill {tool_name} arguments for this turn. "
            "You must infer the arguments yourself from the user's request and still emit a tool call now. "
            "Do not say the tool is unavailable. Do not leave the arguments object empty. "
            "Use the tool schema plus the user's request to guess the single best next call.\n"
            "Example shape with real argument keys:\n"
            f"{cls._chatgpt_web_tool_guess_example(tool_name)}"
        )

    def _chatgpt_web_should_force_followup_tool_call(
        self,
        payload_messages: list[dict[str, Any]],
        tool_name: str,
        tool_args: Optional[dict[str, Any]],
    ) -> bool:
        if tool_name != "read_file" or not isinstance(tool_args, dict):
            return False
        if not str(tool_args.get("path") or "").strip():
            return False
        last_tool_content = ""
        for item in reversed(payload_messages):
            if isinstance(item, dict) and item.get("role") == "tool":
                last_tool_content = str(item.get("content") or "")
                break
        tool_payload = self._chatgpt_web_parse_tool_payload(last_tool_content) if last_tool_content else None
        if not isinstance(tool_payload, dict):
            return False
        matches = tool_payload.get("matches")
        original_request = self._chatgpt_web_original_user_request(payload_messages)
        if isinstance(matches, list) and bool(matches):
            if self._chatgpt_web_answer_only_mode(original_request) == "line":
                return False
            return True
        if tool_payload.get("truncated"):
            try:
                next_offset = int(tool_args.get("offset") or 0)
            except Exception:
                next_offset = 0
            return next_offset > 1
        return False

    def _format_tools_for_chatgpt_web(self, tools: Optional[list[dict[str, Any]]] = None) -> str:
        selected_tools = tools if tools is not None else self.tools
        if not selected_tools:
            return "[]"
        formatted_tools = []
        for tool in selected_tools:
            func = tool.get("function") if isinstance(tool, dict) else None
            if not isinstance(func, dict):
                continue
            name = str(func.get("name") or "").strip()
            if not name:
                continue
            schema = self._compact_chatgpt_web_schema(func.get("parameters", {}))
            if not isinstance(schema, dict) or not schema:
                schema = {"type": "object"}
            formatted_tools.append({
                "name": name,
                "description": self._compact_chatgpt_web_description(func.get("description", "")),
                "parameters": schema,
            })
        return json.dumps(formatted_tools, ensure_ascii=False)

    def _chatgpt_web_tool_protocol(self, tools: Optional[list[dict[str, Any]]] = None) -> str:
        selected_tools = tools if tools is not None else self.tools
        if not selected_tools:
            return ""
        universal_examples = self._chatgpt_web_universal_tool_examples()
        tool_names = [
            str(tool.get("function", {}).get("name") or "").strip()
            for tool in selected_tools
            if isinstance(tool, dict) and isinstance(tool.get("function"), dict)
        ]
        tool_names = [name for name in tool_names if name]
        if len(tool_names) == 1:
            tool_label = tool_names[0]
            return (
                f"You have access to exactly one tool in this turn: {tool_label}. "
                f"That tool is available right now. Do not say tools are unavailable. "
                f"Ignore any later steps for now and focus only on using {tool_label} in this response. "
                "The user already approved the task, so do not ask for permission to continue with the obvious next tool step. "
                f"If the user's request needs {tool_label}, your next response must be EXACTLY ONE <tool_call>...</tool_call> block and nothing else. "
                "After you receive a <tool_response>, if the task is still in progress, make the next tool call immediately instead of narrating that you will continue later. "
                f"Use the latest <tool_response> plus the tool schema for {tool_label} to guess the next tool call needed to advance the main goal when another step is still needed.\n"
                f"<tools>\n{self._format_tools_for_chatgpt_web(selected_tools)}\n</tools>\n"
                "Tool call schema: {'name': <function-name>, 'arguments': <args-dict>}\n"
                f"Exact tool example for this turn:\n<tool_call>\n{{\"name\": \"{tool_label}\", \"arguments\": {{}}}}\n</tool_call>\n"
                f"{universal_examples}"
            )
        return (
            "You are running inside Hermes Agent's local tool loop over ChatGPT Web. "
            "If the user asks for live filesystem inspection, file search, command execution, Python/code execution, calculations, or current system/repo facts, you MUST call Hermes tools before answering. "
            "Never claim that tools are unavailable, inaccessible, or unsupported here. They ARE available through this local tool loop. "
            "Do not claim that a tool failed unless you actually emitted a <tool_call> block and were given a failing <tool_response>. "
            "The user's current request already authorizes the obvious in-scope tool calls needed to finish it, so do not ask whether to continue with the next step. "
            "For multi-step tasks, work iteratively: make the single best next tool call now, wait for the <tool_response>, then make the next tool call or provide the final answer. "
            "When the task is still underway, use the latest <tool_response> plus the tool schemas to guess the next tool call needed to advance the main goal. "
            "Do not reply with progress narration like 'I will continue' or unsupported completion claims. "
            "When a tool is needed, respond with EXACTLY ONE <tool_call>...</tool_call> block and no surrounding commentary. "
            "After tool execution, you will receive tool outputs inside <tool_response>...</tool_response> blocks and should then continue the task.\n"
            f"<tools>\n{self._format_tools_for_chatgpt_web(selected_tools)}\n</tools>\n"
            "For each function call return a JSON object with this schema: {'name': <function-name>, 'arguments': <args-dict>}. "
            "Each function call must be enclosed within <tool_call> </tool_call> XML tags.\n"
            f"{universal_examples}"
        )

    def _chatgpt_web_salvage_malformed_tool_call(
        self,
        message_text: str,
        payload_messages: list[dict[str, Any]],
    ) -> list[SimpleNamespace]:
        if not isinstance(message_text, str) or "<tool_call" not in message_text:
            return []
        name_match = re.search(
            r"<tool_call>\s*[\s\S]{0,20000}?\"name\"\s*:\s*\"([A-Za-z0-9_. -]+)\"",
            message_text,
            re.IGNORECASE,
        )
        if not name_match:
            return []
        hinted_name = str(name_match.group(1) or "").strip()
        if not hinted_name:
            return []
        available_tool_names = {
            str(tool.get("function", {}).get("name") or "").strip()
            for tool in (self.tools or [])
            if isinstance(tool, dict)
        }
        available_tool_names.discard("")
        valid_tool_names = set(self.valid_tool_names) | available_tool_names
        hinted_lower = hinted_name.lower()
        hinted_normalized = hinted_lower.replace("-", "_").replace(" ", "_")
        repaired_name = None
        for candidate in (hinted_name, hinted_lower, hinted_normalized):
            if candidate in valid_tool_names:
                repaired_name = candidate
                break
        if repaired_name is None:
            repaired_name = self._repair_tool_call(hinted_name)
        if not repaired_name:
            return []
        inferred_args = self._chatgpt_web_tool_args(
            repaired_name,
            payload_messages or [{"role": "user", "content": message_text}],
        )
        if inferred_args is None:
            return []
        synthetic_block = (
            "<tool_call>\n"
            + json.dumps(
                {"name": repaired_name, "arguments": inferred_args},
                ensure_ascii=False,
            )
            + "\n</tool_call>"
        )
        extracted_tool_calls, _ = _extract_xml_tool_calls_from_text(synthetic_block)
        return extracted_tool_calls

    def _chatgpt_web_normalize_extracted_tool_calls(
        self,
        tool_calls: list[SimpleNamespace],
        payload_messages: list[dict[str, Any]],
    ) -> list[SimpleNamespace]:
        for tool_call in tool_calls:
            function = getattr(tool_call, "function", None)
            tool_name = str(getattr(function, "name", "") or "").strip()
            if not tool_name:
                continue
            parsed_args = _parse_tool_call_arguments(getattr(function, "arguments", None))
            if tool_name == "browser_vision":
                question = parsed_args.get("question") if isinstance(parsed_args, dict) else None
                if not isinstance(question, str) or not question.strip():
                    inferred_args = self._chatgpt_web_tool_args(
                        tool_name,
                        payload_messages or [{"role": "user", "content": tool_name}],
                    )
                    if inferred_args is not None:
                        function.arguments = json.dumps(inferred_args, ensure_ascii=False)
        return tool_calls
