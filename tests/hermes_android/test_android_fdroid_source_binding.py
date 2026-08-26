import hashlib
import importlib.util
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
VERSION_NAME = "0.13.153"
VERSION_CODE = "145390"
RESOLVED_RELEASE_COMMIT = "a" * 40
GRADLE_RELATIVE = Path("android/app/build.gradle.kts")
RELEASE_TAG_EXPRESSION = 'System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()'
BUILD_PYTHON_EXPRESSION = (
    'return if (osName.contains("windows")) "python" else "python3"'
)
BUILD_PYTHON_REPLACEMENT = (
    'return if (osName.contains("windows")) "python" else "python3.13"'
)
FDROID_LOCAL_PROPERTIES_PAYLOAD = (
    b"sdk.dir=/opt/android-sdk\n"
    b"sdk-location=/opt/android-sdk\n"
    b"ndk.dir=/opt/android-sdk/ndk/29.0.14206865\n"
    b"ndk-location=/opt/android-sdk/ndk/29.0.14206865\n"
)
FDROID_CHAQUOPY_PROGUARD_PAYLOAD = (
    b"# Ensure all classes and methods used by Cython code are left alone by minifyEnabled.\n"
    b"-keep class com.chaquo.python.** { * ; }\n"
    b"\n"
    b"# See get_sam in class.pxi.\n"
    b"-keep class kotlin.jvm.functions.** { * ; }\n"
    b"-keep class kotlin.jvm.internal.FunctionBase { * ; }\n"
    b"-keep class kotlin.reflect.KAnnotatedElement { *; }\n"
    b"\n"
    b"# TODO: https://github.com/chaquo/chaquopy/issues/842\n"
    b"-dontwarn org.jetbrains.annotations.NotNull\n"
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
      - apt-get install -y g++ python3-pip
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
    (repo / ".gitignore").write_text(
        "android/local.properties\nandroid/app/build/\n__pycache__/\n",
        encoding="utf-8",
    )
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
    properties = FDROID_LOCAL_PROPERTIES_PAYLOAD
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


def _write_fdroid_local_properties(repo: Path, payload: bytes) -> None:
    for relative in (
        Path("local.properties"),
        Path("android/local.properties"),
        Path("android/app/local.properties"),
    ):
        (repo / relative).write_bytes(payload)


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
    proguard = repo / "android/app/build/python/proguard-rules.pro"
    proguard.parent.mkdir(parents=True, exist_ok=True)
    proguard.write_bytes(FDROID_CHAQUOPY_PROGUARD_PAYLOAD)


def _accept_fixture_remote_tag(binding_module, monkeypatch, repo: Path) -> None:
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    monkeypatch.setattr(
        binding_module,
        "_assert_remote_release_tag_authority",
        lambda _repo, _version: commit,
    )


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
    assert (
        binding_module.FDROID_CHAQUOPY_PROGUARD_PATH
        not in binding_module._untracked_paths(source_checkout)
    )
    assert (
        binding_module.FDROID_CHAQUOPY_PROGUARD_PATH
        in binding_module._all_untracked_paths(source_checkout)
    )
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
    proguard = source_checkout / "android/app/build/python/proguard-rules.pro"
    proguard.parent.mkdir(parents=True, exist_ok=True)
    proguard.write_bytes(FDROID_CHAQUOPY_PROGUARD_PAYLOAD)

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


def test_fdroid_local_properties_accepts_exact_pinned_sdk_ndk_payload(
    source_checkout: Path,
):
    binding_module = _load_binding_module()
    assert len(FDROID_LOCAL_PROPERTIES_PAYLOAD) == 146
    assert hashlib.sha256(FDROID_LOCAL_PROPERTIES_PAYLOAD).hexdigest() == (
        "6ce9884ec454393dcdd094805065e31adda2e11650dd59d71087c9e0f608660f"
    )
    assert binding_module.FDROID_LOCAL_PROPERTIES_PAYLOAD == FDROID_LOCAL_PROPERTIES_PAYLOAD
    _apply_fdroid_buildserver_preparation(source_checkout)

    binding_module._validate_fdroid_local_properties(source_checkout)


@pytest.mark.parametrize(
    "payload",
    [
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\nndk-location=/opt/android-sdk/ndk/29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\nndk.dir=/opt/android-sdk/ndk/29.0.14206865\n",
        b"sdk-location=/opt/android-sdk\nsdk.dir=/opt/android-sdk\nndk.dir=/opt/android-sdk/ndk/29.0.14206865\nndk-location=/opt/android-sdk/ndk/29.0.14206865\n",
        FDROID_LOCAL_PROPERTIES_PAYLOAD + b"unexpected.key=value\n",
        FDROID_LOCAL_PROPERTIES_PAYLOAD + b"ndk.dir=/opt/android-sdk/ndk/29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/different-sdk\nndk.dir=/opt/android-sdk/ndk/29.0.14206865\nndk-location=/opt/android-sdk/ndk/29.0.14206865\n",
        b"sdk.dir=opt/android-sdk\nsdk-location=opt/android-sdk\nndk.dir=opt/android-sdk/ndk/29.0.14206865\nndk-location=opt/android-sdk/ndk/29.0.14206865\n",
        b"sdk.dir=/tmp/android-sdk\nsdk-location=/tmp/android-sdk\nndk.dir=/tmp/android-sdk/ndk/29.0.14206865\nndk-location=/tmp/android-sdk/ndk/29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk-evil\nsdk-location=/opt/android-sdk-evil\nndk.dir=/opt/android-sdk-evil/ndk/29.0.14206865\nndk-location=/opt/android-sdk-evil/ndk/29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk/\nsdk-location=/opt/android-sdk/\nndk.dir=/opt/android-sdk//ndk/29.0.14206865\nndk-location=/opt/android-sdk//ndk/29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk/../other\nsdk-location=/opt/android-sdk/../other\nndk.dir=/opt/android-sdk/../other/ndk/29.0.14206865\nndk-location=/opt/android-sdk/../other/ndk/29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\nndk.dir=/opt/android-sdk/ndk/29.0.14206865\nndk-location=/different-ndk\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\nndk.dir=/opt/android-sdk/ndk/../29.0.14206865\nndk-location=/opt/android-sdk/ndk/../29.0.14206865\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\nndk.dir=/opt/android-sdk/ndk/28.0.13004108\nndk-location=/opt/android-sdk/ndk/28.0.13004108\n",
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\nndk.dir=/opt/other/ndk/29.0.14206865\nndk-location=/opt/other/ndk/29.0.14206865\n",
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"\n", b"\r\n"),
        FDROID_LOCAL_PROPERTIES_PAYLOAD.rstrip(b"\n"),
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"sdk.dir=", b"sdk.dir =", 1),
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"sdk-location=", b"\nsdk-location=", 1),
        b"# generated\n" + FDROID_LOCAL_PROPERTIES_PAYLOAD,
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(
            b"sdk.dir=/opt/android-sdk\n", b"sdk.dir=/opt/android-\\\nsdk\n", 1
        ),
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"android-sdk", b"android\\u002dsdk"),
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"\n", b"\n\n", 1),
        b"\xef\xbb\xbf" + FDROID_LOCAL_PROPERTIES_PAYLOAD,
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"android-sdk", b"android\x00-sdk", 1),
        FDROID_LOCAL_PROPERTIES_PAYLOAD.replace(b"/opt/android-sdk", b"/opt/andr\xc3\xb6id-sdk"),
    ],
    ids=[
        "old-sdk-only-payload",
        "missing-ndk-dir",
        "missing-ndk-location",
        "reordered-lines",
        "extra-key",
        "duplicate-key",
        "mismatched-sdk-values",
        "relative-paths",
        "alternate-sdk-root",
        "sdk-prefix-root",
        "trailing-slash",
        "sdk-traversal",
        "mismatched-ndk-values",
        "ndk-traversal",
        "wrong-ndk-version",
        "wrong-ndk-path",
        "crlf",
        "missing-final-lf",
        "whitespace",
        "blank-line",
        "comment",
        "continuation",
        "escaped-unicode-separator",
        "double-lf",
        "bom",
        "embedded-nul",
        "non-ascii",
    ],
)
def test_fdroid_local_properties_rejects_noncanonical_payloads(
    source_checkout: Path,
    payload: bytes,
):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    _write_fdroid_local_properties(source_checkout, payload)

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="exact pinned buildserver payload",
    ):
        binding_module._validate_fdroid_local_properties(source_checkout)


