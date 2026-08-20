# Hermes Agent Fork v0.13.148

This Android release completes the v0.13.147 overhaul with stricter native
runtime ownership, broader themed and localized UI coverage, and a real
one-click Debian HTTPS repair. Every device claim below remains conditional on
the exact-tag release gates at the end of this document.

## Interface, startup, and localization

- Applies the persisted color palette, shape, and font scale from the first
  Compose frame onward, with an Android 12+ Hermes splash and no legacy dark
  preview flash.
- Extends the shared glass treatment and responsive layout to the manifested
  Tasker editors and provider setup page without forcing external web content
  into the app palette.
- Localizes Tasker choices, provider failures, direct native-tool status, and
  every current recommended-model card in English, Simplified Chinese,
  Spanish, German, Portuguese, and French.
- Adds manifest-v3 headed evidence on phone and tablet for every destination,
  Device subpage, theme/shape/font bucket, English framework Activity, and
  cold-start launch lane. The phone lane additionally covers every recommended
  card and localized framework Activity in all six languages. Launch videos
  require explicit human review; the host tooling cannot self-certify pixels.

## Local inference safety and compatibility

- Pins the reproducible Android release and F-Droid build to Google Maven's
  current stable `com.google.ai.edge.litertlm:litertlm-android:0.16.1` artifact.
- Bounds LiteRT-LM startup probes, engine initialization, completion canaries,
  generation, replacement, and shutdown with one native ownership protocol.
  A timed-out or failed cleanup blocks retry and cross-backend fallback until
  the native owner exits safely or the app is force-stopped and reopened.
- Prevents stale remote or cached endpoints from bypassing a restart-required
  local state, and refuses to start local and remote runtimes over one another
  when either stop operation fails.
- Limits automatic model selection to exact immutable release-certified
  artifacts. Unknown-size, mutable, mismatched, experimental, and over-5-GiB
  catalog rows are not quick-started; Gemma 4 12B remains unsupported on
  nominal 16-GiB phones and is blocked before a new native engine is created.
- Treats the historical pinned Gemma 4 E4B artifact as an experimental,
  text-only lane. Its release evidence is deliberately scoped to CPU with
  speculative decoding disabled on the certified x86_64 AVD; it is not a
  Snapdragon, Adreno, NPU, GPU, multimodal, or current-upstream-MTP claim.
- Removes the misleading NPU selector because Hermes currently provides CPU
  and GPU LiteRT-LM backends, not a distinct Android NPU implementation.
- Adds a daily/manual published-latest LiteRT-LM compatibility workflow and an
  advisory immutable llama.cpp/Termux drift report without weakening the
  content-addressed release pins.

## Tools and Debian sandbox

- Routes the exact read-only time request before provider selection, so “Run a
  command to tell me what time it is.” executes the built-in `date` route with
  a visible tool trace and no provider request. Arbitrary explicit shell text is
  not promoted into this provider-neutral path.
- Routes the exact “Check my device status” request to native Android status
  diagnostics before provider selection, with a visible diagnostic trace and
  zero model or provider requests.
- Restricts the embedded app to the audited Android tool profile. External MCP
  transports, user plugins and context engines, process-backed ACP/Codex
  provider modes, and async web/vision tools remain disabled until their
  threads and descendants can participate in the same verified shutdown
  contract. Stored MCP configuration is retained for export but not executed;
  desktop and CLI capability is unchanged.
- Keeps Debian commands on a guest-only PATH, invokes packaged multicall tools
  through trusted argv0-preserving symlinks, seeds the guest CA bundle from the
  readable Android trust store, and installs curl without the pathological
  recommended-package delay.
- Gives the full Debian lifecycle the intended bounded update window and
  records incomplete deployments honestly for inspection or retry. The headed
  gate requires `id`, `uname -a`, `curl --version`, and real HTTPS retrieval to
  succeed through the packaged APK-native PRoot/QEMU route.

## Release gates and evidence boundaries

The release is certified only after the 12-worker Python and Kotlin suites,
lint, source-bound APK/AAB assembly, hardware-accelerated API 35 phone/tablet
UI and launch review, Debian HTTPS proof, release-matrix GGUF/LiteRT-LM
completions, the scoped historical E4B CPU/speculation-off record,
Macrobenchmark capture, signed GitHub assets, a fully green Android Release
workflow, and pinned F-Droid reproducibility all pass on the exact tag.

No physical Galaxy S24/Snapdragon/Adreno repair is claimed by the x86_64 AVD
evidence. The no-MR F-Droid check remains a disposable local autoupdater preview;
this release process does not open or push a GitLab merge request.
