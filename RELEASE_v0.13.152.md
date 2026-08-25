# Hermes Agent Fork v0.13.152

This corrective Android release supersedes the immutable `v0.13.151` tag,
whose workflow stopped before signing and never produced a published GitHub
release. It retains the Nanbeige/TurboQuant, request-scoped tool-authority,
generation-progress, terminal Stop, and local-runtime repairs certified for the
prior candidate while closing a response-body cancellation gap discovered by
the hosted release gate.

## Request-owned streaming cancellation repair

- Retains every exact OkHttp `Call` in a request-owned registry from outermost
  interceptor entry until the returned response body reaches EOF, closes, or
  fails. A synchronous `execute()` call is therefore still cancellable after
  OkHttp has removed it from `Dispatcher.runningCalls()` at the response-header
  boundary.
- Makes cancellation sticky and linearizable with call registration. Stop can
  no longer race a call which is entering the transport: either registration
  sees the prior cancellation and fails before dispatch, or cancellation sees
  and cancels the registered exact call.
- Enforces a fresh dispatcher inside the request-owned transport itself, even
  when integration supplies a shared base client. Cancelling request A cannot
  reach request B; dispatcher cancellation remains only a secondary mechanism
  for queued or pre-interceptor asynchronous work.
- Applies the same exact-call lifetime boundary to remote chat fallback and to
  native-tool HTTP paths, including request-owned package operations, verified
  Docker-layer fallback downloads, terminal network fallback, and Android
  automation network work.
- Preserves the last verified layer while a replacement streams into a unique
  temporary file. Cancellation removes the request's partial file without
  deleting or replacing the durable entry, and without stopping another
  request's download.

## Deterministic regression coverage

- Reproduces the original gap at the body-stream boundary: the response body is
  active and the layer partial exists while OkHttp reports zero dispatcher
  calls. This proves that a relaxed dispatcher assertion would hide a production
  defect rather than repair the test.
- Captures both exact calls with event-listener latches, brings the independent
  request B online first, then cancels A while its response body is streaming.
  The test requires A cancelled, B still active, A's prior sentinel preserved,
  A's partial removed, and both request registries empty after their own stops.
- The repaired regression passed 50 consecutive fresh server/client iterations
  on Windows. The expanded adjacent cancellation suites passed 117/117 tests,
  and the complete Android unit suite passed 990/990 tests across 91 suites with
  zero failures, errors, or skips before release recertification.

## Retained Android and Nanbeige behavior

- A catalog-verified Nanbeige Q4_K_M artifact still reconciles stale Stable
  state to the Experimental TurboQuant / Nanbeige lane before launch, with the
  persisted and visible Settings state kept authoritative.
- An explicitly selected local backend still starts after the first fresh
  process UI frame without remote-fallback authority, while later Settings or
  model actions invalidate a stale startup generation.
- Ordinary chat still receives no native schema unless one concrete action is
  requested, validated typed tools still bypass model startup with their exact
  arguments, and no request may dispatch more than one native action across
  initial and recovery rounds.
- Local generation still exposes elapsed progress, terminalizes Stop and
  visible failures, removes the observed duplicated Nanbeige think residue, and
  rejects uncancellable privileged-shell routes from chat.

## Release and F-Droid boundaries

The public annotated `v0.13.151` tag remains unchanged as historical evidence;
it has no published GitHub release or release assets. `v0.13.152` is built and
certified from a new source digest, a new signed phone candidate, and freshly
captured physical-phone plus headed phone/tablet evidence. The tag workflow must
recheck the live default head and annotated tag before signing, draft creation,
asset upload, and publication, and its final universal APK must match the
physical candidate byte for byte.

After publication, a fresh local `fdroiddata` clone must detect exactly
`0.13.152`/`145290`, preserve its resolved full commit through the committed
source-binding overlay, and pass the pinned buildserver binary comparison and
allowed-signer check. This release intentionally creates no fdroiddata commit,
push, fork, or merge request.