def test_fdroid_local_properties_rejects_nonidentical_files(source_checkout: Path):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    (source_checkout / "android/app/local.properties").write_bytes(
        FDROID_LOCAL_PROPERTIES_PAYLOAD + b"unexpected.key=value\n"
    )

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="files are not identical",
    ):
        binding_module._validate_fdroid_local_properties(source_checkout)


def test_fdroid_local_properties_rejects_symlink(source_checkout: Path):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    locator = source_checkout / "local.properties"
    locator.unlink()
    locator.symlink_to(Path("android/local.properties"))

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="must be an ordinary file",
    ):
        binding_module._validate_fdroid_local_properties(source_checkout)


def test_fdroid_local_properties_rejects_missing_locator(source_checkout: Path):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    (source_checkout / "android/local.properties").unlink()

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="must be an ordinary file",
    ):
        binding_module._validate_fdroid_local_properties(source_checkout)


def test_fdroid_local_properties_rejects_directory_locator(source_checkout: Path):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    locator = source_checkout / "android/local.properties"
    locator.unlink()
    locator.mkdir()

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="must be an ordinary file",
    ):
        binding_module._validate_fdroid_local_properties(source_checkout)


def test_fdroid_local_properties_rejects_additional_untracked_path(source_checkout: Path):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    (source_checkout / "unexpected.txt").write_text("unexpected\n", encoding="ascii")

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="buildserver-generated untracked SDK locators",
    ):
        binding_module._validate_fdroid_local_properties(source_checkout)


