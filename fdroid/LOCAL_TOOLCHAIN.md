# Local F-Droid toolchain

Hermes uses two local environments because fdroiddata contains real symbolic links that a normal Windows checkout materializes incorrectly.

## Metadata checks in WSL2

Keep a Linux checkout at `~/fdroiddata-hermes` and install the same fdroidserver revision used by the F-Droid CI job into `~/.venvs/fdroidserver`:

```sh
git clone --depth=1 --branch master https://gitlab.com/fdroid/fdroiddata.git ~/fdroiddata-hermes
python3 -m venv ~/.venvs/fdroidserver
~/.venvs/fdroidserver/bin/pip install \
  'git+https://gitlab.com/fdroid/fdroidserver.git@00932d0a715b43b3ecf8da44826abf2ba65dd8b4'
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

The July 2026 F-Droid job used this immutable image:

```text
registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:dc522fdce601ec80fb1ed420dd0301262a0c7747e8a769c7975629944e8b46c4
```

The helper downloads the `fdroidserver` source archive by exact commit
`00932d0a715b43b3ecf8da44826abf2ba65dd8b4`, checks out and verifies
`gradlew-fdroid` at `c7227d147483979bb5c408048cee3533a8814fb0`, and never pulls a floating
helper branch or refreshes moving scanner signatures during certification.

Mount a fdroiddata checkout containing the candidate metadata at `/workspace`, mount this script as `/run-hermes-fdroid.sh`, and retain the Gradle/build caches in named volumes:

```powershell
docker volume create hermes-fdroid-gradle
docker volume create hermes-fdroid-build
docker run --name hermes-fdroid-build --memory 6g --cpus 16 `
  --mount "type=bind,source=$FdroidDataRoot,target=/workspace,readonly" `
  --mount "type=bind,source=$HermesRoot\fdroid\run-local-buildserver.sh,target=/run-hermes-fdroid.sh,readonly" `
  --mount "type=volume,source=hermes-fdroid-gradle,target=/home/vagrant/.gradle" `
  --mount "type=volume,source=hermes-fdroid-build,target=/home/vagrant/build" `
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:dc522fdce601ec80fb1ed420dd0301262a0c7747e8a769c7975629944e8b46c4 `
  bash /run-hermes-fdroid.sh
```

Set `VERSION_CODE`, `APP_ID`, or `FDROIDSERVER_COMMIT` on the container when reproducing a different recipe. Inspect a failed named container before removing it so OOM termination is distinguishable from an application build error.
