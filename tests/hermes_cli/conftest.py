"""Fixtures shared across hermes_cli kanban tests."""

from __future__ import annotations

import pytest


@pytest.fixture
def all_assignees_spawnable(monkeypatch):
    """Pretend every assignee maps to a real Hermes profile.

    Most dispatcher tests use synthetic assignees ("alice", "bob") that
    don't correspond to actual profile directories on disk. Without this
    patch, the dispatcher's profile-exists guard (PR #20105) routes
    those tasks into ``skipped_nonspawnable`` instead of spawning, which
    would break tests that assert spawn behavior.
    """
    from hermes_cli import profiles
    monkeypatch.setattr(profiles, "profile_exists", lambda name: True)


@pytest.fixture
def isolate_update_repository_side_effects(monkeypatch):
    """Keep update-flow tests from mutating or inspecting the real checkout.

    Cache cleanup has dedicated filesystem-behavior coverage.  Tests focused
    on branching, migrations, or gateway restarts should neither scan the
    repository nor refresh installed optional backends, especially while
    racing one another under xdist.
    """
    from hermes_cli import main

    monkeypatch.setattr(main, "_clear_bytecode_cache", lambda _root: 0)
    monkeypatch.setattr(main, "_refresh_active_lazy_features", lambda: None)
