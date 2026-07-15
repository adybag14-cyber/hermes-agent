# Hermes Agent Fork v0.13.146

This release refreshes the Android app around a compact emerald/noir design and makes small local-model tool use more dependable.

## Interface and localization

- Applies the emerald/noir palette across the shell, chat, terminal, Kanban, Device, and Settings surfaces while preserving user-defined themes.
- Splits Device into focused overview, Linux, connectivity, files, controls, and automation pages.
- Completes English, Chinese, Spanish, German, Portuguese, and French coverage for the new cards, routes, model-tool guidance, terminal sessions, Kanban labels, and accessibility descriptions.
- Migrates only the exact legacy built-in purple palette, leaving custom themes untouched and retaining the old palette as an explicit preset.

## Local models and tools

- Adds small, general, and large tool-schema profiles for local inference.
- Publishes a curated, argument-aware tool catalog by default before every small-model request, including terminal, Linux sandbox, files, Android automation, diagnostics, and local memory.
- Keeps a compact full catalog available for larger models.
- Fixes manual `proot-distro login`, `run`, and `sh` commands so interactive sessions remain responsive and subsequent commands execute inside the selected sandbox.

## Certification

- Headed Android 15 checks covered all six languages, Settings model guidance, Kanban, Device routes, the restored Alpine PRoot sandbox, and compositor-settled screenshots.
- Verified real completions from Qwen3.5 0.8B GGUF, MiniCPM5 1B Fable5 GGUF, MiniCPM5 1B LiteRT-LM, and VibeThinker 3B LiteRT-LM; MiniCPM also executed a natural-language terminal request through the curated default schema.
- Captured 557 headed emulator frames at 18 ms median, 30 ms p95, and 5.03% deadline-missed jank, plus cold/warm launch and memory evidence.
- Android unit, lint, APK/AAB, AndroidTest, Python, signed-release, and pinned F-Droid reproducibility gates are required before publication.
- F-Droid metadata remains tag-driven at version `0.13.146` / versionCode `144690`; no manual GitLab merge request is required.
