# Agent model settings, import and branding validation

## Review scope and source

This candidate addresses Android model import issue #21 and presentation/branding issue #20 in `adybag14-cyber/hermes-agent`. The application is **Agent**. It is an independent fork, not an official or endorsed Nous Research product. PR #22 targets `codex/termux-five-goals`.

The tested runtime source is **21c9354eb701f175f7ff29f9d65f91b8b2ab6c7e**. Later commits in this PR add the final sanitized store screenshots, a packaging-assertion update, and this report; they do not change the APK runtime. This is a development candidate, not release certification. Machine-readable counts, source/capture hashes and artifact identity are in [the results JSON](agent-model-settings-20260925-results.json).

## Delivered behavior

**Choose a model first.** Settings > Models opens with local selection/import and installed models. The system document picker accepts providers that report an unknown MIME type, requests readable/openable documents, and allows GGUF, LiteRT-LM `.litertlm`, and Android `.task` files. Local import requires neither an account nor network access, including when offline mode is enabled. Importing does not force an engine selection; the model's Use & Start flow selects the runtime and retains independent format, compatibility and memory checks.

**Safer file handling.** Imports copy into a non-discoverable `.part` file, sync it and publish by same-directory rename. Slow provider reads occur outside the model-file publication lock. Duplicate naming and record publication use the narrow lock; persistence failures roll back the copied model before discovery can see a failed publication. Original provider files are never deleted. Cancellation before selection does nothing, and transient URI access is not unnecessarily persisted. This is not a claim of a single ACID transaction across file storage and preferences: abrupt process death can leave an ignored staging file.

**Simpler, accessible settings.** Online downloads/providers, response preferences, runtime, advanced GGUF controls and local sharing use labelled expandable sections. The fixed tab bar reserves space outside the scrolling viewport, so it cannot cover controls brought into view by keyboard/accessibility/test scrolling. Reselecting Models returns to its first action. Controls retain touch targets, selected-state semantics, expanded/collapsed descriptions, progress announcements and explicit removal confirmation. Settings propagates its selected language to nested controls immediately; English, Chinese, Spanish, German, Portuguese and French are verified.

**Independent Android presentation.** Installed labels, shell, boot screen, notifications, service/permission text, widget, navigation icon, floating control and current store listing use Agent and an independent A mark. Native chat and the embedded Android Python assistant use the Agent default identity, while explicit user personas remain unchanged. New Agent command phrases preserve the old aliases and existing authority/permission checks.

The installed application ID, Android component identities, package/class names, persisted keys, URI authorities, protocol headers and historical release records are deliberately not globally renamed. Copyright, MIT licensing and accurate upstream attribution remain. The hosted repository and upstream CLI technical identity were not renamed. See [BRANDING.md](../../BRANDING.md). The implementation does not establish that an external F-Droid/trademark complaint is closed.

**LiteRT-LM.** The Android dependency is pinned to **0.17.1**, verified as stable by the repository's live Google Maven version checker during this task. API compilation and packaging passed; neither compilation nor emulator UI tests certify inference for any particular model.

## Build provenance

The build used the retained F-Droid buildserver image recorded in the results JSON, JDK 21, Python 3.13, Android SDK 36 and verified native SDK inputs. The retained Python source bundle was verified against both current requirements/lock hashes and its file inventory. The complete debug APK includes arm64-v8a and x86_64 native libraries and was built with `skipHermesAndroidLinuxAssets=false`. No placeholder dependencies or native-asset bypass were introduced.

The unit-test harness fetched the genuine Robolectric API 31 artifact from Maven Central and checked its published SHA512 before sandbox execution. Python process-cleanup tests ran under `tini -s`, rather than an unreaping sleep process as container init. These environment repairs did not exclude failing tests. The Python runner's optional Git bytecode-precompile probe printed that the exported source directory was not a Git worktree; the archive/overlay source manifests provide provenance, and all 59 test-file subprocesses completed.

## Final validation

| Check | Result |
| --- | --- |
| Full debug APK and Android test APK | Built successfully with real native and Python assets |
| Play edition Kotlin compilation | Passed |
| JVM / Robolectric / Compose suite | **1,044 tests; 112 suites; 0 failures; 0 errors; 0 skipped** |
| Android Python suite | **59 files; 889 passed; 0 failed; 1 skipped** |
| Full lint | **0 errors, 0 fatal issues; 57 warnings** |
| Play lint | **0 errors, 0 fatal issues; 60 warnings** |
| Installed Android 36 x86_64 instrumentation | **3 tests passed**, explicit `OK (3 tests)` |
| Store images | Ten new 1080 x 2400 captures; metadata sanitizer checks passed |
| Whitespace validation | `git diff --check` passed |

The three device-side tests cover real offline DocumentsUI opening and cancellation; model-first/repeated-tab navigation with exact model heading/import-label assertions in all six languages; and advanced settings validation, cache/runtime choices, invalid argument handling and one-shot RAM-consent cancellation in all six languages. Tests use actual rendered controls, not replacement business logic. A regression additionally checks that the scrolled advanced choice is below the fixed navigation area and remains clickable.

The final corrected header was visually spot-checked for status-bar separation. All ten captures are real installed-APK screenshots with dimensions and SHA256 hashes recorded, but this report does **not** claim a manual visual audit of every screen or physical TalkBack certification.

## Startup qualification and remaining gates

An intermediate debug reinstall/instrumentation launch failed before any tests ran. Android recorded a startup ANR; its trace placed the main thread in ART DEX verification before application code, while the emulator reported heavy runnable load. The retained evidence is not erased or reported as a pass. Final instrumentation succeeded after normal ART preparation of both packages with `cmd package compile -m speed -f`; no verifier bypass was used. This does not prove the ANR was solely caused by host load or establish a cold-start fix.

Physical checks remain with the owner: fresh launch after installation/reboot without special ART preparation; local picker behavior and a real GGUF inference session; LiteRT-LM model loading; interrupted imports, storage limits and duplicate filenames; screen-reader/large-text navigation; thermal/memory behavior; and production-signed upgrade/data retention. No physical device, ARM64 inference or GPU performance qualification was performed.

## APK handoff

The verified candidate is saved in the Devbox user's Downloads folder as:

`Agent-21c9354eb7-debug.apk`

- Application label: **Agent**; application ID: `com.mobilefork.hermesagent`.
- Version name **0.13.146**, version code **144690**, minimum API **24**, target API **36**.
- Size **478,373,933 bytes**; native ABIs **arm64-v8a** and **x86_64**.
- SHA256: `1fd2e3834cbca147bfa820cd3d3ec315d1294556d4066cc9356c2b48b48a4e33`.

This is a **debug candidate**, not a production-signed update. Existing release installations may reject it because of signing or version compatibility. Do not uninstall a working installation merely to bypass that protection; use a spare test device or a properly signed upgrade candidate. Creating another Android user/profile does not bypass package signature constraints.

No release tag, store publication, merge, issue closure or production-service deployment was performed. GitHub CI must be read at the final PR revision independently of the local validation above.
