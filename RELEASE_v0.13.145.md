# Hermes Agent Fork v0.13.145

This release makes local Android agents easier to follow, faster to stop, and substantially more reliable across LiteRT-LM and GGUF runtimes.

## Agent timeline and compact chat

- Streams and persists distinct `thought`, `tool_call`, `tool_result`, `file_access`, `process_log`, and `final_answer` events in order.
- Renders intermediate work as compact, collapsed timeline cards with hierarchical indentation and a show/hide toggle.
- Keeps reasoning out of the final answer card and adds denser chat spacing plus an adjustable global font scale.
- Adds an immediate Stop action that cancels active local inference, SSE requests, and tool work.

## Settings and terminal

- Splits Settings into focused `/settings`, `/settings/models`, `/settings/theme`, and `/settings/tools` pages with breadcrumbs and shared state.
- Adds a user-facing manual Linux terminal for host and PRoot commands.
- Localizes the new timeline, terminal, Settings, status, card, and accessibility labels in English, Chinese, Spanish, German, Portuguese, and French.

## Local-model reliability

- Recognizes safe natural-English terminal requests from small local models, including MiniCPM5 1B LiteRT-LM, instead of returning that no tools are available.
- Adds MiniCPM/Qwen tagged, XML, and fenced-JSON tool-call parsing while keeping arbitrary prose from being executed as shell commands.
- Uses Android's system shell whenever the embedded Linux state is in system-shell fallback mode, avoiding missing Termux-library failures.
- Stops GGUF readiness polling immediately when llama.cpp exits and launches fallback GGUF servers through the correct shell.
- Recovers safely when Android restores encrypted preferences without the matching Keystore key; only unrecoverable secrets are reset.

## Certification

- Headed Android 15 emulator checks covered compact timelines, the manual terminal, all Settings routes, font/theme controls, and all six languages.
- Verified Qwen 3.5 0.8B GGUF, MiniCPM5 1B LiteRT-LM, MiniCPM5 1B Fable5 GGUF, and VibeThinker 3B LiteRT-LM with real local completions.
- Android unit, lint, debug/release APK, AndroidTest APK, and release AAB gates run in the pinned F-Droid buildserver image.
- F-Droid metadata remains tag-driven at version `0.13.145` / versionCode `144590`; no manual GitLab merge request is required.
