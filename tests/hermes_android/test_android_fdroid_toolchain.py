import json
import re
import shlex
import shutil
import subprocess
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "fdroid/run-local-buildserver.sh"


def _run_helper(*args: object) -> subprocess.CompletedProcess[str]:
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("bash is required to exercise the F-Droid buildserver helper")
    return subprocess.run(
        [bash, SCRIPT.as_posix(), *(str(arg) for arg in args)],
        cwd=REPO_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )


@pytest.fixture(scope="module")
def fdroid_contract() -> dict[str, str]:
    result = _run_helper("--print-contract")
    assert result.returncode == 0, result.stderr
    contract = dict(line.split("=", 1) for line in result.stdout.splitlines() if line)
    assert {
        "BUILDSERVER_IMAGE",
        "BUILDSERVER_REVISION",
        "FDROIDSERVER_COMMIT",
        "FDROIDSERVER_ARCHIVE_URL",
        "FDROIDSERVER_ARCHIVE_SHA256",
        "FDROIDSERVER_ARCHIVE_SIZE_BYTES",
        "GRADLEW_FDROID_COMMIT",
        "GRADLE_MAX_WORKERS",
        "GRADLE_OPTS",
        "ANDROID_NDK_VERSION",
        "ANDROID_NDK_PACKAGE",
        "ANDROID_CMAKE_VERSION",
        "ANDROID_CMAKE_PACKAGE",
        "ANDROID_NINJA_VERSION",
        "VERSION_NAME",
        "VERSION_CODE",
        "SOURCE_BINDING_GRADLE_PROPERTY",
        "VAGRANT_ENV_MODE",
        "VAGRANT_ENV_REQUIRED_NAMES",
        "VAGRANT_ENV_OPTIONAL_NAMES",
    } <= contract.keys()
    return contract


