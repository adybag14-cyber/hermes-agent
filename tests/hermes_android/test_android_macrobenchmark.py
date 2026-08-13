from pathlib import Path
import sqlite3


REPO_ROOT = Path(__file__).resolve().parents[2]
TARGET_PLACEHOLDER = "__HERMES_TARGET_PROCESS_SQL_PREDICATE__"


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
            jank_type TEXT
        );
        INSERT INTO process VALUES
            (1, 'com.mobilefork.hermesagent'),
            (2, 'android.settings');
        INSERT INTO actual_frame_timeline_slice VALUES
            (1, 101, 16000000, 'None'),
            (1, 102, 18000000, 'App Deadline Missed'),
            (1, 102, 18000000, 'App Deadline Missed'),
            (1, 103, 19000000, 'SurfaceFlinger CPU Deadline Missed'),
            (1, 104, 0, 'App Deadline Missed'),
            (1, 0, 20000000, 'App Deadline Missed'),
            (1, NULL, 20000000, 'App Deadline Missed'),
            (2, 201, 70000000, 'App Deadline Missed');
        """
    )

    total, janky, app_deadline, other = _query_counts(
        connection,
        "com.mobilefork.hermesagent",
    )

    assert (total, janky, app_deadline, other) == (3, 2, 1, 1)
    assert app_deadline + other == janky


def test_frame_metric_escapes_target_process_and_returns_zero_counts():
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE process (upid INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE actual_frame_timeline_slice (
            upid INTEGER NOT NULL,
            surface_frame_token INTEGER,
            dur INTEGER NOT NULL,
            jank_type TEXT
        );
        INSERT INTO process VALUES (1, 'com.example.o''hare');
        """
    )

    assert _query_counts(connection, "com.example.o'hare") == (0, 0, 0, 0)


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
            jank_type TEXT
        );
        INSERT INTO process VALUES
            (1, '{package_name}'),
            (2, '{package_name}:worker'),
            (3, '{truncated_name}'),
            (4, 'com.example.unrelated');
        INSERT INTO actual_frame_timeline_slice VALUES
            (1, 101, 16000000, 'None'),
            (2, 102, 16000000, 'None'),
            (3, 103, 18000000, 'App Deadline Missed'),
            (4, 104, 18000000, 'App Deadline Missed');
        """
    )

    assert _query_counts(connection, package_name) == (3, 1, 1, 0)
