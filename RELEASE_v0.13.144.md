# Hermes Agent Fork v0.13.144

This release hardens Android language switching and expands the verified local-model matrix.

## Android fixes

- Localizes Kanban controls, filters, empty states, runtime statuses, and task actions in all six supported languages.
- Localizes language-picker accessibility descriptions instead of leaving screen-reader labels in English.
- Localizes the local-memory, phone-automation, Skills, and Streamable HTTP MCP cards and dialogs.
- Adds the requested Qwen 3.5, MiniCPM5, and VibeThinker artifacts to the recommended local-model catalog.
- Allows large LiteRT-LM instrumentation fixtures to use an explicit readable model path without duplicating multi-gigabyte files.

## Verified local inference

- Qwen 3.5 0.8B Q4_K_M GGUF through the embedded llama.cpp server.
- MiniCPM5 1B Claude Opus Fable5 Thinking Q4_K_M GGUF through the embedded llama.cpp server.
- MiniCPM5 1B mobile LiteRT-LM through the native LiteRT-LM runtime.
- VibeThinker 3B LiteRT-LM through the native LiteRT-LM runtime on a dedicated 6 GB Android 15 emulator.

Each model test required runtime startup and a non-empty OpenAI-compatible local chat completion; skipped tests were not counted.

## Validation

- Six-language headed-emulator screenshot and accessibility-tree matrix.
- Android unit, lint, debug/release assembly, and targeted instrumentation gates.
- Android-related Python regression suite used by the Android CI workflow.
- F-Droid pinned-toolchain and reproducibility gates remain part of release certification.
