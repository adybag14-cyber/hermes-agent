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
~/.venvs/fdroidserver/bin/fdroid checkupdates --allow-dirty com.mobilefork.hermesagent
```

Do not use a Windows fdroiddata checkout for lint: text files in `srclibs/` which should be symlinks are otherwise parsed as invalid YAML.

## Exact build-server reproduction in Docker Desktop

The July 2026 F-Droid job used this immutable image:

```text
registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:dc522fdce601ec80fb1ed420dd0301262a0c7747e8a769c7975629944e8b46c4
```

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
