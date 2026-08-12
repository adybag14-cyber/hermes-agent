from pathlib import Path

import pytest

from scripts.check_android_litertlm_version import declared_version, latest_release


def test_litertlm_version_check_reads_exact_gradle_pin(tmp_path: Path):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'val liteRtLmStableVersion = "9.8.7"\n'
        'implementation("com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion")\n',
        encoding="utf-8",
    )

    assert declared_version(gradle_file) == "9.8.7"


def test_litertlm_version_check_rejects_dynamic_pin(tmp_path: Path):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'val liteRtLmStableVersion = "9.8.+"\n'
        'implementation("com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion")\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="exact version"):
        declared_version(gradle_file)


def test_litertlm_version_check_rejects_dependency_that_bypasses_validated_pin(tmp_path: Path):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'val liteRtLmStableVersion = "9.8.7"\n'
        'implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="validated"):
        declared_version(gradle_file)


def test_litertlm_version_check_reads_maven_release():
    metadata = b"""<?xml version="1.0"?>
    <metadata><versioning><latest>0.15.0</latest><release>0.14.0</release></versioning></metadata>
    """

    assert latest_release(metadata) == "0.14.0"
