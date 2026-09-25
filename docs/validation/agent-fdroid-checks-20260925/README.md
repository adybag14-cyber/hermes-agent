# Agent — F-Droid updater and Docker build checks

## Result

The updated F-Droid checker passed the live public updater checks, the current Agent candidate build, and 111 targeted regression tests. An independent build of the **same candidate commit** in the original Docker environment produced the **same entire unsigned APK**, including identical SHA-256, size and all 4,877 archive entries.

**Tested source:** `c7bec5659b9b9f76ac523443331713f0d44a1a15`.
This record is attached by a later documentation-only commit; it does not pretend those later documentation bytes were part of the tested source.

Physical-device testing was explicitly skipped. No production Devbox service, release tag, signing credential, public F-Droid metadata or store listing was changed.

## Checker update and retained pins

The official 22 September 2026 F-Droid buildserver was pinned by digest:

```text
registry.gitlab.com/fdroid/fdroidserver@sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8
```

Its actual `/home/vagrant/buildserverid` and matching fdroidserver source are revision `8f52ae3ce287bc28964db544b970b88dce9c38bf`. The source archive is 8,341,140 bytes with SHA-256 `d69b5fae88d7e07e2d8a508637937a9ba49dc261c9fcc984f9236d981dbdedd9`. The image and source archive were independently checked before execution. The new environment uses JDK 21.0.12.1 and Python 3.13.5. The declared `gradlew-fdroid` pin, `c7227d147483979bb5c408048cee3533a8814fb0`, already matched the live upstream head and was retained.

The existing helper, source-binding verifier, genuine Python bundle and candidate Gradle caches were reused. The updated image ran side by side with the original `hermes-v157-sdk-candidate-20260909` environment; the previous image, historical containers and volumes were not replaced or pruned.

The application toolchain remains Gradle 9.3.1, AGP 9.1.1, Kotlin 2.4.10, Android API 36 / Build Tools 36.0.0, app NDK 29.0.14206865, native-Python NDK 27.3.13750724, CMake 3.31.6 and Ninja 1.12.1. LiteRT-LM remains 0.17.1. Newer Android compiler releases are not automatically suitable for the locked native/Python ABI and reproducibility contract; this checker refresh does not replace those already-validated application pins. The newer host environment was instead qualified against the old builder's output.

## Executed checks

| Check | Outcome |
| --- | --- |
| Fresh public fdroiddata clone and initial app lint | Passed |
| Real `fdroid checkupdates --auto --allow-dirty` | Passed; resolved public v0.13.157 to `0fa5b85c810e02bc640ad911053c2b8666a29d4f` |
| Source-binding recipe rendering and verification | Passed; public build history, Binaries URL and allowed signer preserved |
| Final metadata lint and whitespace check | Passed; no metadata commit or merge request |
| Canonical checker regressions inside upgraded Docker | 111 passed, zero failed: 7 toolchain, 79 source-binding, 25 Python-preparation tests |
| Existing `run-local-buildserver.sh` on the candidate | Passed: source scanning, strict source binding and actual release build; 63 Gradle tasks executed |
| APK source digest in DEX and genuine runtime package verification | Passed |
| APK identity | Agent; `com.mobilefork.hermesagent`; target API 36; arm64-v8a and x86_64 |
| Independent reference build in original Docker | Passed; 63 Gradle tasks executed |
| Entire old/new-builder unsigned APK comparison | **Byte-identical**; all 4,877 archive entries also match |

The scoped Python checks used the repository's canonical isolated-file runner, not a substitute or mock build. The actual F-Droid build retained the existing single exact scanner exception for the hash-verified local Python bootstrap Maven repository at `android/settings.gradle.kts`. No whole-directory exclusion, scan bypass, source-identity bypass, or signature exception was added. Existing compiler/deprecation and non-ELF script-stripping warnings remain recorded; this is not a claim that every repository warning was eliminated.

The explicit F-Droid preparation/binding and the ordinary clean-source release build produced the same source digest:

```text
be36acc13193f0938ff1dc9517340f53f8ef3809c78d9e84f2ae6e30d18dfb09
```

## Artifact identity

```text
Source commit: c7bec5659b9b9f76ac523443331713f0d44a1a15
APK bytes:     349478187
APK SHA-256:   aa6a38d891197b455e78108d077052a5c54081c125bdcc985d95e4bf3f48ad90
Variant:       Full release, unsigned candidate
Recipe version: 0.13.157 / 145790
```

The recipe version is inherited for a **private, non-publishable rehearsal**. This is not the public v0.13.157 source or APK. It must not replace the already-published version or be installed as a normal signed update. The same source commit was used on both sides of the candidate comparison; no release tag was created or moved.

## Evidence and scope

[Machine-readable summary](summary.json), [live updater receipt](updater-result.json), [candidate artifact receipt](candidate-result.json), [whole-APK comparison](comparison-result.json), [111-test transcript](checker-tests.log), [F-Droid build milestones](fdroid-build-excerpt.log), [reference-build milestones](reference-build-excerpt.log), [DEX source-binding receipt](apk-source-binding.log), and [genuine runtime verifier](apk-runtime-package.json) are attached. The [zero-context public metadata diff](public-updater.diff), [toolchain contract](toolchain-contract.txt), [checker packages](checker-packages.txt), and [raw-input hashes](raw-inputs.json) preserve the exact inputs. The published metadata diff uses zero context so it remains whitespace-clean; the original full-context diff hash is retained. Build milestone files are clearly labelled excerpts, not full transcripts; full logs are retained in the Devbox task artifacts.

The public updater still sees the last published release, v0.13.157. The new Agent changes are an unpublished PR, so its build used an isolated metadata fixture pinned to the exact candidate commit and intentionally lacking `Binaries` and signer claims. The real public metadata kept both fields unchanged. Verified dependency caches were reused for these checks. Therefore this result is **not** a fresh-empty-cache, post-publication comparison against a public signed Agent APK or a claim of F-Droid acceptance/publication.

## Retained diagnostics

The archive-form fdroidserver has no Git/package version metadata; its `--version` probe returned nonzero, while the image/source hash and exact buildserver ID provided the verified identity. An initial canonical-test run needed the checker-only Debian `python3-httpx` fixture dependency; the subsequent run passed all 111 tests. The image's existing `gradle.properties` was preserved rather than deleting its nonempty directory.

An additional post-build use of the *prebuild-only* source-cleanliness verifier correctly rejected generated outputs and bytecode created during compilation. That diagnostic is retained; the verifier was not weakened, and generated outputs were not deleted to force a pass. The required prebuild source scan/binding, built-APK DEX digest check and the independent whole-APK comparison all passed. This report does not mislabel that extra post-build cleanliness probe as a passing gate.
