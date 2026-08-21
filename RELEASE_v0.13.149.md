# Hermes Agent Fork v0.13.149

This corrective Android release preserves the v0.13.148 interface, local-model,
tool-routing, and Debian-sandbox overhaul while repairing the new F-Droid
source-binding gate. The published v0.13.148 tag and assets remain immutable;
v0.13.149 is the first candidate eligible for the completed pinned F-Droid
reproducibility certification.

## F-Droid source authority

- Models the pinned fdroidserver scanner's exact deterministic removal of
  `gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, and
  `gradle-daemon-jvm.properties` before Gradle configuration.
- Keeps the source handoff fail-closed: every other tracked edit or deletion,
  a stale or missing binding file, an unexpected metadata transformation, or a
  mismatched source digest still stops the build.
- Adds regression fixtures for the canonical nested Gradle wrapper JAR and
  daemon JVM properties, plus an explicit unauthorized-deletion rejection.
- Uses the same immutable buildserver image, fdroidserver revision,
  gradlew-fdroid revision, 12-worker limit, 6-GiB container cap, clean
  `env -i` build environment, and allowed Android signing key as v0.13.148.

## Android runtime carried forward

- Retains the complete responsive liquid-glass UI and six-language phone and
  tablet coverage, including themed startup, provider setup, and Tasker
  surfaces.
- Retains fail-closed local-model ownership, immutable model selection,
  LiteRT-LM 0.16.1, and the deliberately CPU/speculation-off x86_64 scope for
  the historical Gemma 4 E4B experiment.
- Retains provider-neutral built-in time and device-status routes and the
  packaged PRoot/QEMU Debian proof for `id`, `uname -a`, `curl --version`, and
  real HTTPS retrieval.
- Makes the MCP availability explanation version-neutral so future patch
  releases do not display a stale release number.

## Evidence boundaries

The original v0.13.148 GitHub release completed its hosted workflow but its
first pinned F-Droid build stopped safely before compilation when the new
source-binding gate detected fdroidserver's unmodelled wrapper-JAR cleanup. No
v0.13.148 F-Droid APK was produced, and that release is not used as proof for
this one.

v0.13.149 requires fresh source-bound APK/AAB artifacts and complete manifest-v3
headed phone/tablet evidence tied to its own source digest and APK hashes. The
release is certified only after the exact-tag GitHub workflows and the pinned
no-MR F-Droid autoupdater/buildserver comparison both pass. No physical Galaxy
S24, Snapdragon, Adreno, NPU, GPU, multimodal E4B, or F-Droid merge-request
claim is made.
