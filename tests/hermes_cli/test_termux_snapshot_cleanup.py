"""Snapshot cleanup uses the update receipt and stays inside the active profile."""

import sys

import pytest

from hermes_cli import curses_ui, main_termux_snapshots, update_cmd, update_cmd_maint


@pytest.mark.parametrize("choice,remaining", [
    (None, {"01", "02", "03"}), (0, {"01", "02", "03"}),
    (1, {"03"}), (2, set()), (3, {"01", "03"}),
])
def test_successful_update_queues_one_interactive_profile_local_cleanup(
    tmp_path, monkeypatch, choice, remaining,
):
    profile = tmp_path / "profile"
    root = profile / "state-snapshots"
    root.mkdir(parents=True)
    for name in ("01", "02", "03"):
        (root / name).mkdir()
        (root / name / "state.db").write_bytes(b"snapshot")
    outside = tmp_path / "other-profile"
    outside.mkdir()
    sentinel = outside / "state.db"
    sentinel.write_bytes(b"keep")
    monkeypatch.setenv("HERMES_HOME", str(profile))
    monkeypatch.setenv("TERMUX_VERSION", "test")
    monkeypatch.setattr(sys.stdin, "isatty", lambda: False)
    monkeypatch.setattr(sys.stdout, "isatty", lambda: True)
    monkeypatch.setattr(update_cmd, "_branch_head_suffix", lambda: "")
    choices = []

    def select(_title, _items, **kwargs):
        assert kwargs["default_index"] == 0
        choices.append(choice)
        return choice

    monkeypatch.setattr(curses_ui, "curses_single_select", select)
    monkeypatch.setattr(curses_ui, "curses_checklist", lambda *a, **kw: {1})
    marker = main_termux_snapshots._termux_snapshot_prompt_marker_path()
    update_cmd_maint._print_update_completion("Update failed")
    assert not marker.exists()
    update_cmd_maint._print_update_completion("✓ Update complete!")
    main_termux_snapshots._prompt_termux_snapshot_cleanup_on_launch()
    assert marker.exists() and not choices
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True)
    main_termux_snapshots._prompt_termux_snapshot_cleanup_on_launch()
    main_termux_snapshots._prompt_termux_snapshot_cleanup_on_launch()
    assert choices == [choice]
    assert {p.name for p in root.iterdir()} == remaining
    assert not marker.exists()
    assert sentinel.read_bytes() == b"keep"


@pytest.mark.linux_only
@pytest.mark.parametrize("redirect", ["root", "child", "during_prompt"])
def test_cleanup_never_follows_directory_symlinks(tmp_path, monkeypatch, redirect):
    profile = tmp_path / "profile"
    root = profile / "state-snapshots"
    root.mkdir(parents=True)
    for name in ("01", "02"):
        (root / name).mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    sentinel = outside / "keep"
    sentinel.write_bytes(b"untouched")
    monkeypatch.setenv("HERMES_HOME", str(profile))
    monkeypatch.setenv("TERMUX_VERSION", "test")
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(sys.stdout, "isatty", lambda: True)

    def redirect_root():
        root.rename(profile / "retained")
        root.symlink_to(outside, target_is_directory=True)

    def select(*args, **kwargs):
        if redirect == "during_prompt":
            redirect_root()
        return 2

    if redirect == "root":
        redirect_root()
    elif redirect == "child":
        (root / "03").symlink_to(outside, target_is_directory=True)
    monkeypatch.setattr(curses_ui, "curses_single_select", select)
    main_termux_snapshots._queue_termux_snapshot_cleanup()
    main_termux_snapshots._prompt_termux_snapshot_cleanup_on_launch()
    assert sentinel.read_bytes() == b"untouched"
