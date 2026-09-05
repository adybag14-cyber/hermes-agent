"""Thin Android bridge over hermes_cli.kanban_db for the mobile Kanban UI.

Human-first board control (list / create / comment / unblock / complete).
Does not start workers or the gateway dispatcher — empty-state messaging
explains that multi-agent workers still need gateway dispatch.
"""

from __future__ import annotations

import json
import os
from typing import Any, Optional

from hermes_android.python_path import prefer_hermes_package_root

prefer_hermes_package_root()

from hermes_cli import kanban_db as kb
from hermes_cli.kanban_db_connect import connect


def _hermes_home(hermes_home: str | None) -> str:
    if hermes_home and str(hermes_home).strip():
        return os.path.expanduser(str(hermes_home).strip())
    return os.path.expanduser(os.environ.get("HERMES_HOME", "~/.hermes"))


def _task_dict(task: kb.Task) -> dict[str, Any]:
    return {
        "id": task.id,
        "title": task.title,
        "body": task.body or "",
        "assignee": task.assignee or "",
        "status": task.status,
        "priority": int(task.priority or 0),
        "tenant": task.tenant or "",
        "created_by": task.created_by or "",
        "created_at": task.created_at or 0,
        "started_at": task.started_at or 0,
        "completed_at": task.completed_at or 0,
        "result": task.result or "",
    }


def _ok(**payload: Any) -> str:
    out = {"ok": True, **payload}
    return json.dumps(out, ensure_ascii=False)


def _err(message: str, **payload: Any) -> str:
    out = {"ok": False, "error": message, **payload}
    return json.dumps(out, ensure_ascii=False)


def ensure_board(hermes_home: str | None = None, board: str | None = None) -> str:
    """Initialize the SQLite board if needed and return path metadata."""
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        # connect() auto-initializes schema on first open.
        conn = connect(board=board)
        try:
            path = str(kb.kanban_db_path(board=board))
            boards = kb.list_boards(include_archived=False)
            return _ok(
                db_path=path,
                board=board or kb.DEFAULT_BOARD,
                boards=boards,
                note=(
                    "Mobile Kanban controls the shared SQLite board. "
                    "Worker spawn still requires gateway/dispatcher."
                ),
            )
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover - surface to Android UI
        return _err(str(exc) or exc.__class__.__name__)


def list_board(
    hermes_home: str | None = None,
    status: str | None = None,
    board: str | None = None,
    limit: int = 100,
) -> str:
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        conn = connect(board=board)
        try:
            status_filter = status.strip() if status and str(status).strip() else None
            tasks = kb.list_tasks(
                conn,
                status=status_filter,
                include_archived=False,
                limit=max(1, min(int(limit or 100), 500)),
            )
            counts: dict[str, int] = {}
            for task in tasks:
                counts[task.status] = counts.get(task.status, 0) + 1
            return _ok(
                board=board or kb.DEFAULT_BOARD,
                counts=counts,
                tasks=[_task_dict(t) for t in tasks],
            )
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover
        return _err(str(exc) or exc.__class__.__name__)


def show_task(
    task_id: str,
    hermes_home: str | None = None,
    board: str | None = None,
) -> str:
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        tid = str(task_id or "").strip()
        if not tid:
            return _err("task_id is required")
        conn = connect(board=board)
        try:
            task = kb.get_task(conn, tid)
            if task is None:
                return _err(f"task not found: {tid}")
            comments = [
                {
                    "id": c.id,
                    "author": c.author or "",
                    "body": c.body or "",
                    "created_at": c.created_at or 0,
                }
                for c in kb.list_comments(conn, tid)
            ]
            return _ok(task=_task_dict(task), comments=comments)
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover
        return _err(str(exc) or exc.__class__.__name__)


def create_task(
    title: str,
    body: str = "",
    hermes_home: str | None = None,
    board: str | None = None,
    assignee: str | None = None,
) -> str:
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        cleaned = str(title or "").strip()
        if not cleaned:
            return _err("title is required")
        conn = connect(board=board)
        try:
            task_id = kb.create_task(
                conn,
                title=cleaned,
                body=str(body or "").strip() or None,
                assignee=str(assignee).strip() if assignee else None,
                created_by="android-ui",
                workspace_kind="scratch",
            )
            task = kb.get_task(conn, task_id)
            return _ok(task_id=task_id, task=_task_dict(task) if task else None)
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover
        return _err(str(exc) or exc.__class__.__name__)


def comment_task(
    task_id: str,
    text: str,
    hermes_home: str | None = None,
    board: str | None = None,
    author: str = "android-ui",
) -> str:
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        tid = str(task_id or "").strip()
        body = str(text or "").strip()
        if not tid:
            return _err("task_id is required")
        if not body:
            return _err("comment text is required")
        conn = connect(board=board)
        try:
            if kb.get_task(conn, tid) is None:
                return _err(f"task not found: {tid}")
            comment_id = kb.add_comment(conn, tid, author=author or "android-ui", body=body)
            return _ok(task_id=tid, comment_id=comment_id)
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover
        return _err(str(exc) or exc.__class__.__name__)


def complete_task(
    task_id: str,
    summary: str = "",
    hermes_home: str | None = None,
    board: str | None = None,
) -> str:
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        tid = str(task_id or "").strip()
        if not tid:
            return _err("task_id is required")
        conn = connect(board=board)
        try:
            ok = kb.complete_task(
                conn,
                tid,
                result=str(summary or "").strip() or None,
                summary=str(summary or "").strip() or None,
            )
            if not ok:
                return _err(f"could not complete task {tid} (wrong status or missing)")
            task = kb.get_task(conn, tid)
            return _ok(task_id=tid, task=_task_dict(task) if task else None)
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover
        return _err(str(exc) or exc.__class__.__name__)


def unblock_task(
    task_id: str,
    hermes_home: str | None = None,
    board: str | None = None,
) -> str:
    try:
        home = _hermes_home(hermes_home)
        os.environ.setdefault("HERMES_HOME", home)
        tid = str(task_id or "").strip()
        if not tid:
            return _err("task_id is required")
        conn = connect(board=board)
        try:
            ok = kb.unblock_task(conn, tid)
            if not ok:
                return _err(f"could not unblock task {tid} (not blocked or missing)")
            task = kb.get_task(conn, tid)
            return _ok(task_id=tid, task=_task_dict(task) if task else None)
        finally:
            conn.close()
    except Exception as exc:  # pragma: no cover
        return _err(str(exc) or exc.__class__.__name__)
