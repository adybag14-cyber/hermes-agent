#!/usr/bin/env bash
set -euo pipefail

: "${HERMES_RELEASE_TAG:?HERMES_RELEASE_TAG is required}"
: "${DEFAULT_BRANCH:?DEFAULT_BRANCH is required}"
: "${EXPECTED_RELEASE_COMMIT:?EXPECTED_RELEASE_COMMIT is required}"
: "${EXPECTED_TAG_OBJECT_SHA:?EXPECTED_TAG_OBJECT_SHA is required}"

printf '%s' "${HERMES_RELEASE_TAG}" | \
  grep -Eq '^v0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)(\.[0-9]+)?)?$'
printf '%s' "${EXPECTED_RELEASE_COMMIT}" | grep -Eq '^[0-9a-f]{40}$'
printf '%s' "${EXPECTED_TAG_OBJECT_SHA}" | grep -Eq '^[0-9a-f]{40}$'
git check-ref-format --branch "${DEFAULT_BRANCH}"

readonly authority_default_ref='refs/remotes/hermes-release-authority/default'
readonly authority_tag_ref='refs/hermes-release-authority/tag'
export GIT_TERMINAL_PROMPT=0

git fetch --no-tags --force --depth=1 origin \
  "+refs/heads/${DEFAULT_BRANCH}:${authority_default_ref}"
git fetch --no-tags --force --depth=1 origin \
  "+refs/tags/${HERMES_RELEASE_TAG}:${authority_tag_ref}"

checked_out_commit="$(git rev-parse --verify 'HEAD^{commit}')"
live_default_commit="$(git rev-parse --verify "${authority_default_ref}^{commit}")"
live_tag_type="$(git cat-file -t "${authority_tag_ref}")"
live_tag_object_sha="$(git rev-parse --verify "${authority_tag_ref}")"
live_tag_commit="$(git rev-parse --verify "${authority_tag_ref}^{commit}")"

printf '%s\n' \
  "checkedOutCommit=${checked_out_commit}" \
  "liveDefaultCommit=${live_default_commit}" \
  "liveTagType=${live_tag_type}" \
  "liveTagObjectSha=${live_tag_object_sha}" \
  "liveTagCommit=${live_tag_commit}"

test "${checked_out_commit}" = "${EXPECTED_RELEASE_COMMIT}"
test "${live_default_commit}" = "${EXPECTED_RELEASE_COMMIT}"
test "${live_tag_type}" = 'tag'
test "${live_tag_object_sha}" = "${EXPECTED_TAG_OBJECT_SHA}"
test "${live_tag_commit}" = "${EXPECTED_RELEASE_COMMIT}"