def test_verify_rejects_locator_mutation_after_prepare(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    _write_fdroid_local_properties(
        source_checkout,
        b"sdk.dir=/opt/android-sdk\nsdk-location=/opt/android-sdk\n",
    )

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="exact pinned buildserver payload",
    ):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


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


def test_verify_accepts_exact_required_chaquopy_proguard_output(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    assert len(FDROID_CHAQUOPY_PROGUARD_PAYLOAD) == 404
    assert hashlib.sha256(FDROID_CHAQUOPY_PROGUARD_PAYLOAD).hexdigest() == (
        "a7dbf6d6d1fbbbbf3b80ed835927e3b13432aa886437b8d5b91eb0edae096b6d"
    )
    assert (
        binding_module.FDROID_CHAQUOPY_PROGUARD_PAYLOAD
        == FDROID_CHAQUOPY_PROGUARD_PAYLOAD
    )
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    prepared = binding_module.prepare_binding(
        source_checkout, binding_file, VERSION_NAME
    )
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)

    assert binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME) == prepared


def test_verify_transformed_accepts_exact_required_chaquopy_proguard_output(
    source_checkout: Path,
    monkeypatch,
):
    binding_module = _load_binding_module()
    _accept_fixture_remote_tag(binding_module, monkeypatch, source_checkout)
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)

    binding_module.verify_transformed_binding(source_checkout, VERSION_NAME)


@pytest.mark.parametrize(
    "mutation",
    [
        FDROID_CHAQUOPY_PROGUARD_PAYLOAD + b"# tampered\n",
        FDROID_CHAQUOPY_PROGUARD_PAYLOAD.replace(b"NotNull", b"Nullable", 1),
    ],
    ids=["extra-content", "changed-rule"],
)
def test_verify_rejects_tampered_chaquopy_proguard_output(
    source_checkout: Path,
    tmp_path: Path,
    mutation: bytes,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    (source_checkout / "android/app/build/python/proguard-rules.pro").write_bytes(mutation)

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="does not match the exact generated payload",
    ):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


