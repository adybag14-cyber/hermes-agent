# Local F-Droid toolchain

Agent reuses the existing Linux updater checkout and Docker buildserver. The
owner's standing instruction (2026-09-27) is to update these environments in
place, not allocate a new container, image, volume, checkout or virtualenv for
each release. Native Linux is still required for fdroiddata's real symlinks;
a normal Windows checkout can materialize them incorrectly.

For v0.13.154 onward, both the generated metadata recipe and local helper install
`platforms;android-37` and `build-tools;36.0.0`; the application compiles against API 37 and targets API 36.
The older Build Tools 31 `aapt` retained by the helper belongs to fdroidserver's
inspection tooling, not the app compile/target SDK. Verify targetSdk in the
public APK as well as in source configuration.

## Metadata checks in WSL2

Use the already-provisioned updater container and its existing fdroiddata
checkout by default. An existing WSL checkout/venv may also be reused. Resolve
its actual path and the pinned fdroidserver identity first; do not recreate a
virtualenv or source clone merely to run the next check. Archive any previous
local metadata diff before restoring only those preview changes, then update
the existing checkout to the current public branch:

```sh
# Existing paths only; stop rather than silently provisioning replacements.
: "${FDROIDDATA_ROOT:?Set the retained Linux metadata checkout}"
: "${FDROID:?Set the retained, version-verified fdroid entrypoint}"
cd "$FDROIDDATA_ROOT"
test -z "$(git status --porcelain)"
git fetch origin master
git merge --ff-only FETCH_HEAD
"$FDROID" lint com.mobilefork.hermesagent
"$FDROID" checkupdates --auto --allow-dirty com.mobilefork.hermesagent
```

Run that preview against freshly fetched live `fdroiddata` metadata in the
retained checkout after the GitHub tag exists. `--auto` must create the local build recipe for the version/code in `com.mobilefork.hermesagent.version`
and resolve its exact tag commit. The autoupdater copies the prior build recipe,
so its output is not yet eligible for the pinned build. From the same WSL shell,
render and verify the current source-binding fields from the committed Agent app
template into that generated build:

```sh
HERMES_ROOT=/mnt/c/Users/adyba/hermes-agent-android-overhaul
FDROIDDATA_ROOT=$HOME/fdroiddata-hermes
bash "$HERMES_ROOT/fdroid/run-local-buildserver.sh" \
  --render-autoupdate-preview \
  "$FDROIDDATA_ROOT/metadata/com.mobilefork.hermesagent.yml" \
  "$HERMES_ROOT/fdroid/com.mobilefork.hermesagent.yml.template"
bash "$HERMES_ROOT/fdroid/run-local-buildserver.sh" \
  --verify-autoupdate-preview \
  "$FDROIDDATA_ROOT/metadata/com.mobilefork.hermesagent.yml" \
  "$HERMES_ROOT/fdroid/com.mobilefork.hermesagent.yml.template"
git -C "$FDROIDDATA_ROOT" diff -- \
  metadata/com.mobilefork.hermesagent.yml
```

The render transaction requires exactly one current-version build, preserves
the autoupdater-resolved full Git commit, every historical `Builds` entry, and
all unrelated live metadata, and overlays the exact `sudo`, `ndk`, `gradle`,
`gradleprops`, `scanignore`, and `prebuild` fields. It then verifies that
`hermesFdroidSourceBinding=true` and the leading
`android_fdroid_source_binding.py prepare` handoff match the committed template
exactly. A missing/duplicate target, unresolved tag, old two-`sed` recipe,
changed template, or any path which could emit `unbound` fails closed.

Review that local diff before Docker. Do not copy the whole template over live
metadata: it intentionally contains only a candidate build and would erase
history. Do not add `--commit` or `--merge-request`, and do not commit or push
the preview. This release intentionally verifies the autoupdater and pinned
build without opening a GitLab merge request or changing live metadata.

Do not use a Windows fdroiddata checkout for lint: text files in `srclibs/` which should be symlinks are otherwise parsed as invalid YAML.

## Exact build-server reproduction in Docker Desktop

The v0.13.154 recipe additionally builds the genuine Python SDK dependencies
from the immutable Chaquopy source archive in `hermes_android/python_runtime.lock.json`.
It declares `python3-venv`, `rustup`, and native-Python NDK 27.3.13750724 alongside
the existing application NDK 29.0.14206865. The Python source builder runs after
the clean source-binding handoff and before the declared Gradle transformations,
with all generated files in the external Gradle cache. Both GitHub and F-Droid
use the same builder and hash-locked trusted wheels; see `android/PYTHON_RUNTIME.md`.

The v0.13.157 recipe also declares `make` and `pkg-config` for the source-built
MCP dependencies (OpenSSL, libffi, cffi, cryptography and rpds-py). The exact
metadata verifier includes those dependencies; no extra broad scanner exception
is added. Runtime validation remains separate from the source/ELF closure audit.