def test_fdroid_helper_has_valid_bash_syntax():
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("bash is required to check the F-Droid buildserver helper")
    result = subprocess.run(
        [bash, "-n", SCRIPT.as_posix()],
        cwd=REPO_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr


def test_fdroid_toolchain_contract_is_immutable_coherent_and_parallel(fdroid_contract):
    image = fdroid_contract["BUILDSERVER_IMAGE"]
    buildserver_revision = fdroid_contract["BUILDSERVER_REVISION"]
    fdroidserver_revision = fdroid_contract["FDROIDSERVER_COMMIT"]
    archive_url = fdroid_contract["FDROIDSERVER_ARCHIVE_URL"]
    archive_sha256 = fdroid_contract["FDROIDSERVER_ARCHIVE_SHA256"]
    archive_size = fdroid_contract["FDROIDSERVER_ARCHIVE_SIZE_BYTES"]
    gradlew_revision = fdroid_contract["GRADLEW_FDROID_COMMIT"]
    workers = fdroid_contract["GRADLE_MAX_WORKERS"]

    assert re.fullmatch(
        r"registry\.gitlab\.com/fdroid/fdroidserver:buildserver-trixie@sha256:[0-9a-f]{64}",
        image,
    )
    assert re.fullmatch(r"[0-9a-f]{40}", buildserver_revision)
    assert re.fullmatch(r"[0-9a-f]{40}", fdroidserver_revision)
    assert archive_url == (
        "https://gitlab.com/fdroid/fdroidserver/-/archive/"
        f"{fdroidserver_revision}/fdroidserver-{fdroidserver_revision}.tar.gz"
    )
    assert re.fullmatch(r"[0-9a-f]{64}", archive_sha256)
    assert int(archive_size) > 0
    assert re.fullmatch(r"[0-9a-f]{40}", gradlew_revision)
    assert fdroidserver_revision == buildserver_revision
    assert workers == "12"
    assert shlex.split(fdroid_contract["GRADLE_OPTS"]) == [
        f"-Dorg.gradle.workers.max={workers}",
        "-Dorg.gradle.parallel=true",
    ]
    assert fdroid_contract["ANDROID_NDK_VERSION"] == "29.0.14206865"
    assert fdroid_contract["ANDROID_NDK_PACKAGE"] == "ndk;29.0.14206865"
    assert fdroid_contract["ANDROID_CMAKE_VERSION"] == "3.31.6"
    assert fdroid_contract["ANDROID_CMAKE_PACKAGE"] == "cmake;3.31.6"
    assert fdroid_contract["ANDROID_NINJA_VERSION"] == "1.12.1"
    assert fdroid_contract["VAGRANT_ENV_MODE"] == "env-i"
    assert fdroid_contract["VERSION_NAME"] == "0.13.153"
    assert fdroid_contract["VERSION_CODE"] == "145390"
    assert (
        fdroid_contract["SOURCE_BINDING_GRADLE_PROPERTY"]
        == "hermesFdroidSourceBinding=true"
    )
    assert set(fdroid_contract["VAGRANT_ENV_REQUIRED_NAMES"].split(",")) == {
        "PATH",
        "PYTHONPATH",
        "PYTHONUNBUFFERED",
        "HOME",
        "GRADLE_USER_HOME",
        "GRADLE_OPTS",
        "TERM",
        "LC_ALL",
        "LANG",
        "ANDROID_HOME",
    }
    assert set(fdroid_contract["VAGRANT_ENV_OPTIONAL_NAMES"].split(",")) == {
        "ANDROID_SDK",
        "ANDROID_SDK_ROOT",
        "JAVA_HOME",
    }


def test_fdroid_buildserver_id_preflight_accepts_only_the_contract_revision(
    fdroid_contract,
    tmp_path,
):
    good_id = tmp_path / "good-buildserverid"
    good_id.write_text(fdroid_contract["BUILDSERVER_REVISION"] + "\n", encoding="ascii")
    good_result = _run_helper("--verify-buildserver-id", good_id)
    assert good_result.returncode == 0, good_result.stderr
    assert fdroid_contract["BUILDSERVER_REVISION"] in good_result.stdout

    wrong_id = tmp_path / "wrong-buildserverid"
    wrong_id.write_text("0" * 40 + "\n", encoding="ascii")
    wrong_result = _run_helper("--verify-buildserver-id", wrong_id)
    assert wrong_result.returncode != 0
    assert "does not match expected revision" in wrong_result.stderr

    missing_result = _run_helper("--verify-buildserver-id", tmp_path / "missing-buildserverid")
    assert missing_result.returncode != 0
    assert "is not readable" in missing_result.stderr


def test_fdroid_toolchain_guide_matches_the_executable_contract(fdroid_contract):
    guide = (REPO_ROOT / "fdroid/LOCAL_TOOLCHAIN.md").read_text(encoding="utf-8")
    image_refs = set(
        re.findall(
            r"registry\.gitlab\.com/fdroid/fdroidserver:buildserver-trixie@sha256:[0-9a-f]{64}",
            guide,
        )
    )
    documented_cpus = set(re.findall(r"--cpus\s+(\d+)", guide))

    assert image_refs == {fdroid_contract["BUILDSERVER_IMAGE"]}
    assert fdroid_contract["BUILDSERVER_REVISION"] in guide
    assert fdroid_contract["FDROIDSERVER_COMMIT"] in guide
    assert fdroid_contract["FDROIDSERVER_ARCHIVE_URL"] in guide
    assert fdroid_contract["FDROIDSERVER_ARCHIVE_SHA256"] in guide
    assert f"{int(fdroid_contract['FDROIDSERVER_ARCHIVE_SIZE_BYTES']):,}" in guide
    assert fdroid_contract["GRADLEW_FDROID_COMMIT"] in guide
    assert documented_cpus == {fdroid_contract["GRADLE_MAX_WORKERS"]}
    assert (
        f"org.gradle.workers.max={fdroid_contract['GRADLE_MAX_WORKERS']}"
        in guide
    )
    assert "org.gradle.parallel=true" in guide
    assert "--render-autoupdate-preview" in guide
    assert "--verify-autoupdate-preview" in guide
    assert "without opening a GitLab merge request" in guide
    assert fdroid_contract["ANDROID_NDK_PACKAGE"] in guide
    assert fdroid_contract["ANDROID_CMAKE_PACKAGE"] in guide
    assert f"Ninja {fdroid_contract['ANDROID_NINJA_VERSION']}" in guide


def test_native_toolchain_contract_matches_lock_gradle_and_fdroid_metadata(
    fdroid_contract,
):
    lock = json.loads(
        (REPO_ROOT / "hermes_android/experimental_llama_server.lock.json").read_text(
            encoding="utf-8"
        )
    )
    gradle = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    metadata = (REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template").read_text(
        encoding="utf-8"
    )

    assert (
        fdroid_contract["ANDROID_NDK_PACKAGE"]
        == lock["toolchain"]["android_ndk_package"]
    )
    assert (
        fdroid_contract["ANDROID_CMAKE_PACKAGE"]
        == lock["toolchain"]["android_cmake_package"]
    )
    assert fdroid_contract["ANDROID_CMAKE_VERSION"] == lock["toolchain"]["cmake_version"]
    assert fdroid_contract["ANDROID_NINJA_VERSION"] == lock["toolchain"]["ninja_version"]
    assert (
        f'val hermesExperimentalLlamaNdkVersion = "{fdroid_contract["ANDROID_NDK_VERSION"]}"'
        in gradle
    )
    assert "ndkVersion = hermesExperimentalLlamaNdkVersion" in gradle
    assert f'    ndk: {fdroid_contract["ANDROID_NDK_VERSION"]}' in metadata
    assert "      - apt-get install -y g++ python3-pip" in metadata
    assert f'      - sdkmanager "{fdroid_contract["ANDROID_CMAKE_PACKAGE"]}"' in metadata


def test_fdroid_helper_renders_and_then_fail_closed_verifies_preview(tmp_path):
    template = REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template"
    metadata = tmp_path / "com.mobilefork.hermesagent.yml"
    text = template.read_text(encoding="utf-8").replace(
        "REPLACE_WITH_RELEASE_COMMIT_HASH",
        "a" * 40,
        1,
    )
    text = text.replace(
        "    gradleprops:\n      - hermesFdroidSourceBinding=true\n",
        "",
        1,
    )
    text = text.replace(
        "      - python3.13 ../../scripts/android_fdroid_source_binding.py prepare --repo-root\n"
        "        ../.. --binding-file \"${GRADLE_USER_HOME:-$HOME/.gradle}/"
        "hermes-android-fdroid-source-binding.properties\"\n"
        "        --version \"$$VERSION$$\"\n",
        "",
        1,
    )
    metadata.write_text(text, encoding="utf-8")

    rendered = _run_helper("--render-autoupdate-preview", metadata, template)
    assert rendered.returncode == 0, rendered.stderr
    assert "sourceCommit=" + "a" * 40 in rendered.stdout
    verified = _run_helper("--verify-autoupdate-preview", metadata, template)
    assert verified.returncode == 0, verified.stderr

    metadata.write_text(
        metadata.read_text(encoding="utf-8").replace(
            "      - hermesFdroidSourceBinding=true\n",
            "",
            1,
        ),
        encoding="utf-8",
    )
    rejected = _run_helper("--verify-autoupdate-preview", metadata, template)
    assert rejected.returncode != 0
    assert "gradleprops does not match" in rejected.stderr


def test_fdroid_metadata_template_forbids_overwriting_live_build_history():
    template = (
        REPO_ROOT / "fdroid/com.mobilefork.hermesagent.yml.template"
    ).read_text(encoding="utf-8")
    opening = "\n".join(template.splitlines()[:8]).lower()

    assert "copy this to" not in opening
    assert "do not overwrite" in opening
    assert "fresh linux checkout" in opening
    assert "preserve all" in opening
    assert "prior builds entries" in opening
