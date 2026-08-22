# Hermes Agent Fork v0.13.150

This Android release adds a separate, opt-in llama.cpp lane for Nanbeige and
TurboQuant while preserving the previously certified Stable compatibility
backend for existing models. It also makes advanced llama-server controls
available in-app, with fail-closed ownership and validation around the options
Hermes must continue to manage itself.

## Nanbeige and TurboQuant lane

- Adds an Experimental TurboQuant / Nanbeige runtime built from the pinned
  `TheTom/llama-cpp-turboquant` commit
  `e30664a710b62aaf13c6b12e39e74500e6ce21ef`, without replacing or modifying
  the Stable Termux `llama-cpp` b9784 lane.
- Adds the exact content-addressed
  `Tdamre/Nanbeige4.2-3B-GGUF` Q4_K_M artifact requested by users: revision
  `128d8e87d69f9c1a30c37e40530c69deda96475d`, 2,574,807,840 bytes, SHA-256
  `99c7bfb88907f7eee0a04c4314f1c46bca391819478d8cb90b3e164f09576489`.
- Selects and persists the required TurboQuant lane before the Nanbeige
  download begins and reasserts it when that model becomes preferred or starts.
- Uses Turbo3 K/V cache plus Flash Attention for the release-matrix Nanbeige
  proof, and prevents the former `unknown model architecture: 'nanbeige'`
  failure from being mistaken for ordinary readiness.
- Keeps the experimental executable CPU-only and isolated as a separately
  packaged Android system library with pinned source, patch, NDK, ABIs, build
  definitions, deterministic toolchain contract, hashes, ELF checks, 16-KiB
  alignment checks, and bundled third-party notices.

## In-app llama.cpp controls

- Adds independent K-cache and V-cache selectors. Stable formats include
  `q5_0` and `q5_1`; the experimental lane additionally exposes `turbo2`,
  `turbo3`, and `turbo4`. The UI explains that llama.cpp has no formats named
  `q5_k` or `q5_v`—users select a supported Q5 type independently for K and V.
- Adds Flash Attention Default, Auto, On, and Off choices and rejects unsafe or
  unsupported combinations, including quantized V or Turbo caches with Flash
  Attention forced off.
- Adds expert additional arguments as one argv token per line. Hermes rejects
  positional injection, control characters, `--flag=value`, duplicate managed
  options, endpoint/API/TLS/download overrides, model and RAM-policy overrides,
  device-placement overrides, chat/tool-protocol overrides, and incorrect arity
  for reviewed performance flags. The selected pinned backend remains the
  final semantic validator for other expert options.
- Shows the effective advanced configuration, fingerprints it without exposing
  raw arguments, and performs an owned-process restart plus readiness and real
  completion canary whenever the applied configuration changes.
- Omits expert argv from portable settings exports; importing a redacted bundle
  clears destination-local expert arguments instead of silently retaining them.

## Dangerous one-shot RAM override

- Adds an explicitly dangerous, confirmed **Try once despite RAM warning**
  action for users who want to experiment with a model which exceeds Hermes'
  conservative RAM estimate.
- The override is consumed by one llama.cpp startup attempt only. It is neither
  persisted nor exported and does not bypass artifact/GGUF validation,
  content-addressed checks, executable checks, loopback ownership, readiness,
  the completion canary, or fail-closed cleanup.
- Normal launches retain the bounded mobile context defaults. Android or the
  native allocator may still kill an oversized attempt, and the UI states that
  risk before it proceeds.

## Runtime, state, and security repairs

- Gives every owned llama.cpp process a fresh 256-bit loopback bearer token.
  Public health/model probes are used only for readiness, while Hermes verifies
  that an unkeyed data-bearing request is rejected before using authenticated
  completion and chat endpoints.
- Rechecks loopback-port availability at the spawn boundary, retains the exact
  owned process handle, and confirms that process is still alive after health
  and completion checks.
- Adds the TurboQuant-only response formatting needed to prevent short
  Nanbeige answers from appearing solely as hidden reasoning content.
- Tightens local-model selection, download, settings, authentication, startup,
  readiness, and chat state authority so stale asynchronous work cannot replace
  a newer user choice or advertise a runtime configuration which was not the
  one actually applied.
- Keeps diagnostics and user-visible errors free of bearer tokens and raw
  expert arguments, while surfacing actionable lane, model, RAM, and restart
  failures.
- Localizes the advanced controls and safety messaging across all six supported
  Android languages and keeps Stable-lane descriptions version-neutral.

## Release and F-Droid boundaries

The GitHub release is produced only from the exact annotated tag after the
complete manifest-v3 source-bound phone/tablet UI, launch, performance, issue,
and real-model evidence has been committed and verified. Hosted signing must
produce the approved Android certificate and the expected universal APK/AAB
pair before the draft is published.

The post-release F-Droid procedure is intentionally no-MR: a fresh local clone
of live `fdroiddata` must detect v0.13.150/145090 from the GitHub tag, preserve
the resolved full commit through the source-binding overlay, and pass the pinned
buildserver comparison. No fdroiddata commit, push, fork, or merge request is
created by this release workflow. Emulator evidence does not claim physical
Snapdragon, Adreno, NPU, or device-specific performance.
