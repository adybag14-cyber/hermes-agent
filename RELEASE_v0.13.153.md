# Hermes Agent Fork v0.13.153

This corrective Android release supersedes v0.13.152 for F-Droid installation
and reproducibility. It retains the certified Nanbeige/TurboQuant runtime,
request-owned cancellation, local generation progress, terminal Stop behavior,
and advanced llama.cpp controls while resolving both independent causes
reported in GitHub Issue #17.

## Reproducible experimental llama.cpp binaries

- Normalizes the pinned TurboQuant llama-server with the locked Android NDK
  `llvm-strip` after linking.
- Removes `.note.gnu.build-id`, whose 20-byte descriptor was the only byte
  difference between the v0.13.150 GitHub and F-Droid native libraries.
- Removes the non-loadable `.comment` compiler-identification section as a
  cross-host hardening measure.
- Uses the locked NDK `llvm-readelf` to fail before packaging if either section
  survives.
- Selects a real host C++ compiler outside the Android NDK for llama.cpp's
  build-time host tools, while retaining the locked NDK compiler for Android
  targets.
- Copies verified JNI and license/manifest outputs into private transaction
  roots on their destination filesystem before coordinated atomic publication.
  This closes the F-Droid `/tmp`-to-Gradle-volume `EXDEV` failure while keeping
  both generated trees coherent under failure or concurrent invocation.
- Records the removed-section contract in the packaged experimental-lane
  manifest before hashing the normalized arm64-v8a and x86_64 libraries.

The normalization does not change executable code, dynamic dependencies,
relocations, load alignment, or runtime capabilities. Both reported native
library pairs were otherwise byte-identical.

## Self-binding F-Droid bot builds

- Retains the explicit `hermesFdroidSourceBinding=true` prepare/verify handoff
  used by the repository's no-MR release preview.
- Adds a fail-closed fallback for the central F-Droid bot's inherited two-`sed`
  source transformation. Normal and F-Droid marker states are closed: an exact
  F-Droid state has all three SDK locators and none of the three tracked scanner-managed
  wrapper files; every partial or contradictory state fails. Any semantic
  release tag without an explicit digest invokes binding verification rather
  than falling through to an unbound build.
- Derives the source identity from committed Git blobs rather than transformed
  working-tree bytes, while requiring `HEAD` to equal the peeled annotated tag
  on the canonical GitHub origin. Git authority environment variables,
  non-default index flags, and ignored/untracked build inputs are rejected.
  Raw bytes and symlink targets for every unchanged tracked source entry are
  compared directly with committed blobs, bypassing configurable Git clean
  filters. Any additional edit, deletion, malformed SDK locator, version
  mismatch, or source-identity mismatch fails the build.

This prevents `hermes-source-unbound` from entering `classes2.dex` when the
central bot has not yet inherited the repository metadata overlay. Correcting
that DEX identity also removes the derivative baseline-profile difference.
The native NDK/CMake declarations remain an F-Droid metadata requirement; the
repository's no-MR preview overlays and verifies them, while central metadata
must carry the same toolchain contract before its build can succeed.
Automatic transformed binding also performs a live read-only lookup of the
canonical annotated tag; network or GitHub unavailability fails closed.

## Immutable release boundary

The v0.13.150, v0.13.151, and v0.13.152 tags and assets remain unchanged.
v0.13.151 legitimately has no APK because its release workflow stopped before
signing and publication; v0.13.152 remains the published cancellation repair
but predates this native normalization.

The v0.13.153 release must be built from a new source digest and candidate,
pass headed phone/tablet and physical Nanbeige certification, and match a
pinned F-Droid build after signing-block normalization. A fresh F-Droid updater
check must select exactly `0.13.153`/`145390`. No fdroiddata commit, push, fork,
or merge request is created by this release workflow.
