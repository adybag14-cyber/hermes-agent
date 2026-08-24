#!/usr/bin/env bash
set -euo pipefail

# Run inside the official F-Droid buildserver-trixie container with this
# repository (or a fdroiddata checkout containing its metadata) at /workspace.
APP_ID="${APP_ID:-com.mobilefork.hermesagent}"
VERSION_NAME="${VERSION_NAME:-0.13.151}"
VERSION_CODE="${VERSION_CODE:-145190}"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly HERMES_FDROID_TEMPLATE="${HERMES_FDROID_TEMPLATE:-${SCRIPT_DIR}/${APP_ID}.yml.template}"
readonly HERMES_SOURCE_BINDING_HELPER="${HERMES_SOURCE_BINDING_HELPER:-${SCRIPT_DIR}/../scripts/android_fdroid_source_binding.py}"
readonly BUILDSERVER_IMAGE="registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9bae53bb4ddbf8fa5bb7385bf2e62e7c6318f99ab0d25b2a551ad38abb528068"
readonly BUILDSERVER_REVISION="4a8821a58659901c63315cb000b0e98525653bc5"
readonly FDROIDSERVER_COMMIT="$BUILDSERVER_REVISION"
readonly FDROIDSERVER_ARCHIVE_URL="https://gitlab.com/fdroid/fdroidserver/-/archive/${FDROIDSERVER_COMMIT}/fdroidserver-${FDROIDSERVER_COMMIT}.tar.gz"
readonly FDROIDSERVER_ARCHIVE_SHA256="8b2f87ef6e278a49f70b98fe0ff007465524f41c15ba212f686f4239a7323909"
readonly FDROIDSERVER_ARCHIVE_SIZE_BYTES="8336107"
readonly GRADLEW_FDROID_COMMIT="c7227d147483979bb5c408048cee3533a8814fb0"
readonly GRADLE_MAX_WORKERS="12"
readonly GRADLE_OPTS="-Dorg.gradle.workers.max=${GRADLE_MAX_WORKERS} -Dorg.gradle.parallel=true"
readonly ANDROID_NDK_VERSION="29.0.14206865"
readonly ANDROID_NDK_PACKAGE="ndk;${ANDROID_NDK_VERSION}"
readonly ANDROID_CMAKE_VERSION="3.31.6"
readonly ANDROID_CMAKE_PACKAGE="cmake;${ANDROID_CMAKE_VERSION}"
readonly ANDROID_NINJA_VERSION="1.12.1"
readonly BUILDSERVER_ID_FILE=/home/vagrant/buildserverid
readonly VAGRANT_ENV_MODE="env-i"
readonly VAGRANT_ENV_REQUIRED_NAMES="PATH,PYTHONPATH,PYTHONUNBUFFERED,HOME,GRADLE_USER_HOME,GRADLE_OPTS,TERM,LC_ALL,LANG,ANDROID_HOME"
readonly VAGRANT_ENV_OPTIONAL_NAMES="ANDROID_SDK,ANDROID_SDK_ROOT,JAVA_HOME"
FDROIDSERVER_DIR=/home/vagrant/fdroidserver
GRADLEW_FDROID_DIR=/home/vagrant/gradlew-fdroid

export GRADLE_OPTS

fail() {
  printf 'Hermes F-Droid toolchain error: %s\n' "$*" >&2
  return 1
}

