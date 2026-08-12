from pathlib import Path
import importlib.util
import os
import re
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
    assert 'scripts/android_release_manifest.py --tag' in workflow
    assert "tags:\n      - 'v0.*'" in workflow
    assert "- 'v*'" not in workflow
    assert "release:\n    types:" not in workflow
    assert 'group: android-release-${{ github.ref_name }}' in workflow
    assert 'HERMES_RELEASE_TAG: ${{ github.ref_name }}' in workflow
    assert 'gh release upload "$HERMES_RELEASE_TAG"' in workflow
    assert 'gh release create "$HERMES_RELEASE_TAG"' in workflow
    assert '--draft' in workflow
    assert re.search(
        r'gh release view\s+"\$HERMES_RELEASE_TAG"\s+\\?\s*\n?\s*--json assets',
        workflow,
    )
    assert 'gh release edit "$HERMES_RELEASE_TAG" --draft=false --latest' in workflow
    assert 'GH_TOKEN: ${{ github.token }}' in workflow


def test_android_push_workflow_uses_node24_ready_action_versions():
    workflow = (REPO_ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")

    _assert_external_actions_are_commit_pinned(workflow)
    assert 'python -m venv .venv' in workflow
    assert 'bash scripts/run_tests.sh' in workflow
    assert 'python -m pytest' not in workflow


def test_android_push_workflow_compiles_android_test_sources():
    workflow = (REPO_ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")

    assert './gradlew :app:compileDebugAndroidTestKotlin -PskipHermesAndroidLinuxAssets=true' in workflow


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
