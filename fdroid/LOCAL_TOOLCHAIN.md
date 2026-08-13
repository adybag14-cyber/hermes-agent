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
GitHub tag exists. `--auto` must create the local 0.13.147/144790 build recipe
and resolve its exact tag commit. Do not add `--commit`, `--merge-request`, or
push the preview: this release intentionally verifies the autoupdater without
opening a GitLab merge request.

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

Both vagrant-user transitions use `sudo -u vagrant env -i`. The helper supplies
only its explicit PATH, Python, home, Gradle, locale, Android SDK, and optional
Java/SDK variables; inherited credentials, tokens, proxy settings, and other
host environment values do not cross that clean-environment boundary.

You can inspect the complete side-effect-free contract before starting Docker:

```sh
bash fdroid/run-local-buildserver.sh --print-contract
```

Mount a fdroiddata checkout containing the candidate metadata at `/workspace`, mount this script as `/run-hermes-fdroid.sh`, and retain the Gradle/build caches in named volumes:

```powershell
docker volume create hermes-fdroid-gradle
docker volume create hermes-fdroid-build
docker run --name hermes-fdroid-build --memory 6g --cpus 12 `
  --mount "type=bind,source=$FdroidDataRoot,target=/workspace,readonly" `
  --mount "type=bind,source=$HermesRoot\fdroid\run-local-buildserver.sh,target=/run-hermes-fdroid.sh,readonly" `
  --mount "type=volume,source=hermes-fdroid-gradle,target=/home/vagrant/.gradle" `
  --mount "type=volume,source=hermes-fdroid-build,target=/home/vagrant/build" `
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9bae53bb4ddbf8fa5bb7385bf2e62e7c6318f99ab0d25b2a551ad38abb528068 `
  bash /run-hermes-fdroid.sh
```

Set `VERSION_CODE` or `APP_ID` on the container when reproducing a different
recipe. A toolchain change requires a tracked update of the image digest,
image/runtime revision, and helper pin; do not substitute a newer
`FDROIDSERVER_COMMIT` at runtime. Inspect a failed named container before
removing it so OOM termination is distinguishable from an application build
error.