The v0.13.156 recipe records one exact scanner exception. The computed Maven
path in `android/settings.gradle.kts` is the local source-built Python bootstrap
repository, restricted by `exclusiveContent` to `com.chaquo.python.runtime:bootstrap`.
Its source lock, requirements hash, complete file inventory, sizes, and hashes are
verified before Gradle consumes it. The pinned scanner mistakes `lab.resolve(`
for an unknown remote URL. The separate Windows Tauri installer now tracks its
generated `apps/bootstrap-installer/src-tauri/Cargo.lock`, so its manifest no
longer needs the v0.13.155 scanner exception. Both installer files and the
Android settings remain source-integrity checked;
the complete scanner still runs, and the metadata verifier rejects omitted,
additional, or broadened scanner exceptions. No whole-directory scan exclusion
or `--skip-scan` is used.

Use this reachable immutable buildserver image:

```text
registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8
```

The image's OCI revision label and `/home/vagrant/buildserverid` identify
`8f52ae3ce287bc28964db544b970b88dce9c38bf`. Before any download, the helper
requires that exact buildserver ID and then downloads the matching
`fdroidserver` source archive from this exact URL:

```text
https://gitlab.com/fdroid/fdroidserver/-/archive/8f52ae3ce287bc28964db544b970b88dce9c38bf/fdroidserver-8f52ae3ce287bc28964db544b970b88dce9c38bf.tar.gz
```

The stored archive is exactly 8,341,140 bytes with SHA-256
`d69b5fae88d7e07e2d8a508637937a9ba49dc261c9fcc984f9236d981dbdedd9`.
The helper downloads it to a bounded regular temporary file, verifies both
stored-byte size and SHA-256 before extraction, and removes the file on success
or failure. It checks out and verifies `gradlew-fdroid` at
`c7227d147483979bb5c408048cee3533a8814fb0`, and never pulls a floating helper
branch or refreshes moving scanner signatures during certification.

The helper also fixes the local Gradle policy at 12 workers with parallel
project execution (`-Dorg.gradle.workers.max=12 -Dorg.gradle.parallel=true`)
and carries those settings through the `sudo` boundary into `fdroid build`.
The Docker CPU allocation and the Gradle worker budget therefore agree instead
of merely giving an otherwise serial build more idle CPUs.

The experimental native lane is separately locked to Android SDK package
`ndk;29.0.14206865` and package `cmake;3.31.6`, whose bundled executables must
report exactly CMake 3.31.6 and Ninja 1.12.1. The metadata declares the NDK and
installs the CMake package; the local helper installs both packages and verifies
their package paths and versions before fdroidserver setup. Gradle also declares
NDK 29.0.14206865 and the native preparation script refuses an ambient or
mismatched CMake/Ninja pair before it downloads the pinned source archive.

Both vagrant-user transitions use `sudo -u vagrant env -i`. The helper supplies
only its explicit PATH, Python, home, Gradle, locale, Android SDK, and optional
Java/SDK variables; inherited credentials, tokens, proxy settings, and other
host environment values do not cross that clean-environment boundary.