def test_verify_rejects_missing_chaquopy_proguard_output(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    (source_checkout / "android/app/build/python/proguard-rules.pro").unlink()

    with pytest.raises(binding_module.FdroidSourceBindingError, match="Chaquopy ProGuard"):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


@pytest.mark.parametrize("replacement", ["symlink", "directory"])
def test_verify_rejects_nonordinary_chaquopy_proguard_output(
    source_checkout: Path,
    tmp_path: Path,
    replacement: str,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    proguard = source_checkout / "android/app/build/python/proguard-rules.pro"
    proguard.unlink()
    if replacement == "symlink":
        proguard.symlink_to(source_checkout / "tracked.txt")
    else:
        proguard.mkdir()

    with pytest.raises(binding_module.FdroidSourceBindingError, match="Chaquopy ProGuard"):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


def test_verify_rejects_extra_chaquopy_build_output_sibling(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    (source_checkout / "android/app/build/python/unexpected.txt").write_text(
        "unexpected\n", encoding="ascii"
    )

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="untracked or ignored build input",
    ):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


@pytest.mark.parametrize(
    "pyc_name",
    [
        "android_release_evidence.cpython-313.pyc",
        "check_android_release_identity.cpython-313.pyc",
    ],
)
def test_verify_rejects_preexisting_python_bytecode(
    source_checkout: Path,
    tmp_path: Path,
    pyc_name: str,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    pycache = source_checkout / "scripts/__pycache__"
    pycache.mkdir(parents=True)
    (pycache / pyc_name).write_bytes(b"forbidden bytecode")
    pyc_path = binding_module.PurePosixPath("scripts/__pycache__") / pyc_name
    assert pyc_path not in binding_module._untracked_paths(source_checkout)
    assert pyc_path in binding_module._all_untracked_paths(source_checkout)

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="untracked or ignored build input",
    ):
        binding_module.verify_binding(source_checkout, binding_file, VERSION_NAME)


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="POSIX FIFO support required")
def test_fifo_proguard_rejection_does_not_block(tmp_path: Path):
    fifo = tmp_path / "proguard-rules.pro"
    os.mkfifo(fifo)
    command = """
import importlib.util
import pathlib
import sys
spec = importlib.util.spec_from_file_location("binding", sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
module._read_exact_regular_file_without_following(pathlib.Path(sys.argv[2]), "FIFO")
"""

    completed = subprocess.run(
        [
            sys.executable,
            "-c",
            command,
            str(REPO_ROOT / "scripts/android_fdroid_source_binding.py"),
            str(fifo),
        ],
        capture_output=True,
        text=True,
        timeout=3,
    )

    assert completed.returncode != 0
    assert "must be an ordinary non-symlink file" in completed.stderr


def test_prepare_rejects_preexisting_chaquopy_proguard_output(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    _apply_fdroid_buildserver_preparation(source_checkout)
    proguard = source_checkout / "android/app/build/python/proguard-rules.pro"
    proguard.parent.mkdir(parents=True, exist_ok=True)
    proguard.write_bytes(FDROID_CHAQUOPY_PROGUARD_PAYLOAD)

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="untracked or ignored build input",
    ):
        binding_module.prepare_binding(
            source_checkout,
            tmp_path / binding_module.BINDING_FILE_NAME,
            VERSION_NAME,
        )


def test_dynamic_local_imports_do_not_emit_python_bytecode(
    source_checkout: Path,
    tmp_path: Path,
):
    isolated_scripts = tmp_path / "isolated-scripts"
    isolated_scripts.mkdir()
    for name in (
        "android_fdroid_source_binding.py",
        "android_release_evidence.py",
        "check_android_release_identity.py",
    ):
        shutil.copyfile(REPO_ROOT / "scripts" / name, isolated_scripts / name)
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_file = tmp_path / "isolated-gradle-home" / "binding.properties"
    environment = dict(os.environ)
    environment.pop("PYTHONDONTWRITEBYTECODE", None)

    subprocess.run(
        [
            sys.executable,
            str(isolated_scripts / "android_fdroid_source_binding.py"),
            "prepare",
            "--repo-root",
            str(source_checkout),
            "--binding-file",
            str(binding_file),
            "--version",
            VERSION_NAME,
        ],
        check=True,
        env=environment,
    )

    assert not (isolated_scripts / "__pycache__").exists()


def test_exact_transformed_checkout_self_binds_without_prebuild_handoff(
    source_checkout: Path,
    monkeypatch,
):
    binding_module = _load_binding_module()
    _accept_fixture_remote_tag(binding_module, monkeypatch, source_checkout)
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)

    verified = binding_module.verify_transformed_binding(source_checkout, VERSION_NAME)
    committed_identity = binding_module.git_source_tree_identity(source_checkout)

    assert verified.source_digest == committed_identity.digest
    assert verified.source_files == committed_identity.file_count
    assert verified.commit == subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=source_checkout,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def test_transformed_checkout_self_binding_rejects_extra_source_change(
    source_checkout: Path,
    monkeypatch,
):
    binding_module = _load_binding_module()
    _accept_fixture_remote_tag(binding_module, monkeypatch, source_checkout)
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    (source_checkout / "tracked.txt").write_text("tampered\n", encoding="utf-8")

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="post-metadata-prebuild tracked-source changes",
    ):
        binding_module.verify_transformed_binding(source_checkout, VERSION_NAME)


