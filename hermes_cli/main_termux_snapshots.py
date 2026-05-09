"""One-shot, profile-local snapshot cleanup after a successful Termux update."""

import logging
import shutil
import stat
import sys
from pathlib import Path

from hermes_cli.main_install_repair import _is_termux_env
from hermes_constants import get_hermes_home

logger = logging.getLogger(__name__)


def _termux_snapshot_prompt_marker_path() -> Path:
    return get_hermes_home() / ".termux_snapshot_cleanup_prompt"


def _termux_state_snapshots_dir() -> Path:
    return get_hermes_home() / "state-snapshots"


def _plain_directory(path: Path) -> bool:
    """Reject symlinks and Windows junctions before any recursive deletion."""
    info = path.lstat()
    return stat.S_ISDIR(info.st_mode) and not (
        getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT
    )


def _list_termux_state_snapshots() -> list[Path]:
    root = _termux_state_snapshots_dir()
    if not root.exists() or not _plain_directory(root):
        return []
    canonical_root = root.resolve()
    return sorted(
        (p for p in root.iterdir() if _plain_directory(p) and p.resolve().parent == canonical_root),
        key=lambda p: p.name,
    )


def _queue_termux_snapshot_cleanup() -> None:
    if not _is_termux_env():
        return
    try:
        # Exclusive creation never follows an existing marker symlink.
        with _termux_snapshot_prompt_marker_path().open("x", encoding="utf-8") as marker:
            marker.write("1")
    except FileExistsError:
        return
    except OSError:
        logger.debug("Could not queue Termux snapshot cleanup", exc_info=True)


def _select_snapshots_to_delete(snapshots: list[Path]) -> list[Path]:
    from hermes_cli.curses_ui import curses_checklist, curses_single_select

    choice = curses_single_select(
        "Termux snapshot cleanup — deleted snapshots cannot be recovered",
        ["Skip cleanup", "Delete older snapshots (keep the newest)",
         "Delete ALL snapshots (including the newest)", "Select individual snapshots"],
        default_index=0,
    )
    if choice == 1:
        return snapshots[:-1]
    if choice == 2:
        return snapshots
    if choice == 3:
        newest_first = list(reversed(snapshots))
        chosen = curses_checklist(
            "Select snapshots to delete",
            [p.name + (" (most recent)" if i == 0 else "") for i, p in enumerate(newest_first)],
            set(), cancel_returns=set(),
        )
        return [p for i, p in enumerate(newest_first) if i in chosen]
    return []


def _prompt_termux_snapshot_cleanup_on_launch() -> None:
    if not _is_termux_env() or not (sys.stdin.isatty() and sys.stdout.isatty()):
        return
    marker = _termux_snapshot_prompt_marker_path()
    if not marker.exists():
        return
    snapshots = _list_termux_state_snapshots()
    selected = _select_snapshots_to_delete(snapshots) if len(snapshots) > 1 else []
    root = _termux_state_snapshots_dir()
    canonical_root = root.resolve()
    removed = 0
    for path in selected:
        try:
            # Recheck after the user has chosen: neither the root nor a child
            # may have become a symlink/junction while the menu was open.
            if not _plain_directory(root) or root.resolve() != canonical_root:
                break
            if not _plain_directory(path) or path.resolve().parent != canonical_root:
                continue
            shutil.rmtree(path)
            removed += 1
        except OSError as exc:
            print(f"  Could not remove {path.name}: {exc}")
    if selected:
        print(f"  Removed {removed}/{len(selected)} snapshot directories.")
    try:
        marker.unlink(missing_ok=True)
    except OSError:
        logger.debug("Could not clear Termux snapshot cleanup prompt", exc_info=True)
