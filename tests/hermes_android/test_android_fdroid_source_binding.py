import importlib.util
import re
import subprocess
import sys
from pathlib import Path

import pytest
import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
VERSION_NAME = "0.13.150"
VERSION_CODE = "145090"
RESOLVED_RELEASE_COMMIT = "a" * 40
GRADLE_RELATIVE = Path("android/app/build.gradle.kts")
RELEASE_TAG_EXPRESSION = 'System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()'
BUILD_PYTHON_EXPRESSION = (
    'return if (osName.contains("windows")) "python" else "python3"'
)
BUILD_PYTHON_REPLACEMENT = (
    'return if (osName.contains("windows")) "python" else "python3.13"'
)


def _autoupdater_metadata(target_count: int = 1) -> str:
    historical = """\
  - versionName: 0.13.147
    versionCode: 144790
    commit: 1471471471471471471471471471471471471471
    subdir: android/app
    gradle:
      - yes
    prebuild:
      - echo historical-recipe-must-remain-byte-identical
"""
    target = f"""\
  - versionName: {VERSION_NAME}
    versionCode: {VERSION_CODE}
    commit: {RESOLVED_RELEASE_COMMIT}
    subdir: android/app
    sudo:
      - apt-get update
      - apt-get install -y python3-pip
    gradle:
      - yes
    prebuild:
      - sed -i -e 's/System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()/"v$$VERSION$$"/'
        build.gradle.kts
      - sed -i -e 's/return if (osName.contains("windows")) "python" else "python3"/return
        if (osName.contains("windows")) "python" else "python3.13"/' build.gradle.kts
    antifeatures:
      - UpstreamNonFree
"""
    return f"""\
# local autoupdater preview; never commit or push
AntiFeatures:
  NonFreeNet: optional providers
Builds:
{historical}{target * target_count}
AllowedAPKSigningKeys: keep-this-live-value
AutoUpdateMode: Version
CurrentVersion: {VERSION_NAME}
CurrentVersionCode: {VERSION_CODE}
MaintainerNotes: preserve this unrelated live metadata exactly
"""


