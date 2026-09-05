---
name: fdroid-merge-request-debugging
description: Certify Android releases and repair F-Droid reviews.
version: 1.0.3
author: "adybag14-cyber, Hermes Agent"
license: MIT
platforms: [linux]
metadata:
  hermes:
    tags: [fdroid, android, reproducible-builds, gitlab, release]
    category: devops
---

# F-Droid Merge Request Debugging Skill

Validate an Android release against its F-Droid update and build authorities,
or repair an explicitly requested fdroiddata review. A release audit does not
authorize creating a merge request, publishing metadata, or resolving discussions.

## When to Use

Use for autoupdater drift, reproducible APK mismatches, or reviewer-requested
metadata corrections. Do not apply this workflow to ordinary Android feature work.

## Prerequisites

- A native Linux workspace with the required fdroidserver/buildserver toolchain.
  On Windows, run the Linux steps inside WSL or a container, not a Windows checkout
  of fdroiddata: its symlinks must remain real symlinks.
- Read-only access to the app source, release assets, fdroiddata and relevant CI.
  Authenticated GitLab access is needed only for explicitly authorized MR writes.
- Determine the actual app ID, version code, tag, source commit and signing-key
  allowlist from the repository. Never reuse a different fork's package identity.

## How to Run

Use `terminal` for Git, fdroidserver and authenticated GitLab operations.
Use `read_file` for the app's committed release contract and `patch` for scoped
changes. Use `web_extract` or `browser_navigate` to inspect authoritative review
pages when an API is unavailable; do not claim an unsubmitted action succeeded.

## Quick Reference

| Authority | Required evidence |
| --- | --- |
| GitHub release | Public tag, peeled commit, version, APK bytes and signer |
| F-Droid updater | Fresh post-publication discovery of that exact release |
| F-Droid builder | Pinned build matching the public signed APK after signature normalization |
| Requested MR | Exact metadata diff, latest terminal CI and addressed discussions |

## Procedure

1. Inspect app and fdroiddata worktree status before editing. Preserve unrelated
   changes. For an existing review, retrieve the current MR, unresolved discussion
   IDs and failed job logs; screenshots and earlier pipeline statuses are only hints.
2. Resolve immutable source and toolchain inputs. For an app supplying
   `fdroid/LOCAL_TOOLCHAIN.md` and `fdroid/run-local-buildserver.sh`, read them in
   that app checkout and inspect the helper's `--print-contract` output.
3. Verify the public reference APK, its digest, manifest version and signer against
   the release and allowed signing keys. A successful source build followed by a
   missing `Binaries` URL is a publication failure, not proof of irreproducibility.
4. After GitHub publication, run both release gates below in separate fresh
   environments. A pre-release candidate build or cached update preview is not
   post-release evidence.
5. For requested metadata repairs, edit only the identified app recipe. Preserve
   historical builds and unrelated metadata. Keep the declared release-tag filter,
   `UpdateCheckData`, `Binaries` and signing-key policy unless the evidence calls
   for a specific change. Prefer full commit identities for build inputs.
6. Run local metadata validation and inspect formatter output before applying it.
   If the CI formatter requires a precise YAML change, preserve it exactly and
   explain intentional formatting exceptions rather than making unrelated edits.
7. Commit or push only within the user's authorized scope. Use process-local
   noninteractive authentication settings; do not rewrite global Git credentials
   or persistent user environment variables merely to avoid a prompt.
8. For an authorized MR, monitor its latest pipeline to a terminal result. Reply
   and resolve a discussion only after its requested change is implemented and
   validated. Re-query the MR after writes; do not amend source history solely
   to force a description update.

### Post-release updater gate

Start from a fresh checkout of live fdroiddata after the GitHub release is public.
Run the pinned updater and record the detected version name/code and exact source
commit. Verify that it selects the latest intended release, not an older cached tag.
If the inherited recipe needs the app's committed source-binding overlay, apply
and verify only the documented fields in that generated recipe; never replace the
whole live metadata file with a one-version template. Without submission authority,
keep the preview local and do not open a fdroiddata MR.

### Post-release reproducibility gate

Build the exact discovered commit in a separate fresh pinned buildserver using the
verified recipe and public `Binaries` reference. Retain toolchain identities, full
build logs, artifact hashes and the comparison outcome. A compiled APK, successful
lint, matching version, or signature verification alone does not prove reproducibility.
For Chaquopy, verify the committed asset-normalization contract and source binding;
do not hand-edit generated bytecode, ZIP metadata or evidence to manufacture a match.

## Pitfalls

- An updater's inherited recipe may discover a release yet still lack its required
  NDK, CMake or source-binding changes. Report discovery and build results separately.
- A reference-APK 404, authentication failure, unavailable runner or toolchain mismatch
  is not a green release gate. Inspect the exact failed step before changing code.
- Preserve signer allowlists and fail-closed source checks. Never expose signing
  material, tokens or live credentials in commands, logs, metadata or reviewer replies.
- Do not reset Docker, terminate unrelated processes or change global host settings
  to make a local build pass. Use an authorized isolated runner or report the blocker.
- A successful no-op push does not prove a description or reviewer reply changed.
  Use the appropriate authorized API/UI operation and verify its result.

## Verification

A release certification requires both fresh post-GitHub gates to pass for the same
immutable version, commit and public APK. For an authorized MR, additionally require
the relevant build, APK comparison, updater, lint, formatter, schema and source jobs
to finish successfully, then confirm the requested discussions are addressed.

Report exact source/release identities, hashes, toolchain, gate results, CI URLs,
remaining blockers and worktree state. If no MR was requested, explicitly state
that no fdroiddata submission or reviewer action was performed.
