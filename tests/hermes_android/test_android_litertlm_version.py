from pathlib import Path

import pytest

from scripts.check_android_litertlm_version import declared_version, latest_release, main


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


def test_litertlm_version_check_rejects_dynamic_maven_release():
    metadata = b"""<?xml version="1.0"?>
    <metadata><versioning><release>0.16.+</release></versioning></metadata>
    """

    with pytest.raises(ValueError, match="exact release version"):
        latest_release(metadata)


def test_litertlm_version_check_prints_latest_without_changing_release_pin(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
):
    metadata_file = tmp_path / "maven-metadata.xml"
    metadata_file.write_text(
        "<metadata><versioning><release>9.8.8</release></versioning></metadata>",
        encoding="utf-8",
    )

    main(
        [
            "--print-latest",
            "--metadata-file",
            str(metadata_file),
            "--gradle-file",
            str(tmp_path / "intentionally-absent.gradle.kts"),
        ]
    )

    assert capsys.readouterr().out == "9.8.8\n"


def test_litertlm_version_check_prints_declared_without_maven_metadata(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'val liteRtLmStableVersion = "9.8.7"\n'
        'implementation("com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion")\n',
        encoding="utf-8",
    )

    main(["--print-declared", "--gradle-file", str(gradle_file)])

    assert capsys.readouterr().out == "9.8.7\n"


def test_litertlm_version_check_default_mode_keeps_release_pin_strict(
    tmp_path: Path,
):
    gradle_file = tmp_path / "build.gradle.kts"
    gradle_file.write_text(
        'val liteRtLmStableVersion = "9.8.7"\n'
        'implementation("com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion")\n',
        encoding="utf-8",
    )
    metadata_file = tmp_path / "maven-metadata.xml"
    metadata_file.write_text(
        "<metadata><versioning><release>9.8.8</release></versioning></metadata>",
        encoding="utf-8",
    )

    with pytest.raises(SystemExit, match="pins LiteRT-LM Android 9.8.7"):
        main(
            [
                "--gradle-file",
                str(gradle_file),
                "--metadata-file",
                str(metadata_file),
            ]
        )
