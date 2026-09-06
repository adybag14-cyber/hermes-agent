# Chaquopy and local-model pre-release laboratory

This is an opt-in development lane, not a signed release or a promotion of all
Python dependencies into the production default. Physical ARM64 phone testing
and version finalization follow this phase. Do not install this debug APK over
an existing signed production app or downgrade/uninstall that app to make it fit.

Lab builds install side by side as `com.mobilefork.hermesagent.lab`, labelled
**Hermes Lab (experimental)** with a `-lab` version suffix. Their test package is
`com.mobilefork.hermesagent.lab.test`. OAuth deep links and external automation/
Tasker entry points are disabled in the lab overlay so it cannot intercept the
released app's integrations. Those integrations require a later signed-candidate
gate; these isolated checks do not certify an in-place data migration.

## Build inputs

- LiteRT-LM 0.17.0; Kotlin/Compose 2.4.10; AGP 9.1.1 built-in Kotlin; Gradle 9.3.1.
- Chaquopy 17.0.0 Gradle/Java/JNI with the fork's source-built Python bootstrap
  17.0.1, selected exclusively from the sealed consumer bundle.
- Full Hermes Python 3.13 only. The standalone Chaquopy SDK app's 3.14 results
  do not override Hermes's Python upper bound.

Prepare a bundle with `compat/hermes/prepare_hermes.py` in
`adybag14-cyber/chaquopy`, following its README. Keep wheels, APKs, model binaries
and diagnostic media outside Git; publish CI output as Actions artifacts.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest `
  --max-workers=12 -PhermesChaquopyLab=C:/absolute/path/to/new-consumer
python ../scripts/verify_android_runtime_package.py `
  app/build/outputs/apk/debug/app-debug.apk --chaquopy-lab
```

The lab build verifies the exact input inventory and resolved bootstrap. Without
the property, the normal pinned Python requirements and placeholders remain.
Never bypass a receipt failure or edit its hashes to accept changed inputs.

## Separate acceptance checks

The opt-in `src/chaquopyLabAndroidTest` tests distinguish:

- Genuine SDK imports/behavior and two real Hermes agent turns against a loopback
  HTTP fixture (`ChaquopyHermesIntegrationInstrumentedTest`). This is not model inference.
- Exact-model size/hash, runtime readiness, visible reply content and optional
  model-originated sandbox tool calls (`LocalModelExperimentInstrumentedTest`).
  A denial or plain “I cannot execute” is not successful agentic execution.
- A real anonymous Android DownloadManager transfer, then size and SHA-256
  verification (`ModelScopeDownloadInstrumentedTest`). Preallocated file size
  must never override the system download status.
- Actual UI language switching and mirror actions/notices in all six languages
  (`ModelScopeUiInstrumentedTest`). This is not the full release UI/performance gate.

Test one model at a time on one dedicated headed AVD. Use a host deadline in
addition to instrumentation deadlines. Bind the exact serial, AVD name and boot
identity; never silently switch to an attached phone. Full physical-device,
minimum API, 16-KB pages, accelerator and sustained-performance coverage remain
separate from x86_64/API-35 CPU emulator observations.

MiniCPM5 can emit a valid `uname -a` tool call, but current request-owned PRoot
policy blocks guest execution because filesystem/package changes cannot be
committed atomically with Stop. Preserve that guard. Hermes now explains this
native denial directly instead of asking the model for an unreliable explanation.
Manual Alpine installation and guest execution remain separately testable.

## Public ModelScope mirrors

The initial mirror set contains **two files**, not every Tdamre variant:

- `Tdamre/MiniCPM5-1B-litert-lm` / `MiniCPM5-1B-web.litertlm`.
- `Tdamre/VibeThinker-3B-litert-lm` / `VibeThinker-3B.litertlm`.

`VerifiedLocalModelMirrors` binds original revision/file/hash to an exact public
ModelScope commit. The app sends no ModelScope token and does not contact Hugging
Face first on this route. Downloads still undergo the original artifact checks.
The endpoint's observed range behavior does not justify a resume promise; an
interrupted download may restart. Tests from outside mainland China establish
anonymous reachability and byte identity, **not guaranteed mainland availability**.

VibeThinker's card declares a Qwen2.5-Coder-3B base. Its MIT label alone does not
establish clearance from that base model's research licence. The mirror retains
the MIT notice, full Qwen Research License, attribution and conversion notice;
the app labels research/evaluation use unless separately licensed and links to
the mirror's licence information.

## Release boundary

The offline evidence validator retains 0.16.1 for v0.13.148–153 and selects
0.17.0 from v0.13.154 onward. Historical evidence is immutable. No laboratory
pass substitutes for source-bound signed candidate certification. After a future
GitHub app release, fresh F-Droid updater discovery **and** exact buildserver
reproducibility against the public APK are mandatory. Do not open an fdroiddata
merge request unless the user explicitly requests submission.
