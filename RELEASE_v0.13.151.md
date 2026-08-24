# Hermes Agent Fork v0.13.151

This corrective Android release fixes the physical-device Nanbeige chat path
introduced with v0.13.150. It preserves the separate Stable and Experimental
TurboQuant / Nanbeige llama.cpp runtimes, advanced in-app server controls, and
one-shot RAM override while repairing stale lane state, ordinary-chat routing,
response cleanup, and long-generation feedback.

## Nanbeige physical-device repairs

- Reconciles a verified catalog artifact with its required runtime lane at the
  final start boundary. An exact Nanbeige Q4_K_M file can no longer remain on
  the incompatible Stable lane because of stale persisted settings; Hermes
  persists Experimental TurboQuant / Nanbeige before launching it and fails
  closed if that state cannot be committed. After startup, the Settings draft
  mirrors the authoritative persisted lane so the same screen cannot continue
  to display or reapply stale Stable state.
- Keeps lane selection content-authoritative. The repair is applied only after
  the artifact's exact byte count and SHA-256 have been verified, while unknown,
  lane-neutral, and already-correct model files preserve the user's lane. A
  verified artifact which declares an unknown nonblank lane now fails closed
  instead of silently launching an incompatible runtime.
- Gives every native-chat lane the same request-scoped tool boundary. Ordinary
  non-action chat receives no native tool schema; an affirmative action request
  receives only its exact tool/action schema and at most one dispatch across
  initial and recovery rounds. A fully validated typed tool invocation bypasses
  the model and carries its parsed arguments directly. These boundaries apply
  in English, Chinese, Spanish, German, Portuguese, and French. TurboQuant alone
  still receives its separate `reasoning_format=none` response-format repair;
  Stable llama.cpp, LiteRT-LM, and remote inference behavior otherwise remains
  unchanged.
- Fails closed on raw privileged-shell requests made from chat, including saved
  automations whose shell step uses Shizuku. The current Shizuku AIDL boundary
  cannot cancel a remote shell after Binder dispatch, so chat neither advertises
  nor starts that long-running operation. The explicit manual and background
  automation surfaces retain their existing privileged-shell capability.
- Conservatively repairs the one duplicated-answer/orphan-close `<think>` shape
  observed on the phone while preserving unmatched tags, literal examples,
  fenced code, tool JSON, and other legitimate text. The physical-device payload
  shape which previously
  displayed `NANBEIGE_OK\n</think>\n\nNANBEIGE_OK` now resolves to the assistant
  answer instead of exposing protocol residue.

## Chat completion and progress repairs

- Shows an always-visible elapsed-generation indicator while a non-streaming
  local request is running, so a slow phone generation no longer looks inert
  when the composer status is outside the visible viewport.
- Makes Stop terminal: cancelling an in-flight request replaces that request's
  owned in-flight assistant response (whether still blank or partially
  streamed) with a localized stopped message and prevents a late completion
  from overwriting the cancellation result.
- Makes terminal failures visible as localized assistant messages instead of
  leaving a permanent `…` placeholder. A real answer which wins the race is
  never replaced by the fallback status.
- Gives each send exclusive request ownership across persistence, SSE callbacks,
  Stop, fast resend, and conversation navigation. A late callback from request A
  cannot mutate or cancel request B, and leaving a conversation terminalizes its
  own in-flight assistant response before the next chat becomes active.
- Localizes the new progress, Stop, and failure messaging across all six Android
  languages carried by the v0.13.150 interface.

## Physical diagnosis and candidate boundary

The v0.13.150 failure was reproduced on a connected Nubia NX789J running
Android 16 with the exact 2,574,807,840-byte
`Tdamre/Nanbeige4.2-3B-GGUF` Q4_K_M artifact. Stable llama.cpp failed with
`unknown model architecture: 'nanbeige'`; changing only the lane to
Experimental TurboQuant / Nanbeige loaded the model. An authenticated direct
no-tool request to the same owned server then returned a non-empty completion,
while the app's ordinary-chat request exposed malformed think markup and could
enter unsolicited tool rounds. That diagnosis identifies the repaired
boundaries, but it is not v0.13.151 candidate certification.

Before tagging, the v0.13.151 candidate must be installed over the existing app
without clearing its data and prove all of the following on the physical ARM64
phone: a stale Stable selection self-reconciles after exact artifact
verification; Nanbeige becomes ready; an ordinary prompt produces no
unsolicited tool card and a non-empty marker-free answer; elapsed progress is
visible while generation is active; and Stop/failure leaves a terminal message
rather than a blank placeholder. The normal source-bound phone/tablet evidence,
unit, instrumentation-compile, debug/release, signing, and exact-artifact gates
remain required. No claim is made for device-specific GPU, NPU, or accelerator
performance.

## Release and F-Droid boundaries

The GitHub release is produced only from the exact annotated v0.13.151 tag after
the source-bound evidence and physical-device proof have been committed and
verified. The workflow does not persist checkout credentials while repository
code runs, and it revalidates the live annotated tag and default-branch head
before signing, draft creation, asset upload, and final publication. Hosted
signing must produce the approved Android certificate and the expected universal
APK/AAB pair before the draft is published.

The phone candidate is signed by a separate nonpublishing workflow which accepts
only the live default-branch SHA through `repository_dispatch` and rechecks that
authority before secrets and again before upload. The tag workflow rebuilds
deterministically and refuses publication unless the final signed APK's byte
count and SHA-256 exactly match that certified phone candidate. It also
re-extracts the complete 12-file Stable ARM64 runtime closure from those final
APK bytes and verifies every entry's byte count and SHA-256 against the
committed physical-device record before publication.

The post-release F-Droid procedure remains intentionally no-MR. A fresh local
clone of live `fdroiddata` must detect v0.13.151/145190 from the published
GitHub tag, preserve the resolved full commit through the source-binding
overlay, and pass the pinned buildserver/reproducibility comparison. No
fdroiddata commit, push, fork, or merge request is created by this release
workflow.
