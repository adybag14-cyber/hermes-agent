#!/usr/bin/env bash
set -euxo pipefail

# Run inside the official F-Droid buildserver-trixie container with this
# repository (or a fdroiddata checkout containing its metadata) at /workspace.
APP_ID="${APP_ID:-com.mobilefork.hermesagent}"
VERSION_CODE="${VERSION_CODE:-144690}"
FDROIDSERVER_COMMIT="${FDROIDSERVER_COMMIT:-00932d0a715b43b3ecf8da44826abf2ba65dd8b4}"
FDROIDSERVER_DIR=/home/vagrant/fdroidserver

source /etc/profile.d/bsenv.sh
sdkmanager "platform-tools" "build-tools;31.0.0"

rm -rf "$FDROIDSERVER_DIR"
mkdir -p "$FDROIDSERVER_DIR"
curl --fail --location --silent --show-error \
  "https://gitlab.com/fdroid/fdroidserver/-/archive/${FDROIDSERVER_COMMIT}/fdroidserver-${FDROIDSERVER_COMMIT}.tar.gz" \
  | tar -xz --directory="$FDROIDSERVER_DIR" --strip-components=1
git -C /home/vagrant/gradlew-fdroid pull --ff-only

rm -rf /home/vagrant/metadata
mkdir -p /home/vagrant/metadata /home/vagrant/build /home/vagrant/logs /home/vagrant/tmp /home/vagrant/unsigned
cp "/workspace/metadata/${APP_ID}.yml" /home/vagrant/metadata/
chown -R vagrant:vagrant \
  /home/vagrant/metadata \
  /home/vagrant/build \
  /home/vagrant/logs \
  /home/vagrant/tmp \
  /home/vagrant/unsigned \
  /home/vagrant/.gradle

export PATH="$FDROIDSERVER_DIR:$PATH"
export PYTHONPATH="$FDROIDSERVER_DIR:$FDROIDSERVER_DIR/examples"
export PYTHONUNBUFFERED=true
export GRADLE_USER_HOME=/home/vagrant/.gradle
export TERM=dumb

run_fdroid() {
  sudo --preserve-env --user vagrant env \
    "PATH=$PATH" \
    "PYTHONPATH=$PYTHONPATH" \
    PYTHONUNBUFFERED=true \
    TERM=dumb \
    HOME=/home/vagrant \
    "GRADLE_USER_HOME=$GRADLE_USER_HOME" \
    fdroid "$@"
}

cd /home/vagrant
if [ -d "build/${APP_ID}/.git" ]; then
  sudo --preserve-env --user vagrant env \
    HOME=/home/vagrant \
    git -C "build/${APP_ID}" fetch --prune --tags --force origin
fi
run_fdroid fetchsrclibs "${APP_ID}:${VERSION_CODE}" --verbose
run_fdroid build \
  --verbose \
  --test \
  --refresh-scanner \
  --on-server \
  --no-tarball \
  "${APP_ID}:${VERSION_CODE}"
