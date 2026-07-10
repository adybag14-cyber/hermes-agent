# Small local model smoke results (HermesX86Api35)

Date: 2026-07-10  
APK: debug `com.mobilefork.hermesagent` with instrumented `SmallLocalModelsInstrumentedTest`

| Model | Format | Size | Backend | Result | Notes |
|-------|--------|------|---------|--------|-------|
| **MiniCPM 5 1B** | `.litertlm` | 1.05 GB | LiteRT-LM | **PASS** | ~68s first load+chat |
| **Qwen2.5 1.5B Instruct** | `.litertlm` | 1.52 GB | LiteRT-LM | **PASS** | ~43s first load+chat |
| **Gemma 4 E2B** | `.litertlm` | 2.46 GB | LiteRT-LM | **PASS** | Needs ≥2.5 GB free `/data`; truncated copy fails size check |
| **Qwen3.5 0.8B Q4_K_M** | `.gguf` | 0.51 GB | llama.cpp | **PARTIAL** | Needs full Linux assets in APK (`libhermes_android_llama_server.so`). With assets, server starts (~18s) but empty `content` observed once — retest after chat response parsing fix |
| **Gemma 3 1B INT4** | `.litertlm` | ~0.56 GB | LiteRT-LM | **NOT RUN** | HF gated (401 without `HF_TOKEN`) |

## Host model cache

`C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_models\`

- `MiniCPM5-1B.litertlm`
- `Qwen2.5-1.5B-Instruct.litertlm`
- `gemma-4-E2B-it.litertlm`
- `Qwen3.5-0.8B-Q4_K_M.gguf`
- `Qwen_Qwen3.5-0.8B-Q4_K_M.gguf` (duplicate)

## Emulator constraints

- Default AVD `/data` was ~1 GB free → multi-model provision failed; use `pm trim-caches` or sequential single-model provision.
- Heavy LiteRT loads can drop adb offline on x86_64 emulator; retest one model per boot when flaky.

## Catalog updates (app)

- Added MiniCPM 5 1B + Qwen3.5 0.8B GGUF to default model catalog / first-class presets.
- Inference defaults for MiniCPM / 0.8B / Gemma3-1B in `OnDeviceBackendManager`.
