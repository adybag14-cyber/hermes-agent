# Local F-Droid toolchain

Hermes uses two local environments because fdroiddata contains real symbolic links that a normal Windows checkout materializes incorrectly.

## Metadata checks in WSL2

Keep a Linux checkout at `~/fdroiddata-hermes` and install the same pinned
fdroidserver revision used by the exact local build into
`~/.venvs/fdroidserver`:

```sh
git clone --depth=1 --branch master https://gitlab.com/fdroid/fdroiddata.git ~/fdroiddata-hermes
python3 -m venv ~/.venvs/fdroidserver
~/.venvs/fdroidserver/bin/pip install \
  'git+https://gitlab.com/fdroid/fdroidserver.git@4a8821a58659901c63315cb000b0e98525653bc5'
cd ~/fdroiddata-hermes
~/.venvs/fdroidserver/bin/fdroid lint com.mobilefork.hermesagent
~/.venvs/fdroidserver/bin/fdroid checkupdates --auto --allow-dirty com.mobilefork.hermesagent
```

Run that preview from a fresh clone of the live `fdroiddata` metadata after the
GitHub tag exists. `--auto` must create the local 0.13.153/145390 build recipe
and resolve its exact tag commit. The autoupdater copies the prior build recipe,
so its output is not yet eligible for the pinned build. From the same WSL shell,
render and verify the v0.13.153 source-binding fields from the committed Hermes
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

The render transaction requires exactly one 0.13.153/145390 build, preserves
the autoupdater-resolved full Git commit, every historical `Builds` entry, and
all unrelated live metadata, and overlays the exact `sudo`, `ndk`, `gradle`,
`gradleprops`, and `prebuild` fields. It then verifies that
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

Use this reachable immutable buildserver image:

```text
registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9bae53bb4ddbf8fa5bb7385bf2e62e7c6318f99ab0d25b2a551ad38abb528068
```

The image's OCI revision label and `/home/vagrant/buildserverid` identify
`4a8821a58659901c63315cb000b0e98525653bc5`. Before any download, the helper
requires that exact buildserver ID and then downloads the matching
`fdroidserver` source archive from this exact URL:

```text
https://gitlab.com/fdroid/fdroidserver/-/archive/4a8821a58659901c63315cb000b0e98525653bc5/fdroidserver-4a8821a58659901c63315cb000b0e98525653bc5.tar.gz
```

The stored archive is exactly 8,336,107 bytes with SHA-256
`8b2f87ef6e278a49f70b98fe0ff007465524f41c15ba212f686f4239a7323909`.
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

Mount the locally rendered fdroiddata checkout at `/workspace`, the repository
`fdroid` directory and source-binding helper read-only, and retain the
Gradle/build caches in named volumes. The Gradle volume also retains the
hash-verified TurboQuant source archive at
`/home/vagrant/.gradle/caches/hermes-experimental-llama/source`; generated
source-tree paths under `android/app/build` remain disposable. The helper
verifies the rendered metadata again before the container downloads an SDK,
fdroidserver, source, or dependency:

```powershell
docker volume create hermes-fdroid-gradle
docker volume create hermes-fdroid-build
docker run --name hermes-fdroid-build --memory 6g --cpus 12 `
  --mount "type=bind,source=$FdroidDataRoot,target=/workspace,readonly" `
  --mount "type=bind,source=$HermesRoot\fdroid,target=/hermes-fdroid,readonly" `
  --mount "type=bind,source=$HermesRoot\scripts\android_fdroid_source_binding.py,target=/hermes-android-fdroid-source-binding.py,readonly" `
  --mount "type=volume,source=hermes-fdroid-gradle,target=/home/vagrant/.gradle" `
  --mount "type=volume,source=hermes-fdroid-build,target=/home/vagrant/build" `
  --env HERMES_FDROID_TEMPLATE=/hermes-fdroid/com.mobilefork.hermesagent.yml.template `
  --env HERMES_SOURCE_BINDING_HELPER=/hermes-android-fdroid-source-binding.py `
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9bae53bb4ddbf8fa5bb7385bf2e62e7c6318f99ab0d25b2a551ad38abb528068 `
  bash /hermes-fdroid/run-local-buildserver.sh
```

Set `VERSION_NAME`, `VERSION_CODE`, `APP_ID`, and a matching committed
`HERMES_FDROID_TEMPLATE` together when reproducing a different recipe; a
partial override fails the metadata preflight. A toolchain change requires a
tracked update of the image digest, image/runtime revision, and helper pin; do
not substitute a newer `FDROIDSERVER_COMMIT` at runtime. Inspect a failed named
container before removing it so OOM termination is distinguishable from an
application build error.