def test_transformed_checkout_rejects_assume_unchanged_index_bypass(
    source_checkout: Path,
    monkeypatch,
):
    binding_module = _load_binding_module()
    _accept_fixture_remote_tag(binding_module, monkeypatch, source_checkout)
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    (source_checkout / "tracked.txt").write_text("hidden tamper\n", encoding="utf-8")
    _git(source_checkout, "update-index", "--assume-unchanged", "tracked.txt")

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="non-default Git index flags",
    ):
        binding_module.verify_transformed_binding(source_checkout, VERSION_NAME)


def test_transformed_checkout_rejects_info_excluded_untracked_source(
    source_checkout: Path,
    monkeypatch,
):
    binding_module = _load_binding_module()
    _accept_fixture_remote_tag(binding_module, monkeypatch, source_checkout)
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    hidden = source_checkout / "android/app/src/main/java/Hidden.kt"
    hidden.parent.mkdir(parents=True, exist_ok=True)
    hidden.write_text("class Hidden\n", encoding="utf-8")
    (source_checkout / ".git/info/exclude").write_text(
        "android/app/src/main/java/Hidden.kt\n",
        encoding="utf-8",
    )

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="untracked or ignored build input",
    ):
        binding_module.verify_transformed_binding(source_checkout, VERSION_NAME)


def test_explicit_binding_rejects_info_excluded_untracked_source(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    hidden = source_checkout / "android/app/src/main/assets/hidden.txt"
    hidden.parent.mkdir(parents=True, exist_ok=True)
    hidden.write_text("hidden asset\n", encoding="utf-8")
    (source_checkout / ".git/info/exclude").write_text(
        "android/app/src/main/assets/hidden.txt\n",
        encoding="utf-8",
    )

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="untracked or ignored build input",
    ):
        binding_module.verify_binding(
            source_checkout,
            binding_file,
            VERSION_NAME,
        )


def test_explicit_binding_compares_raw_tracked_bytes_despite_clean_filter(
    source_checkout: Path,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    binding_file = tmp_path / "gradle-home" / binding_module.BINDING_FILE_NAME
    _apply_fdroid_buildserver_preparation(source_checkout)
    binding_module.prepare_binding(source_checkout, binding_file, VERSION_NAME)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)

    clean_filter = source_checkout / ".git/mask-clean.sh"
    clean_filter.write_text("#!/bin/sh\nprintf 'committed\\n'\n", encoding="utf-8")
    clean_filter.chmod(0o755)
    _git(source_checkout, "config", "filter.mask.clean", str(clean_filter))
    (source_checkout / ".git/info/attributes").write_text(
        "tracked.txt filter=mask\n",
        encoding="utf-8",
    )
    (source_checkout / "tracked.txt").write_text("malicious working bytes\n", encoding="utf-8")
    hidden_diff = subprocess.run(
        ["git", "diff", "--name-only", "HEAD", "--", "tracked.txt"],
        cwd=source_checkout,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert hidden_diff == ""

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="tracked bytes differ from HEAD: tracked.txt",
    ):
        binding_module.verify_binding(
            source_checkout,
            binding_file,
            VERSION_NAME,
        )


