"""An inaccessible optional executable must not abort the whole doctor report."""
from unittest.mock import patch

from hermes_cli.doctor_state import _gh_authenticated


def test_github_auth_probe_handles_inaccessible_executable():
    with patch("hermes_cli.doctor_state.subprocess.run", side_effect=PermissionError("not executable")):
        assert _gh_authenticated() is False
