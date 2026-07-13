from pathlib import Path

import pytest

from scripts.check_android_litertlm_version import declared_version, latest_release


def test_litertlm_version_check_reads_exact_gradle_pin(tmp_path: Path):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")\n',
        encoding="utf-8",
    )

    assert declared_version(gradle_file) == "0.14.0"


def test_litertlm_version_check_rejects_dynamic_pin(tmp_path: Path):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.+")\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="exact version"):
        declared_version(gradle_file)


def test_litertlm_version_check_reads_maven_release():
    metadata = b"""<?xml version="1.0"?>
    <metadata><versioning><latest>0.15.0</latest><release>0.14.0</release></versioning></metadata>
    """

    assert latest_release(metadata) == "0.14.0"
