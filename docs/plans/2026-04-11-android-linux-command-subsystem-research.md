# Android Linux Command Subsystem Research

> For Hermes: use subagent-driven-development skill to implement the follow-on plan task-by-task.

Goal: determine the correct architecture for giving the Hermes Android app a real local Linux command subsystem with terminal/process parity, instead of the narrowed mobile tool profile used today.

Question studied:
- What is the safest way to make Hermes inside the APK execute real local CLI commands in an Android-native environment with behavior as close to Termux as possible?

Tech stack examined:
- Embedded Hermes API server (`hermes_android/*`)
- Existing terminal backends (`tools/terminal_tool.py`, `tools/environments/*`)
- Android app runtime/build (`android/app/*`)
- Termux package metadata and relocated binary behavior
- `proot` + generic Linux rootfs feasibility on this device

---

## Findings

### 1. The Android app already runs a fully local embedded Hermes server
Evidence:
- `hermes_android/server.py`
- `hermes_android/bootstrap.py`
- `android/app/src/main/java/com/nousresearch/hermesagent/backend/HermesRuntimeManager.kt`

Meaning:
- Hermes does not need any remote-host workaround to get local command execution.
- The missing piece is an Android-safe command backend and command-suite bootstrap.

### 2. The current Android tool profile excludes terminal/process by policy, not because Hermes lacks them
Evidence:
- `toolsets.py` (`hermes-android-app`)
- `hermes_android/mobile_defaults.py`

Meaning:
- Once an Android-safe backend exists, Hermes can expose `terminal` and `process` through the existing API server architecture.

### 3. The current `local` backend is not Android-app safe as-is
Evidence:
- `tools/environments/local.py`
- `tools/terminal_tool.py`

Specific problems:
- shell discovery is desktop/server oriented
- PATH defaults omit Android command locations
- snapshot/bootstrap logic assumes bash-flavored shells and desktop filesystem norms

Meaning:
- Simply enabling `terminal` in `hermes-android-app` would be unreliable.
- Hermes needs a dedicated Android backend or a dedicated Android shell suite setup.

### 4. A `proot` + generic Linux rootfs approach looked attractive on paper, but failed as the primary recommendation
Research result:
- Termux `proot` packages are small and available.
- Alpine minirootfs tarballs are also small.
- However, live testing on this device produced `execve(...): Function not implemented` / loader failures.

Meaning:
- `proot` remains an optional future path, but it is not the safest foundation for the next alpha release.
- Shipping a subsystem that fails on the current Android test device would violate the user’s “no workaround, no fake support” requirement.

### 5. A relocated Termux-style command prefix is the most practical and honest architecture
Research result:
- Termux package binaries are Android-native and can run outside the canonical Termux prefix when provided the right `PATH` + `LD_LIBRARY_PATH`.
- Live experiments showed relocated `bash`, `grep`, `curl`, and `git` commands running successfully from a copied prefix.

Meaning:
- Hermes can ship a real local Android command suite by extracting a curated Termux-style prefix into app-private storage.
- This is much closer to “same as Termux offering” than an Android toybox shell or a brittle rootfs emulator.

### 6. The best implementation path is build-time asset generation + runtime extraction
Recommended pipeline:
1. Build step downloads a curated Termux package set for each supported ABI.
2. Build step resolves dependency closure and normalizes the extracted prefix into Android assets.
3. App runtime extracts that prefix into app-private storage on first boot.
4. Hermes sets `TERMINAL_ENV=android_linux` and runs `terminal`/`process` against the extracted bash + libraries.

Why this wins:
- all binaries are Android-native
- no remote service dependency
- no fake command stubs
- the shipped command suite can be curated for real CLI usefulness
- the architecture fits the current Hermes tool/backend abstraction cleanly

### 7. Device UI is the right place to expose subsystem state and guidance
Evidence:
- `android/app/src/main/java/com/nousresearch/hermesagent/ui/device/DeviceScreen.kt`
- `android/app/src/main/java/com/nousresearch/hermesagent/device/DeviceStateWriter.kt`

Meaning:
- Users should be able to see:
  - whether the Linux suite is provisioned
  - its ABI / prefix path / shell path
  - that Hermes can now use `terminal`/`process`
- This should live alongside shared-folder and accessibility capabilities.

---

## Decision

Recommended architecture for the next Android alpha release:

- Ship an app-private Termux-style Linux command suite, not a toy Android shell and not a `proot`-first rootfs.
- Add a dedicated `android_linux` terminal backend.
- Provision the suite during Android bootstrap.
- Enable `terminal` and `process` in `hermes-android-app`.
- Surface subsystem state in Device UI and `android_device_status`.

This is the smallest path that is still honest about providing real local Linux CLI execution inside the app.

---

## Risks

1. Some Termux packages may still contain prefix-sensitive scripts or symlink-heavy layouts.
   - Mitigation: normalize assets at build time, recreate links at runtime, and keep the initial package set curated.

2. Asset size can balloon if symlinks/hardlinks are naively materialized.
   - Mitigation: preserve link intent in a manifest and recreate links at runtime.

3. Exact full Termux parity is broader than one alpha release.
   - Mitigation: ship a strong real command suite first, then expand package coverage iteratively.

---

## Recommendation summary

Do not ship Android terminal support by just exposing the current `local` backend.

Do ship:
- curated relocated Termux command-suite assets
- `android_linux` backend
- runtime extraction + env setup
- Device UI status/guidance
- `terminal` + `process` in the Android toolset

That gives Hermes a real local command subsystem in the APK runtime, using the same class of Android-native binaries users already trust in Termux.
