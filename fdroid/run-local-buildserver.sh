#!/usr/bin/env bash
set -euxo pipefail

# Run inside the official F-Droid buildserver-trixie container with this
# repository (or a fdroiddata checkout containing its metadata) at /workspace.
APP_ID="${APP_ID:-com.mobilefork.hermesagent}"
VERSION_CODE="${VERSION_CODE:-144790}"
FDROIDSERVER_COMMIT="${FDROIDSERVER_COMMIT:-00932d0a715b43b3ecf8da44826abf2ba65dd8b4}"
GRADLEW_FDROID_COMMIT="${GRADLEW_FDROID_COMMIT:-c7227d147483979bb5c408048cee3533a8814fb0}"
FDROIDSERVER_DIR=/home/vagrant/fdroidserver
GRADLEW_FDROID_DIR=/home/vagrant/gradlew-fdroid

source /etc/profile.d/bsenv.sh
sdkmanager "build-tools;31.0.0"
test -x /opt/android-sdk/build-tools/31.0.0/aapt

rm -rf "$FDROIDSERVER_DIR"
mkdir -p "$FDROIDSERVER_DIR"
curl --fail --location --silent --show-error \
  "https://gitlab.com/fdroid/fdroidserver/-/archive/${FDROIDSERVER_COMMIT}/fdroidserver-${FDROIDSERVER_COMMIT}.tar.gz" \
  | tar -xz --directory="$FDROIDSERVER_DIR" --strip-components=1
git -C "$GRADLEW_FDROID_DIR" fetch --force origin "$GRADLEW_FDROID_COMMIT"
git -C "$GRADLEW_FDROID_DIR" checkout --detach --force "$GRADLEW_FDROID_COMMIT"
test "$(git -C "$GRADLEW_FDROID_DIR" rev-parse HEAD)" = "$GRADLEW_FDROID_COMMIT"

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
  --on-server \
  --no-tarball \
  "${APP_ID}:${VERSION_CODE}"