The current metadata also source-binds the F-Droid APK to the same committed
digest as the GitHub release. Its first `prebuild` command runs
`scripts/android_fdroid_source_binding.py prepare` before either metadata edit.
That phase first reproduces and validates the pinned fdroidserver's signing-key
scrub plus its three generated SDK `local.properties` files, then stores the
immutable `HEAD` commit/digest handoff under `GRADLE_USER_HOME`, outside the
source tarball. The two historical `sed` transformations then set the release
tag and Python 3.13 selection. Before system Gradle starts, the pinned
fdroidserver scanner unconditionally removes tracked files with the exact
basenames `gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, and
`gradle-daemon-jvm.properties`; this includes the canonical checked-in
`android/gradle/wrapper/gradle-wrapper.jar`. Gradle's
`hermesFdroidSourceBinding=true` path runs the script's `verify` phase, which
accepts only those deterministic scanner deletions plus the declared source
transformations and places the prepared digest in the release `BuildConfig`.
Any other tracked or untracked source change fails. The normal
`HERMES_SOURCE_DIGEST` path still requires a fully clean checkout and cannot be
combined with the F-Droid authority.

This handoff removes the prior `unbound` DEX difference; it does not by itself
certify reproducibility. Certification still requires the pinned buildserver's
`Binaries:` comparison to report that its unsigned APK matches the published
GitHub universal APK after the standard signing-block normalization.

Gradle also recognizes the central bot's inherited two-`sed` source
transformation without weakening that boundary. Marker states are closed: an
ordinary checkout has no root/app SDK locators and retains every tracked
wrapper, while the exact F-Droid state has all three identical SDK locators and
none of the three tracked scanner-managed wrappers. Every partial or contradictory
combination fails. Any semantic release tag without an explicit digest invokes
binding verification instead of emitting an unbound identity; an invalid tag,
competing digest, or explicit binding disable fails.

`android_fdroid_source_binding.py verify-transformed` accepts only the complete
known signing scrub, SDK locators, two metadata edits, and scanner deletions. It
sanitizes Git authority, rejects non-default index flags and all hidden
untracked inputs, and compares each unchanged tracked file or symlink directly
with its committed blob so clean filters cannot conceal different build bytes.
For explicitly committed `text eol=crlf` rules, the byte verifier derives the
required checkout bytes from the LF-normalized blob. Git interprets only the
committed `.gitattributes` files in an isolated temporary index: current worktree
attributes, `.git/info/attributes`, system/global attributes, and arbitrary
clean/smudge filters cannot supply that authority. CRLF rules combined with
filters or working-tree encodings are rejected. All other source files still
require raw blob bytes, and every regular file retains its executable-mode
check. The verifier never rewrites source resources, so the two bundled
PowerShell resources retain the same CRLF bytes in GitHub and F-Droid builds.
Do not add the old candidate-only LF-restoration `init` workaround.

It then requires `HEAD` to equal the peeled annotated tag on the canonical
GitHub origin. That live read-only tag lookup is intentionally fail-closed when
GitHub or the network is unavailable.

This source fallback does not provision Android SDK packages. Central metadata
must still declare NDK 29.0.14206865 and install CMake 3.31.6, exactly as the
committed no-MR preview overlay does. Update detection alone is not proof that
an unoverlaid inherited central recipe is buildable.

The experimental native lane removes `.note.gnu.build-id` and `.comment` with
the locked NDK `llvm-strip` after linking, then requires the locked
`llvm-readelf` to prove both non-loadable host-metadata sections are absent.
This prevents pre-strip host details from surviving as a different GNU build-ID
in otherwise byte-identical F-Droid and GitHub libraries.

You can inspect the complete side-effect-free contract before starting Docker:

```sh
bash fdroid/run-local-buildserver.sh --print-contract
```

Reuse the registered builder and its existing build/Gradle volumes. Before
changing its source or recipe, retain the previous gate receipt, metadata diff,
relevant logs and output hashes. Confirm no earlier task still owns a running
build. Restore only known generated source transformations, not unrelated user
changes, and update the same checkout to the exact candidate or tag commit.
The source-binding verifier and full scanner remain enabled.

```powershell
# Resolve/verify these existing identities; never create or auto-replace them.
$Builder = 'agent-v158-public-repro-cache-21cd3019'
docker inspect $Builder --format '{{.Id}} {{.Config.Image}} {{json .Mounts}}'
# After confirming this retained builder is idle and the host has capacity:
docker update --cpus 12 $Builder
docker start $Builder
# Copy reviewed current metadata/helper inputs into the existing /inputs paths,
# retaining the old gate receipts. Reverify their hashes before this invocation.
docker exec --env HERMES_FDROID_TEMPLATE=/inputs/metadata.template `
  --env HERMES_SOURCE_BINDING_HELPER=/inputs/android_fdroid_source_binding.py `
  $Builder bash /inputs/run-local-buildserver.sh
```

The existing updater is `agent-v158-public-updater-4b924f1c`. Names are discovery
hints, not ownership proofs: verify full container IDs, mounts and actual runtime
versions before use. Keep one active build owner per reused checkout/cache.
New result/log directories are permitted; new execution environments are not.

Reusable caches are not evidence of a cold-cache build. Record cache reuse and
tool versions honestly on every run. Do not relabel a warm replay as a new empty-
volume reproducibility test. Public-release checks still require the actual
released source, exact generated recipe, allowed signature, embedded source
digest and successful comparison to the public signed APK. Development candidate
checks are labelled separately and must not overwrite the public version.

Update installed tooling in place only when the reviewed source contract calls
for it, and record the actual tool identities rather than assuming the container
image label describes later package updates. Keep the pinned-image check strict;
if a mandatory new image cannot be represented in an existing container, report
that incompatibility for an explicit decision instead of creating a replacement
or claiming the old image is the new one.

Set `VERSION_NAME`, `VERSION_CODE`, `APP_ID`, and a matching committed
`HERMES_FDROID_TEMPLATE` together when reproducing a different recipe; a
partial override fails the metadata preflight. A toolchain change requires a
tracked update of the image digest, image/runtime revision, and helper pin; do
not substitute a newer `FDROIDSERVER_COMMIT` at runtime. Inspect a failed named
container before removing it so OOM termination is distinguishable from an
application build error.

## Candidate checker update (2026-09-25)

The official 2026-09-22 buildserver and its matching fdroidserver source are
updated together to revision `8f52ae3ce287bc28964db544b970b88dce9c38bf`.
The source archive is independently size/hash locked above. `gradlew-fdroid`
remains at its current upstream head `c7227d147483979bb5c408048cee3533a8814fb0`.
Keep the older builder for already-published artifact comparisons; do not
reinterpret a historical release's evidence with these newer tools.

This checker refresh does not upgrade the app's Gradle/AGP/NDK/CMake/Python
payload pins. Those pins form the already-validated Android source and ABI
closure; a newer host checker is not a reason to replace them mid-comparison.
The updater and candidate builds use the existing Docker infrastructure.

An unpublished PR may be rehearsed with a separate, explicitly non-publishable
metadata fixture pinned to its full commit and without `Binaries` or signing-key
claims. That is a candidate build check, never updater discovery of the PR or
reproduction of a public release. Preserve the real public updater output and
its history separately. Terminal post-publication gates still require the
public tag, allowed signer, untouched generated recipe and public APK match.
