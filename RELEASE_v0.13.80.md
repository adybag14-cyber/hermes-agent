# Hermes Agent v0.13.80

## Android local model runtime

- Enables Gemma 4 LiteRT-LM multimodal capability detection for image and audio model records.
- Uses LiteRT-LM model capability metadata to gate Gemma 4 speculative decoding, with CPU fallback on x86 emulator builds.
- Passes chat template extra context through to LiteRT-LM so local Gemma and Qwen prompts can disable thinking where needed.
- Narrows native tool schemas to explicitly requested tools for smaller local GGUF models.
- Adds deterministic handling for explicit `terminal_tool` commands while keeping normal chat and tool selection model-driven.

## Android Tasker/Shizuku parity

- Keeps Tasker terminology for saved Android automations and Shizuku/Sui terminology for privileged device actions.
- Validates Qwen 3.5 0.8B GGUF tool use through llama.cpp on the emulator, including file creation, deletion, and Android system status inspection.