validate_toolchain_contract() {
  [[ "$BUILDSERVER_IMAGE" =~ @sha256:[0-9a-f]{64}$ ]] \
    || fail "buildserver image is not pinned by a full SHA-256 digest"
  [[ "$BUILDSERVER_REVISION" =~ ^[0-9a-f]{40}$ ]] \
    || fail "buildserver revision is not a full Git commit"
  [[ "$FDROIDSERVER_COMMIT" =~ ^[0-9a-f]{40}$ ]] \
    || fail "fdroidserver revision is not a full Git commit"
  [[ "$FDROIDSERVER_ARCHIVE_URL" == "https://gitlab.com/fdroid/fdroidserver/-/archive/${FDROIDSERVER_COMMIT}/fdroidserver-${FDROIDSERVER_COMMIT}.tar.gz" ]] \
    || fail "fdroidserver archive URL does not match the pinned commit"
  [[ "$FDROIDSERVER_ARCHIVE_SHA256" =~ ^[0-9a-f]{64}$ ]] \
    || fail "fdroidserver archive is not pinned by a full SHA-256 digest"
  [[ "$FDROIDSERVER_ARCHIVE_SIZE_BYTES" =~ ^[1-9][0-9]*$ ]] \
    || fail "fdroidserver archive size must be a positive integer"
  [[ "$GRADLEW_FDROID_COMMIT" =~ ^[0-9a-f]{40}$ ]] \
    || fail "gradlew-fdroid revision is not a full Git commit"
  [[ "$FDROIDSERVER_COMMIT" == "$BUILDSERVER_REVISION" ]] \
    || fail "fdroidserver revision does not match the buildserver image revision"
  [[ "$GRADLE_MAX_WORKERS" =~ ^[1-9][0-9]*$ ]] \
    || fail "Gradle worker count must be a positive integer"
  [[ "$ANDROID_NDK_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] \
    || fail "Android NDK revision must be one exact three-component version"
  [[ "$ANDROID_NDK_PACKAGE" == "ndk;${ANDROID_NDK_VERSION}" ]] \
    || fail "Android NDK package does not match its locked revision"
  [[ "$ANDROID_CMAKE_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] \
    || fail "Android CMake revision must be one exact three-component version"
  [[ "$ANDROID_CMAKE_PACKAGE" == "cmake;${ANDROID_CMAKE_VERSION}" ]] \
    || fail "Android CMake package does not match its locked revision"
  [[ "$ANDROID_NINJA_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] \
    || fail "Android Ninja revision must be one exact three-component version"
  [[ "$VERSION_NAME" =~ ^0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] \
    || fail "release version name must be an exact v0 semantic version without a v prefix"
  [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] \
    || fail "release version code must be a positive integer"
}

print_contract() {
  printf 'BUILDSERVER_IMAGE=%s\n' "$BUILDSERVER_IMAGE"
  printf 'BUILDSERVER_REVISION=%s\n' "$BUILDSERVER_REVISION"
  printf 'FDROIDSERVER_COMMIT=%s\n' "$FDROIDSERVER_COMMIT"
  printf 'FDROIDSERVER_ARCHIVE_URL=%s\n' "$FDROIDSERVER_ARCHIVE_URL"
  printf 'FDROIDSERVER_ARCHIVE_SHA256=%s\n' "$FDROIDSERVER_ARCHIVE_SHA256"
  printf 'FDROIDSERVER_ARCHIVE_SIZE_BYTES=%s\n' "$FDROIDSERVER_ARCHIVE_SIZE_BYTES"
  printf 'GRADLEW_FDROID_COMMIT=%s\n' "$GRADLEW_FDROID_COMMIT"
  printf 'GRADLE_MAX_WORKERS=%s\n' "$GRADLE_MAX_WORKERS"
  printf 'GRADLE_OPTS=%s\n' "$GRADLE_OPTS"
  printf 'ANDROID_NDK_VERSION=%s\n' "$ANDROID_NDK_VERSION"
  printf 'ANDROID_NDK_PACKAGE=%s\n' "$ANDROID_NDK_PACKAGE"
  printf 'ANDROID_CMAKE_VERSION=%s\n' "$ANDROID_CMAKE_VERSION"
  printf 'ANDROID_CMAKE_PACKAGE=%s\n' "$ANDROID_CMAKE_PACKAGE"
  printf 'ANDROID_NINJA_VERSION=%s\n' "$ANDROID_NINJA_VERSION"
  printf 'VERSION_NAME=%s\n' "$VERSION_NAME"
  printf 'VERSION_CODE=%s\n' "$VERSION_CODE"
  printf 'SOURCE_BINDING_GRADLE_PROPERTY=hermesFdroidSourceBinding=true\n'
  printf 'VAGRANT_ENV_MODE=%s\n' "$VAGRANT_ENV_MODE"
  printf 'VAGRANT_ENV_REQUIRED_NAMES=%s\n' "$VAGRANT_ENV_REQUIRED_NAMES"
  printf 'VAGRANT_ENV_OPTIONAL_NAMES=%s\n' "$VAGRANT_ENV_OPTIONAL_NAMES"
}

run_metadata_preview_helper() {
  local command="$1"
  local metadata_file="$2"
  local template_file="$3"

  [[ -f "$HERMES_SOURCE_BINDING_HELPER" && ! -L "$HERMES_SOURCE_BINDING_HELPER" ]] \
    || fail "source-binding helper is not a regular file: $HERMES_SOURCE_BINDING_HELPER"
  [[ -f "$metadata_file" && ! -L "$metadata_file" ]] \
    || fail "autoupdater metadata is not a regular file: $metadata_file"
  [[ -f "$template_file" && ! -L "$template_file" ]] \
    || fail "F-Droid metadata template is not a regular file: $template_file"
  python3 "$HERMES_SOURCE_BINDING_HELPER" "$command" \
    --metadata "$metadata_file" \
    --template "$template_file" \
    --version "$VERSION_NAME" \
    --version-code "$VERSION_CODE"
}

render_metadata_preview() {
  run_metadata_preview_helper "render-autoupdate-preview" "$1" "$2"
  run_metadata_preview_helper "verify-autoupdate-preview" "$1" "$2"
}

verify_metadata_preview() {
  run_metadata_preview_helper "verify-autoupdate-preview" "$1" "$2"
}

verify_buildserver_id() {
  local id_file="$1"
  local actual_revision

  [[ -r "$id_file" ]] || fail "buildserver ID is not readable: $id_file"
  actual_revision="$(tr -d '\r\n' < "$id_file")"
  [[ "$actual_revision" == "$BUILDSERVER_REVISION" ]] \
    || fail "buildserver ID $actual_revision does not match expected revision $BUILDSERVER_REVISION"
  printf 'Verified F-Droid buildserver revision %s\n' "$actual_revision"
}

usage() {
  printf '%s\n' \
    "Usage: ${0##*/} [--print-contract | --verify-buildserver-id PATH |" \
    "  --render-autoupdate-preview METADATA [TEMPLATE] |" \
    "  --verify-autoupdate-preview METADATA [TEMPLATE]]" >&2
}

validate_toolchain_contract
case "${1:-}" in
  --print-contract)
    [[ "$#" -eq 1 ]] || { usage; exit 64; }
    print_contract
    exit 0
    ;;
  --verify-buildserver-id)
    [[ "$#" -eq 2 ]] || { usage; exit 64; }
    verify_buildserver_id "$2"
    exit 0
    ;;
  --render-autoupdate-preview)
    [[ "$#" -ge 2 && "$#" -le 3 ]] || { usage; exit 64; }
    render_metadata_preview "$2" "${3:-$HERMES_FDROID_TEMPLATE}"
    exit 0
    ;;
  --verify-autoupdate-preview)
    [[ "$#" -ge 2 && "$#" -le 3 ]] || { usage; exit 64; }
    verify_metadata_preview "$2" "${3:-$HERMES_FDROID_TEMPLATE}"
    exit 0
    ;;
  "")
    ;;
  *)
    usage
    exit 64
    ;;
esac

# The local autoupdater preview is the only metadata authority for this no-MR
# run. Fail before any SDK, fdroidserver, source, or dependency download unless
# its resolved release build contains the exact committed source-binding recipe.
metadata_preview="/workspace/metadata/${APP_ID}.yml"
verify_metadata_preview "$metadata_preview" "$HERMES_FDROID_TEMPLATE"

# Fail before downloads if the caller used a different buildserver image.
verify_buildserver_id "$BUILDSERVER_ID_FILE"
set -x

source /etc/profile.d/bsenv.sh
sdkmanager "build-tools;31.0.0" "$ANDROID_NDK_PACKAGE" "$ANDROID_CMAKE_PACKAGE"
[[ -n "${ANDROID_HOME:-}" ]] || fail "bsenv did not define ANDROID_HOME"
android_sdk_root="${ANDROID_HOME%/}"
ndk_root="${android_sdk_root}/ndk/${ANDROID_NDK_VERSION}"
cmake_bin="${android_sdk_root}/cmake/${ANDROID_CMAKE_VERSION}/bin/cmake"
ninja_bin="${android_sdk_root}/cmake/${ANDROID_CMAKE_VERSION}/bin/ninja"
test -x "${android_sdk_root}/build-tools/31.0.0/aapt"
test -r "${ndk_root}/source.properties"
test "$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "${ndk_root}/source.properties")" = "$ANDROID_NDK_VERSION"
test -x "$cmake_bin"
test -x "$ninja_bin"
test "$(LC_ALL=C "$cmake_bin" --version | sed -n '1p')" = "cmake version ${ANDROID_CMAKE_VERSION}"
test "$(LC_ALL=C "$ninja_bin" --version | sed -n '1p')" = "$ANDROID_NINJA_VERSION"
printf 'Verified Android native toolchain: %s, cmake %s, ninja %s\n' \
  "$ANDROID_NDK_PACKAGE" "$ANDROID_CMAKE_VERSION" "$ANDROID_NINJA_VERSION"

rm -rf "$FDROIDSERVER_DIR"
mkdir -p "$FDROIDSERVER_DIR"
fdroidserver_archive="$(mktemp --tmpdir=/tmp "hermes-fdroidserver-${FDROIDSERVER_COMMIT}.XXXXXXXXXX")"
cleanup_fdroidserver_archive() {
  rm -f -- "$fdroidserver_archive"
}
trap cleanup_fdroidserver_archive EXIT
[[ -f "$fdroidserver_archive" && ! -L "$fdroidserver_archive" ]] \
  || fail "fdroidserver archive temporary path is not a regular file"
curl --fail --location --silent --show-error \
  --max-filesize "$FDROIDSERVER_ARCHIVE_SIZE_BYTES" \
  --output "$fdroidserver_archive" \
  "$FDROIDSERVER_ARCHIVE_URL"
test "$(stat --format='%s' "$fdroidserver_archive")" = "$FDROIDSERVER_ARCHIVE_SIZE_BYTES"
printf '%s  %s\n' "$FDROIDSERVER_ARCHIVE_SHA256" "$fdroidserver_archive" \
  | sha256sum --check --strict -
tar -xzf "$fdroidserver_archive" --directory="$FDROIDSERVER_DIR" --strip-components=1
rm -f -- "$fdroidserver_archive"
trap - EXIT
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

VAGRANT_ENV=(
  "PATH=$PATH"
  "PYTHONPATH=$PYTHONPATH"
  "PYTHONUNBUFFERED=$PYTHONUNBUFFERED"
  "HOME=/home/vagrant"
  "GRADLE_USER_HOME=$GRADLE_USER_HOME"
  "GRADLE_OPTS=$GRADLE_OPTS"
  "TERM=$TERM"
  "LC_ALL=C.UTF-8"
  "LANG=C.UTF-8"
  "ANDROID_HOME=$ANDROID_HOME"
)
for optional_name in ANDROID_SDK ANDROID_SDK_ROOT JAVA_HOME; do
  if [[ -n "${!optional_name:-}" ]]; then
    VAGRANT_ENV+=("${optional_name}=${!optional_name}")
  fi
done
readonly -a VAGRANT_ENV

run_fdroid() {
  sudo -u vagrant env -i \
    "${VAGRANT_ENV[@]}" \
    fdroid "$@"
}

cd /home/vagrant
if [ -d "build/${APP_ID}/.git" ]; then
  sudo -u vagrant env -i \
    "${VAGRANT_ENV[@]}" \
    git -C "build/${APP_ID}" fetch --prune --tags --force origin
fi
run_fdroid fetchsrclibs "${APP_ID}:${VERSION_CODE}" --verbose
run_fdroid build \
  --verbose \
  --test \
  --on-server \
  --no-tarball \
  "${APP_ID}:${VERSION_CODE}"
