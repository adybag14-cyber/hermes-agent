from pathlib import Path
import importlib.util
import os
import re
import subprocess
import sys

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_android_release_manifest_module():
    script = REPO_ROOT / "scripts/android_release_manifest.py"
    spec = importlib.util.spec_from_file_location("android_release_manifest", script)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _load_android_release_identity_module():
    script = REPO_ROOT / "scripts/check_android_release_identity.py"
    spec = importlib.util.spec_from_file_location("check_android_release_identity", script)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_android_build_gradle_supports_semver_alpha_release_tags():
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")

    assert 'fun androidVersionName()' in gradle
    assert 'v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z]+)(?:[.-]?(\\d+))?)?' in gradle
    assert '"alpha" -> 1' in gradle
    assert '"beta" -> 2' in gradle
    assert 'versionName = androidVersionName()' in gradle


def test_android_release_identity_matches_fdroid_metadata_and_rejects_a_wrong_tag():
    identity_module = _load_android_release_identity_module()
    version_fields = dict(
        line.split("=", 1)
        for line in (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.version")
        .read_text(encoding="utf-8")
        .splitlines()
        if "=" in line
    )
    version_name = version_fields["versionName"]
    major, minor, patch = (int(part) for part in version_name.split("."))

    identity = identity_module.validate_release_identity(REPO_ROOT, f"v{version_name}")
    assert identity.version_name == version_name
    assert identity.version_code == int(version_fields["versionCode"])
    with pytest.raises(ValueError, match="identity mismatch"):
        identity_module.validate_release_identity(REPO_ROOT, f"v{major}.{minor}.{patch + 1}")


def _assert_external_actions_are_commit_pinned(workflow: str) -> None:
    action_refs = re.findall(r"^\s*-?\s*uses:\s*([^\s#]+)", workflow, flags=re.MULTILINE)
    assert action_refs
    for action_ref in action_refs:
        _action, separator, revision = action_ref.partition("@")
        assert separator and re.fullmatch(r"[0-9a-f]{40}", revision), action_ref


def test_android_release_workflow_restores_signing_material_and_builds_release_artifacts():
    workflow = (REPO_ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")
    fdroid_template = (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template").read_text(
        encoding="utf-8",
    )

    _assert_external_actions_are_commit_pinned(workflow)
    assert "persist-credentials: false" in workflow
    assert "refs/remotes/hermes-release-authority/default" in workflow
    assert "refs/hermes-release-authority/tag" in workflow
    assert 'test "$local_tag_type" = \'tag\'' in workflow
    assert 'test "$remote_tag_type" = \'tag\'' in workflow
    assert 'test "$remote_tag_commit" = "$tagged_commit"' in workflow
    assert workflow.count("bash scripts/verify_android_remote_release_authority.sh") == 4
    assert 'ANDROID_KEYSTORE_BASE64' in workflow
    assert 'ANDROID_KEYSTORE_PASSWORD' in workflow
    assert 'ANDROID_KEY_ALIAS' in workflow
    assert 'ANDROID_KEY_PASSWORD' in workflow
    assert 'scripts/check_android_release_identity.py --tag "$GITHUB_REF_NAME"' in workflow
    assert 'bash scripts/run_tests.sh' in workflow
    assert ':app:compileDebugAndroidTestKotlin' in workflow
    assert './gradlew :app:assembleRelease' in workflow
    assert './gradlew :app:bundleRelease' in workflow
    assert 'app-release-unsigned.apk' in workflow
    assert 'app-universal-release.apk' in workflow
    assert '--alignment-preserved true' in workflow
    assert '--v2-signing-enabled true' in workflow
    assert '--ks-pass env:ANDROID_KEYSTORE_PASSWORD' in workflow
    configured_build_tools = re.findall(r"packages:.*build-tools;([0-9.]+)", workflow)
    selected_build_tools = re.findall(
        r'build_tools_dir="\$sdk_root/build-tools/([0-9.]+)"',
        workflow,
    )
    assert configured_build_tools == selected_build_tools
    assert len(configured_build_tools) == 1
    workflow_signer = re.search(r"EXPECTED_SIGNER_SHA256:\s*([0-9a-f]{64})", workflow)
    fdroid_signer = re.search(r"^AllowedAPKSigningKeys:\s*([0-9a-f]{64})$", fdroid_template, re.MULTILINE)
    assert workflow_signer is not None
    assert fdroid_signer is not None
    assert workflow_signer.group(1) == fdroid_signer.group(1)
    assert 'aapt2" dump badging' in workflow
    assert 'jarsigner -verify "$aab"' in workflow
    assert 'cp -f "$signed_apk" "$universal_apk"' in workflow
    assert 'rm -f "$unsigned_apk"' in workflow
    assert "trap cleanup_signing_material EXIT" in workflow
    assert "if: ${{ always() }}" in workflow
    assert "rm -f android/release.keystore android/keystore.properties" in workflow
    physical_candidate_gate = workflow.index(
        "- name: Require final release APK to match the certified physical-device candidate"
    )
    signed_build_step = workflow.index("- name: Build and sign Android artifacts")
    manifest_step = workflow.index("- name: Rename artifacts and write checksums")
    assert signed_build_step < physical_candidate_gate < manifest_step
    assert (
        'release-evidence/${HERMES_RELEASE_TAG}/physical-device/'
        'nanbeige4.2-3b-q4-k-m-repair.json'
    ) in workflow
    assert '["release_identity"]["candidate_apk_sha256"]' in workflow
    assert '["release_identity"]["candidate_apk_bytes"]' in workflow
    assert 'release_sha256="$(sha256sum "$signed_apk"' in workflow
    assert 'release_bytes="$(stat -c \'%s\' "$signed_apk")"' in workflow
    assert 'test "$release_sha256" = "$candidate_sha256"' in workflow
    assert 'test "$release_bytes" = "$candidate_bytes"' in workflow
    assert "android_release_evidence.py verify-physical-candidate-apk" in workflow
    assert '--apk "$(pwd)/$signed_apk"' in workflow
    assert 'scripts/android_release_manifest.py --tag' in workflow
    assert "tags:\n      - 'v0.*'" in workflow
    assert "- 'v*'" not in workflow
    assert "release:\n    types:" not in workflow
    assert 'group: android-release-${{ github.ref_name }}' in workflow
    assert 'HERMES_RELEASE_TAG: ${{ github.ref_name }}' in workflow
    assert 'gh release upload "$HERMES_RELEASE_TAG"' in workflow
    assert 'gh release create "$HERMES_RELEASE_TAG"' in workflow
    assert '--draft' in workflow
    assert '--verify-tag' in workflow
    assert re.search(
        r'gh release view\s+"\$HERMES_RELEASE_TAG"\s+\\?\s*\n?\s*--json assets',
        workflow,
    )
    assert 'gh release edit "$HERMES_RELEASE_TAG" --draft=false --latest' in workflow
    assert 'GH_TOKEN: ${{ github.token }}' in workflow
    pre_sign = workflow.index(
        "- name: Reauthorize live default head and annotated tag before signing"
    )
    restore_signing = workflow.index("- name: Restore signing material")
    pre_draft = workflow.index(
        "- name: Reauthorize live default head and annotated tag before draft creation"
    )
    draft = workflow.index("- name: Create or update draft release")
    pre_upload = workflow.index(
        "- name: Reauthorize live default head and annotated tag before asset upload"
    )
    upload = workflow.index("- name: Upload release assets")
    publish = workflow.index("- name: Publish verified release")
    assert pre_sign < restore_signing < pre_draft < draft < pre_upload < upload < publish
    assert workflow.index(
        "bash scripts/verify_android_remote_release_authority.sh",
        publish,
    ) > publish


def test_android_push_workflow_uses_node24_ready_action_versions():
    workflow = (REPO_ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")

    _assert_external_actions_are_commit_pinned(workflow)
    assert 'python -m venv .venv' in workflow
    assert 'bash scripts/run_tests.sh' in workflow
    assert 'python -m pytest' not in workflow


def test_android_push_workflow_compiles_android_test_sources():
    workflow = (REPO_ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")

    assert './gradlew :app:compileDebugAndroidTestKotlin -PskipHermesAndroidLinuxAssets=true' in workflow


def test_android_signed_device_candidate_is_default_head_bound_and_nonpublishing():
    workflow = (REPO_ROOT / ".github/workflows/android-device-candidate.yml").read_text(
        encoding="utf-8",
    )
    fdroid_template = (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template").read_text(
        encoding="utf-8",
    )

    _assert_external_actions_are_commit_pinned(workflow)
    assert "repository_dispatch:" in workflow
    assert "types: [android-device-candidate]" in workflow
    assert "workflow_dispatch:" not in workflow
    assert "${{ inputs." not in workflow
    assert "github.event.client_payload.candidate_sha" in workflow
    assert "github.event.client_payload.release_tag" in workflow
    assert 'test "$GITHUB_EVENT_NAME" = "repository_dispatch"' in workflow
    assert 'test "$GITHUB_REF" = "refs/heads/${DEFAULT_BRANCH}"' in workflow
    assert "grep -Eq '^[0-9a-f]{40}$'" in workflow
    assert workflow.count('test "$GITHUB_SHA" = "$REQUESTED_CANDIDATE_SHA"') == 4
    assert "ref: ${{ steps.authority.outputs.candidate_sha }}" in workflow
    assert "fetch-depth: 1" in workflow
    assert "persist-credentials: false" in workflow
    assert 'git fetch --no-tags --depth=1 origin' in workflow
    assert 'checked_out_sha="$(git rev-parse --verify \'HEAD^{commit}\')"' in workflow
    assert 'live_default_sha="$(git rev-parse --verify' in workflow
    assert workflow.count('test "$checked_out_sha" = "$REQUESTED_CANDIDATE_SHA"') == 3
    assert workflow.count('test "$live_default_sha" = "$REQUESTED_CANDIDATE_SHA"') == 3
    assert "contents: read" in workflow
    assert "contents: write" not in workflow
    assert "scripts/check_android_release_identity.py --tag" in workflow
    assert "scripts/android_release_evidence.py source-identity" in workflow
    assert "--require-clean" in workflow
    signing_step = workflow.index("- name: Restore, sign, and verify source-bound candidate APK")
    unsigned_build_step = workflow.index("- name: Build unsigned source-bound candidate APK")
    live_head_guard = workflow.index('test "$live_default_sha" = "$REQUESTED_CANDIDATE_SHA"')
    pre_sign_guard = workflow.index(
        "- name: Reauthorize live default head before restoring signing secrets"
    )
    post_sign_guard = workflow.index(
        "- name: Require candidate to remain live default head before upload"
    )
    upload_step = workflow.index("- name: Upload signed device candidate")
    assert (
        live_head_guard
        < unsigned_build_step
        < pre_sign_guard
        < signing_step
        < post_sign_guard
        < upload_step
    )
    for secret in (
        "ANDROID_KEYSTORE_BASE64",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD",
    ):
        secret_expression = f"${{{{ secrets.{secret} }}}}"
        assert workflow.count(secret_expression) == 1
        assert secret_expression not in workflow[:signing_step]
    assert "trap cleanup_signing_material EXIT" in workflow
    assert "./gradlew :app:assembleRelease --no-daemon" in workflow
    assert workflow.count("scripts/verify_android_source_bound_artifact.py") == 2
    assert "--alignment-preserved true" in workflow
    assert "--v2-signing-enabled true" in workflow
    assert "--ks-pass env:ANDROID_KEYSTORE_PASSWORD" in workflow
    candidate_signer = re.search(r"EXPECTED_SIGNER_SHA256:\s*([0-9a-f]{64})", workflow)
    fdroid_signer = re.search(r"^AllowedAPKSigningKeys:\s*([0-9a-f]{64})$", fdroid_template, re.MULTILINE)
    assert candidate_signer is not None
    assert fdroid_signer is not None
    assert candidate_signer.group(1) == fdroid_signer.group(1)
    assert "hermes-agent-android-${HERMES_RELEASE_TAG}-device-candidate.apk" in workflow
    assert "actions/upload-artifact@" in workflow
    assert "gh release" not in workflow
    assert "git push" not in workflow


def test_remote_release_authority_script_rejects_tag_and_default_ref_drift(tmp_path):
    if os.name == "nt":
        pytest.skip("remote release authority integration requires POSIX bash")

    source = tmp_path / "source"
    remote = tmp_path / "origin.git"
    source.mkdir()

    def git(cwd: Path, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *args],
            cwd=cwd,
            check=True,
            capture_output=True,
            text=True,
        )

    git(source, "init")
    git(source, "branch", "-M", "main")
    git(source, "config", "user.name", "Hermes Release Test")
    git(source, "config", "user.email", "hermes-release-test@example.invalid")
    (source / "source.txt").write_text("one\n", encoding="utf-8")
    git(source, "add", "source.txt")
    git(source, "commit", "-m", "source one")
    release_commit = git(source, "rev-parse", "HEAD^{commit}").stdout.strip()
    release_tag = "v0.13.151"
    git(source, "tag", "-a", release_tag, "-m", "Hermes release one")
    initial_tag_object = git(source, "rev-parse", f"refs/tags/{release_tag}").stdout.strip()

    git(tmp_path, "init", "--bare", str(remote))
    git(source, "remote", "add", "origin", str(remote))
    git(source, "push", "origin", "main", f"refs/tags/{release_tag}")

    script = REPO_ROOT / "scripts/verify_android_remote_release_authority.sh"

    def authority(tag_object: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "HERMES_RELEASE_TAG": release_tag,
                "DEFAULT_BRANCH": "main",
                "EXPECTED_RELEASE_COMMIT": release_commit,
                "EXPECTED_TAG_OBJECT_SHA": tag_object,
            }
        )
        return subprocess.run(
            ["bash", str(script)],
            cwd=source,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

    assert authority(initial_tag_object).returncode == 0

    git(source, "tag", "-d", release_tag)
    git(source, "tag", release_tag, release_commit)
    git(source, "push", "--force", "origin", f"refs/tags/{release_tag}")
    assert authority(initial_tag_object).returncode != 0

    git(source, "tag", "-d", release_tag)
    git(source, "tag", "-a", release_tag, "-m", "Hermes release moved", release_commit)
    moved_tag_object = git(source, "rev-parse", f"refs/tags/{release_tag}").stdout.strip()
    assert moved_tag_object != initial_tag_object
    git(source, "push", "--force", "origin", f"refs/tags/{release_tag}")
    assert authority(initial_tag_object).returncode != 0

    (source / "source.txt").write_text("two\n", encoding="utf-8")
    git(source, "add", "source.txt")
    git(source, "commit", "-m", "source two")
    git(source, "push", "origin", "main")
    git(source, "checkout", "--detach", release_commit)
    assert authority(moved_tag_object).returncode != 0


def test_android_release_manifest_prefers_universal_apk_over_newer_split(tmp_path):
    manifest = _load_android_release_manifest_module()
    apk_dir = tmp_path / "release"
    apk_dir.mkdir()

    universal = apk_dir / "app-universal-release.apk"
    split = apk_dir / "app-x86_64-release.apk"
    universal.write_bytes(b"universal")
    split.write_bytes(b"x86 only")

    split_mtime = universal.stat().st_mtime + 30
    os.utime(split, (split_mtime, split_mtime))

    assert manifest.select_release_apk(apk_dir) == universal


def test_android_release_manifest_rejects_ambiguous_split_only_apks(tmp_path):
    manifest = _load_android_release_manifest_module()
    apk_dir = tmp_path / "release"
    apk_dir.mkdir()

    (apk_dir / "app-arm64-v8a-release.apk").write_bytes(b"arm64")
    (apk_dir / "app-x86_64-release.apk").write_bytes(b"x86")

    with pytest.raises(ValueError, match="no universal APK"):
        manifest.select_release_apk(apk_dir)


def test_android_release_manifest_ignores_unsigned_companion_apk(tmp_path):
    manifest = _load_android_release_manifest_module()
    apk_dir = tmp_path / "release"
    apk_dir.mkdir()

    signed = apk_dir / "app-release.apk"
    unsigned = apk_dir / "app-release-unsigned.apk"
    signed.write_bytes(b"signed")
    unsigned.write_bytes(b"unsigned")

    unsigned_mtime = signed.stat().st_mtime + 30
    os.utime(unsigned, (unsigned_mtime, unsigned_mtime))

    assert manifest.select_release_apk(apk_dir) == signed