def test_transformed_checkout_ignores_hostile_git_authority_environment(
    source_checkout: Path,
    monkeypatch,
    tmp_path: Path,
):
    binding_module = _load_binding_module()
    _accept_fixture_remote_tag(binding_module, monkeypatch, source_checkout)
    _apply_fdroid_buildserver_preparation(source_checkout)
    _apply_declared_fdroid_transform(source_checkout)
    _apply_fdroid_post_prebuild_cleanup(source_checkout)
    expected_digest = binding_module.git_source_tree_identity(source_checkout).digest
    monkeypatch.setenv("GIT_DIR", str(tmp_path / "hostile-git-dir"))
    monkeypatch.setenv("GIT_WORK_TREE", str(tmp_path / "hostile-work-tree"))
    monkeypatch.setenv("GIT_INDEX_FILE", str(tmp_path / "hostile-index"))

    verified = binding_module.verify_transformed_binding(source_checkout, VERSION_NAME)

    assert verified.source_digest == expected_digest


def test_transformed_checkout_rejects_remote_tag_commit_drift(
    source_checkout: Path,
    monkeypatch,
):
    binding_module = _load_binding_module()
    head = "a" * 40
    remote_commit = "b" * 40
    tag_object = "c" * 40

    def fake_run_git(_repo, *args):
        if args == ("remote", "get-url", "origin"):
            return (binding_module.EXPECTED_REMOTE_REPOSITORY + "\n").encode()
        if args[:3] == ("ls-remote", "--tags", "origin"):
            tag_ref = f"refs/tags/v{VERSION_NAME}"
            return (
                f"{tag_object}\t{tag_ref}\n"
                f"{remote_commit}\t{tag_ref}^{{}}\n"
            ).encode()
        if args == ("rev-parse", "HEAD"):
            return (head + "\n").encode()
        raise AssertionError(args)

    monkeypatch.setattr(binding_module, "_run_git", fake_run_git)

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="does not match HEAD",
    ):
        binding_module._assert_remote_release_tag_authority(
            source_checkout,
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
    assert "apt-get install -y g++ python3-pip" in build["sudo"]
    assert 'sdkmanager "cmake;3.31.6"' in build["sudo"]
    assert "android_fdroid_source_binding.py prepare" in prebuild[0]
    assert "${GRADLE_USER_HOME:-$HOME/.gradle}" in prebuild[0]
    assert RELEASE_TAG_EXPRESSION in prebuild[1]
    assert BUILD_PYTHON_EXPRESSION in prebuild[2]
    assert "providers.gradleProperty(\"hermesFdroidSourceBinding\")" in gradle
    assert "android_fdroid_source_binding.py" in gradle
    assert '"verify"' in gradle
    assert '"verify-transformed"' in gradle
    assert '"--binding-file"' in gradle
    assert "automaticFdroidSourceBinding" in gradle
    assert "ordinaryCheckoutMarkers" in gradle
    assert "exactFdroidCheckoutMarkers" in gradle
    assert "fdroidMutationDetected" in gradle
    assert "F-Droid SDK-locator and scanner-wrapper state is partial" in gradle
    assert "semanticReleaseTag &&" in gradle
    assert "A transformed F-Droid checkout cannot disable source binding" in gradle
    assert "A release-tagged build cannot disable source binding" in gradle
    assert "_assert_remote_release_tag_authority" in (
        REPO_ROOT / "scripts/android_fdroid_source_binding.py"
    ).read_text(encoding="utf-8")
    assert '"--require-clean"' in gradle
    assert "mutually exclusive authorities" in gradle


def test_autoupdater_preview_rejects_the_prior_two_sed_recipe(tmp_path: Path):
    binding_module = _load_binding_module()
    metadata = tmp_path / "com.mobilefork.hermesagent.yml"
    metadata.write_text(_autoupdater_metadata(), encoding="utf-8")

    with pytest.raises(
        binding_module.FdroidSourceBindingError,
        match="sudo does not match the v0.13.153 source-binding template",
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