def _load_binding_module():
    script = REPO_ROOT / "scripts/android_fdroid_source_binding.py"
    spec = importlib.util.spec_from_file_location("android_fdroid_source_binding", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", *args],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )


@pytest.fixture
def source_checkout(tmp_path: Path) -> Path:
    repo = tmp_path / "source"
    (repo / GRADLE_RELATIVE).parent.mkdir(parents=True)
    (repo / "android/macrobenchmark").mkdir()
    (repo / "fdroid").mkdir()
    (repo / GRADLE_RELATIVE).write_bytes(
        "\n".join(
            [
                'val releaseTag = System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()',
                "fun resolvedBuildPython(): String {",
                '    val osName = System.getProperty("os.name").lowercase()',
                '    return if (osName.contains("windows")) "python" else "python3"',
                "}",
                "android {",
                "    signingConfigs {",
                '        create("release") {',
                '            storeFile = file("release.keystore")',
                "        }",
                "    }",
                "    buildTypes {",
                "        release {",
                '            signingConfig = signingConfigs.getByName("release")',
                "        }",
                "    }",
                "}",
                "",
            ]
        ).encode("utf-8")
    )
    (repo / "android/macrobenchmark/build.gradle.kts").write_bytes(
        "\n".join(
            [
                "android {",
                "    buildTypes {",
                '        create("benchmark") {',
                '            signingConfig = signingConfigs.getByName("debug")',
                "        }",
                "    }",
                "}",
                "",
            ]
        ).encode("utf-8")
    )
    (repo / "android/settings.gradle.kts").write_text("rootProject.name = \"fixture\"\n")
    (repo / "android/gradle/wrapper").mkdir(parents=True)
    (repo / "android/gradle/wrapper/gradle-wrapper.jar").write_bytes(
        b"fixture Gradle wrapper JAR bytes\n"
    )
    (repo / "android/gradle/gradle-daemon-jvm.properties").write_text(
        "toolchainVersion=21\n",
        encoding="utf-8",
    )
    (repo / "android/gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
    (repo / "android/gradlew.bat").write_text("@echo off\r\n", encoding="utf-8")
    (repo / ".gitignore").write_text("android/local.properties\n", encoding="utf-8")
    (repo / "fdroid/com.mobilefork.hermesagent.version").write_text(
        f"versionName={VERSION_NAME}\nversionCode={VERSION_CODE}\n",
        encoding="utf-8",
    )
    (repo / "fdroid/com.mobilefork.hermesagent.yml.template").write_text(
        f"CurrentVersion: {VERSION_NAME}\nCurrentVersionCode: {VERSION_CODE}\n",
        encoding="utf-8",
    )
    (repo / "tracked.txt").write_text("committed\n", encoding="utf-8")
    _git(repo, "init", "-q")
    _git(repo, "config", "user.name", "Hermes test")
    _git(repo, "config", "user.email", "hermes-test@example.invalid")
    _git(repo, "config", "core.autocrlf", "false")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", "fixture")
    return repo


def _apply_fdroid_buildserver_preparation(repo: Path) -> None:
    properties = b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\n"
    for relative in (
        Path("local.properties"),
        Path("android/local.properties"),
        Path("android/app/local.properties"),
    ):
        path = repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(properties)

    gradle = repo / GRADLE_RELATIVE
    source = gradle.read_bytes().decode("utf-8")
    signing_block = "\n".join(
        [
            "    signingConfigs {",
            '        create("release") {',
            '            storeFile = file("release.keystore")',
            "        }",
            "    }",
            "",
        ]
    )
    assert source.count(signing_block) == 1
    source = source.replace(signing_block, "", 1)
    source = "".join(
        line
        for line in source.splitlines(keepends=True)
        if "signingConfig = signingConfigs.getByName" not in line
    )
    gradle.write_bytes(source.encode("utf-8"))

    macrobenchmark = repo / "android/macrobenchmark/build.gradle.kts"
    macro_source = macrobenchmark.read_bytes().decode("utf-8")
    macro_source = "".join(
        line
        for line in macro_source.splitlines(keepends=True)
        if "signingConfig = signingConfigs.getByName" not in line
    )
    macrobenchmark.write_bytes(macro_source.encode("utf-8"))


def _apply_declared_fdroid_transform(repo: Path) -> None:
    gradle = repo / GRADLE_RELATIVE
    source = gradle.read_bytes().decode("utf-8")
    assert source.count(RELEASE_TAG_EXPRESSION) == 1
    assert source.count(BUILD_PYTHON_EXPRESSION) == 1
    source = source.replace(RELEASE_TAG_EXPRESSION, f'"v{VERSION_NAME}"', 1)
    source = source.replace(BUILD_PYTHON_EXPRESSION, BUILD_PYTHON_REPLACEMENT, 1)
    gradle.write_bytes(source.encode("utf-8"))


def _apply_fdroid_post_prebuild_cleanup(repo: Path) -> None:
    (repo / "android/gradle/wrapper/gradle-wrapper.jar").unlink()
    (repo / "android/gradle/gradle-daemon-jvm.properties").unlink()
    (repo / "android/gradlew").unlink()
    (repo / "android/gradlew.bat").unlink()


def test_clean_prepare_and_exact_prebuild_resolve_the_committed_github_digest(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME

    _apply_fdroid_buildserver_preparation(source_checkout)
    prepared = binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    committed_identity = binding_module.git_source_tree_identity(source_checkout)
    assert prepared.source_digest == committed_identity.digest
    assert binding_file.is_file()

    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    verified = binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)

    assert verified == prepared
    assert verified.source_digest == committed_identity.digest
    assert "unbound" not in binding_file.read_text(encoding="ascii")


def test_prepare_rejects_a_checkout_already_changed_by_prebuild(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="pre-metadata-prebuild source .* does not match",
    ):
        binding_module.prepare_binding(
            source_checkout,
            tmp_path / binding_module.BINDING_FILE_NAME,
            VERSION_NAME,
        )


def test_prepare_rejects_scanner_cleanup_before_the_handoff(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    (source_checkout / "android/gradle/wrapper/gradle-wrapper.jar").unlink()

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="pre-metadata-prebuild tracked-source changes",
    ):
        binding_module.prepare_binding(
            source_checkout,
            tmp_path / binding_module.BINDING_FILE_NAME,
            VERSION_NAME,
        )


def test_verify_rejects_a_scanner_managed_file_left_in_the_checkout(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    (source_checkout / "android/gradle/gradle-daemon-jvm.properties").unlink()
    (source_checkout / "android/gradlew").unlink()
    (source_checkout / "android/gradlew.bat").unlink()

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="post-metadata-prebuild tracked-source changes",
    ):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


def test_prepare_rejects_missing_buildserver_sdk_and_signing_preparation(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="buildserver-generated untracked SDK locators",
    ):
        binding_module.prepare_binding(
            source_checkout,
            tmp_path / binding_module.BINDING_FILE_NAME,
            VERSION_NAME,
        )


def test_verify_rejects_a_missing_prebuild_handoff(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)

    with pytest.raises(binding_module.FdroidSourceBindingError, match="binding file is missing"):
        binding_module.verify_binding(
            source_checkout,
            tmp_path / binding_module.BINDING_FILE_NAME,
            VERSION_NAME,
        )


@pytest.mark.parametrize(
    "tamper",
    ["extra-source", "extra-deletion", "gradle-transform", "binding-digest"],
)
def test_verify_rejects_every_change_outside_the_closed_fdroid_contract(
    source_checkout: Path,
    tmp_path: Path,
    tamper: str,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)

    expected_message = ""
    if tamper == "extra-source":
        (source_checkout / "tracked.txt").write_text("tampered\n", encoding="utf-8")
        expected_message = "post-metadata-prebuild tracked-source changes"
    elif tamper == "extra-deletion":
        (source_checkout / "tracked.txt").unlink()
        expected_message = "post-metadata-prebuild tracked-source changes"
    elif tamper == "gradle-transform":
        with (source_checkout / GRADLE_RELATIVE).open("a", encoding="utf-8") as stream:
            stream.write("// undeclared edit\n")
        expected_message = "does not match the exact declared transformation"
    else:
        text = binding_file.read_text(encoding="ascii")
        text = text.replace(
            f"sourceDigest={binding_module.git_source_tree_identity(source_checkout).digest}",
            f"sourceDigest={'b' * 64}",
        )
        binding_file.write_text(text, encoding="ascii")
        expected_message = "does not match committed"

    with pytest.raises(binding_module.FdroidSourceBindingError, match=expected_message):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


def test_fdroid_metadata_and_gradle_wire_prepare_before_sed_and_verify_afterward():
    metadata = yaml.safe_load(
        (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template").read_text(
            encoding="utf-8"
        )
    )
    build = metadata["Builds"][0]
    prebuild = build["prebuild"]
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")

    assert build["gradleprops"] == ["hermesFdroidSourceBinding=true"]
    assert build["ndk"] == "29.0.14206865"
    assert 'sdkmanager "cmake;3.31.6"' in build["sudo"]
    assert "android_fdroid_source_binding.py prepare" in prebuild[0]
    assert "${GRADLE_USER_HOME:-$HOME/.gradle}" in prebuild[0]
    assert RELEASE_TAG_EXPRESSION in prebuild[1]
    assert BUILD_PYTHON_EXPRESSION in prebuild[2]
    assert "providers.gradleProperty(\"hermesFdroidSourceBinding\")" in gradle
    assert "android_fdroid_source_binding.py" in gradle
    assert '"verify"' in gradle
    assert '"--binding-file"' in gradle
    assert '"--require-clean"' in gradle
    assert "mutually exclusive authorities" in gradle


def test_autoupdater_preview_rejects_the_prior_two_sed_recipe(tmp_path: Path):
    binding_module = _load_binding_module()
    metadata = tmp_path / "com.mobilefork.hermesagent.yml"
    metadata.write_text(_autoupdater_metadata(), encoding="utf-8")

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="sudo does not match the v0.13.150 source-binding template",
    ):
        binding_module.verify_autoupdate_metadata_preview(
            metadata,
            REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template",
            VERSION_NAME,
            VERSION_CODE,
        )


def test_autoupdater_preview_render_preserves_commit_history_and_unrelated_metadata(
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    metadata = tmp_path / "com.mobilefork.hermesagent.yml"
    template = REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template"
    original_text = _autoupdater_metadata()
    metadata.write_text(original_text, encoding="utf-8")
    before = yaml.safe_load(original_text)

    rendered = binding_module.render_autoupdate_metadata_preview(
        metadata,
        template,
        VERSION_NAME,
        VERSION_CODE,
    )
    verified = binding_module.verify_autoupdate_metadata_preview(
        metadata,
        template,
        VERSION_NAME,
        VERSION_CODE,
    )
    rendered_text = metadata.read_text(encoding="utf-8")
    after = yaml.safe_load(rendered_text)
    template_build = yaml.safe_load(template.read_text(encoding="utf-8"))["Builds"][0]

    assert rendered == verified
    assert rendered.commit == RESOLVED_RELEASE_COMMIT
    assert after["Builds"][0] == before["Builds"][0]
    assert after["AntiFeatures"] == before["AntiFeatures"]
    assert after["AllowedAPKSigningKeys"] == before["AllowedAPKSigningKeys"]
    assert after["AutoUpdateMode"] == before["AutoUpdateMode"]
    assert after["MaintainerNotes"] == before["MaintainerNotes"]

    before_target = dict(before["Builds"][1])
    after_target = dict(after["Builds"][1])
    for field_name in ("sudo", "ndk", "gradle", "gradleprops", "prebuild"):
        assert after_target.pop(field_name) == template_build[field_name]
        before_target.pop(field_name, None)
    assert after_target == before_target
    assert "historical-recipe-must-remain-byte-identical" in rendered_text
    assert "preserve this unrelated live metadata exactly" in rendered_text
    assert "android_fdroid_source_binding.py prepare" in rendered_text
    assert "hermesFdroidSourceBinding=true" in rendered_text
    assert 'sdkmanager "cmake;3.31.6"' in rendered_text
    assert "    ndk: 29.0.14206865" in rendered_text
    assert "unbound" not in "\n".join(template_build["prebuild"]).lower()


@pytest.mark.parametrize("target_count", [0, 2])
def test_autoupdater_preview_render_rejects_missing_or_duplicate_target_builds(
    tmp_path: Path,
    target_count: int,
):
    binding_module = _load_binding_module()
    metadata = tmp_path / "com.mobilefork.hermesagent.yml"
    original = _autoupdater_metadata(target_count)
    metadata.write_text(original, encoding="utf-8")

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match=rf"exactly one {re.escape(VERSION_NAME)}/{VERSION_CODE} build, found {target_count}",
    ):
        binding_module.render_autoupdate_metadata_preview(
            metadata,
            REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template",
            VERSION_NAME,
            VERSION_CODE,
        )
    assert metadata.read_text(encoding="utf-8") == original


def test_autoupdater_preview_rejects_a_template_that_can_emit_unbound(tmp_path: Path):
    binding_module = _load_binding_module()
    metadata = tmp_path / "com.mobilefork.hermesagent.yml"
    template = tmp_path / "template.yml"
    original = _autoupdater_metadata()
    metadata.write_text(original, encoding="utf-8")
    template.write_text(
        (
            REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template"
        ).read_text(encoding="utf-8").replace(
            "      - python3.13 ../../scripts/android_fdroid_source_binding.py prepare",
            "      - echo unbound\n"
            "      - python3.13 ../../scripts/android_fdroid_source_binding.py prepare",
            1,
        ),
        encoding="utf-8",
    )

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="prebuild does not match the exact source-binding contract",
    ):
        binding_module.render_autoupdate_metadata_preview(
            metadata,
            template,
            VERSION_NAME,
            VERSION_CODE,
        )
    assert metadata.read_text(encoding="utf-8") == original
