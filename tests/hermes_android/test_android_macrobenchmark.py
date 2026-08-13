from pathlib import Path
import sqlite3


REPO_ROOT = Path(__file__).resolve().parents[2]
TARGET_PLACEHOLDER = "__HERMES_TARGET_PROCESS_SQL_PREDICATE__"


def test_frame_metric_sql_has_exactly_one_target_process_placeholder():
    template = (
        REPO_ROOT
        / "android/macrobenchmark/src/main/assets/hermes_frame_jank_metric.sql"
    ).read_text(encoding="utf-8")

    assert template.count(TARGET_PLACEHOLDER) == 1


def _query_counts(connection: sqlite3.Connection, package_name: str) -> tuple[int, ...]:
    template = (
        REPO_ROOT
        / "android/macrobenchmark/src/main/assets/hermes_frame_jank_metric.sql"
    ).read_text(encoding="utf-8")
    literal = "'" + package_name.replace("'", "''") + "'"
    clauses = [f"process.name = {literal}", f"process.name LIKE {literal} || ':%'"]
    if len(package_name) > 15:
        truncated = "'" + package_name[-15:].replace("'", "''") + "'"
        clauses.append(f"process.name = {truncated}")
    query = template.replace(TARGET_PLACEHOLDER, "(" + " OR ".join(clauses) + ")")
    assert TARGET_PLACEHOLDER not in query
    row = connection.execute(query).fetchone()
    assert row is not None
    return tuple(int(value) for value in row)


def test_frame_metric_deduplicates_target_frames_and_reconciles_jank_categories():
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE process (upid INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE actual_frame_timeline_slice (
            upid INTEGER NOT NULL,
            surface_frame_token INTEGER,
            dur INTEGER NOT NULL,
            jank_type TEXT,
            jank_tag TEXT
        );
        INSERT INTO process VALUES
            (1, 'com.mobilefork.hermesagent'),
            (2, 'android.settings');
        INSERT INTO actual_frame_timeline_slice VALUES
            (1, 101, 16000000, 'None', 'No Jank'),
            (1, 102, 18000000, 'Prediction Error, App Deadline Missed', 'Self Jank'),
            (1, 102, 18000000, 'Prediction Error, App Deadline Missed', 'Self Jank'),
            (1, 103, 19000000, 'Prediction Error', 'Other Jank'),
            (1, 104, 20000000, 'Dropped Frame', 'Dropped Frame'),
            (1, 105, 20000000, 'Buffer Stuffing', 'Buffer Stuffing'),
            (1, 106, 20000000, 'SurfaceFlinger Stuffing', 'SurfaceFlinger Stuffing'),
            (1, 107, 20000000, 'Unexpected', 'Future Tag'),
            (1, 109, 20000000, 'Unknown Jank', 'Self Jank'),
            (1, 108, 0, 'App Deadline Missed', 'Self Jank'),
            (1, 0, 20000000, 'App Deadline Missed', 'Self Jank'),
            (1, NULL, 20000000, 'App Deadline Missed', 'Self Jank'),
            (2, 201, 70000000, 'App Deadline Missed', 'Self Jank');
        """
    )

    (
        total,
        janky,
        app_deadline,
        app_deadline_or_dropped,
        other,
        other_tagged,
        overlap,
        dropped,
        unknown,
    ) = _query_counts(
        connection,
        "com.mobilefork.hermesagent",
    )

    assert (
        total,
        janky,
        app_deadline,
        app_deadline_or_dropped,
        other,
        other_tagged,
        overlap,
        dropped,
        unknown,
    ) == (
        8,
        2,
        1,
        2,
        1,
        1,
        0,
        1,
        1,
    )
    assert app_deadline + other == janky
    assert janky + other_tagged <= total
    assert max(app_deadline, dropped) <= app_deadline_or_dropped
    assert app_deadline_or_dropped <= app_deadline + dropped


def test_frame_metric_escapes_target_process_and_returns_zero_counts():
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE process (upid INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE actual_frame_timeline_slice (
            upid INTEGER NOT NULL,
            surface_frame_token INTEGER,
            dur INTEGER NOT NULL,
            jank_type TEXT,
            jank_tag TEXT
        );
        INSERT INTO process VALUES (1, 'com.example.o''hare');
        """
    )

    assert _query_counts(connection, "com.example.o'hare") == (
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    )


def test_frame_metric_accepts_full_subprocess_and_perfetto_truncated_names():
    package_name = "com.mobilefork.hermesagent"
    truncated_name = package_name[-15:]
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        f"""
        CREATE TABLE process (upid INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE actual_frame_timeline_slice (
            upid INTEGER NOT NULL,
            surface_frame_token INTEGER,
            dur INTEGER NOT NULL,
            jank_type TEXT,
            jank_tag TEXT
        );
        INSERT INTO process VALUES
            (1, '{package_name}'),
            (2, '{package_name}:worker'),
            (3, '{truncated_name}'),
            (4, 'com.example.unrelated');
        INSERT INTO actual_frame_timeline_slice VALUES
            (1, 101, 16000000, 'None', 'No Jank'),
            (2, 102, 16000000, 'None', 'No Jank'),
            (3, 103, 18000000, 'App Deadline Missed', 'Self Jank'),
            (4, 104, 18000000, 'App Deadline Missed', 'Self Jank');
        """
    )

    assert _query_counts(connection, package_name) == (
        3,
        1,
        1,
        1,
        0,
        0,
        0,
        0,
        0,
    )


def test_frame_metric_exposes_conflicting_self_and_other_tags_for_one_token():
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE process (upid INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE actual_frame_timeline_slice (
            upid INTEGER NOT NULL,
            surface_frame_token INTEGER,
            dur INTEGER NOT NULL,
            jank_type TEXT,
            jank_tag TEXT
        );
        INSERT INTO process VALUES (1, 'com.mobilefork.hermesagent');
        INSERT INTO actual_frame_timeline_slice VALUES
            (1, 101, 16000000, 'App Deadline Missed', 'Self Jank'),
            (1, 101, 16000000, 'Prediction Error', 'Other Jank'),
            (1, 102, 16000000, 'None', 'No Jank');
        """
    )

    assert _query_counts(connection, "com.mobilefork.hermesagent") == (
        2,
        1,
        1,
        1,
        0,
        1,
        1,
        0,
        0,
    )


def test_frame_metric_deduplicates_deadline_and_dropped_overlap_for_one_token():
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE process (upid INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE actual_frame_timeline_slice (
            upid INTEGER NOT NULL,
            surface_frame_token INTEGER,
            dur INTEGER NOT NULL,
            jank_type TEXT,
            jank_tag TEXT
        );
        INSERT INTO process VALUES (1, 'com.mobilefork.hermesagent');
        INSERT INTO actual_frame_timeline_slice VALUES
            (1, 101, 16000000, 'App Deadline Missed', 'Self Jank'),
            (1, 101, 16000000, 'Dropped Frame', 'Dropped Frame'),
            (1, 102, 16000000, 'None', 'No Jank');
        """
    )

    counts = _query_counts(connection, "com.mobilefork.hermesagent")
    assert counts == (2, 1, 1, 1, 0, 0, 0, 1, 0)
    _, _, deadline, union, _, _, _, dropped, _ = counts
    assert deadline + dropped - union == 1
