# Hermes Agent Fork v0.13.147

This Android overhaul delivers a cohesive, original liquid-glass-inspired
Material 3 interface and hardens both on-device inference backends. The issue
fixes listed here become release claims only after the certification gates at
the end of this document pass on the exact tagged commit.

## Interface, layout, and localization

- Extends translucent, layered glass surfaces across the shell, chat, terminal,
  Device, Portal, authentication, Settings, and navigation while preserving
  every built-in and custom theme.
- Fixes custom color-role derivation and saved rounded/square shape selection.
- Adapts navigation and content widths for compact phones, standard phones, and
  tablets, with accessible labels and touch targets.
- Completes English, Simplified Chinese, Spanish, German, Portuguese, and French
  coverage for the changed Android surfaces.
- Finishes the Android monochrome adaptive icon configuration for themed icons.
- Preserves model paragraph breaks and whitespace-only newline SSE deltas.

## LiteRT-LM and GGUF

- Updates the exact reproducible LiteRT-LM Android SDK pin from `0.14.0` to
  `0.16.0` and retains a live Google Maven freshness gate.
- Adds exact preview-version and local-main-AAR build inputs so upstream/nightly
  compatibility can be tested without using a moving dependency in release or
  F-Droid builds. Google currently publishes no Android nightly Maven channel.
- Replaces total-RAM-only admission with current-memory-headroom checks, safer
  context budgets, clear accelerator fallback reporting, and recovered native/
  low-memory exit diagnostics.
- Supports single-file, chat-ready GGUF v2/v3 artifacts with an embedded chat
  template; validates metadata before launch, classifies llama.cpp exits, requires a
  real completion canary after `/v1/models`, and prevents a false local-ready or
  silent remote-fallback result.
- Locks the headed validation matrix to exact Hugging Face files and device byte
  sizes for Qwen3.5 0.8B GGUF, MiniCPM5 1B Fable5 GGUF, MiniCPM5 1B LiteRT-LM,
  and VibeThinker 3B LiteRT-LM. A matrix entry is certified only when its exact
  bytes produce durable, nonblank completion evidence for this release.

## Terminal and documentation

- Repairs and diagnoses Android 15/PRoot exit-code-126 execution routing so
  one-click Debian commands use the app-private executable/QEMU path rather
  than an Android `noexec` location.
- Replaces the stale Android MVP document with installation, model-format,
  tool-use, memory sizing, build, emulator, troubleshooting, release, and
  no-MR F-Droid updater guidance.

## Release gates

The release is certified only after scoped Python tests, Kotlin unit tests,
lint, APK/AAB assembly, headed API 35 phone/tablet/six-language checks, real
LiteRT-LM and GGUF completions, frame/memory capture, signed GitHub assets, a
fully green Android Release workflow, pinned F-Droid reproducibility, and a
disposable local `checkupdates` preview. No GitLab merge request is opened by
this release process.
