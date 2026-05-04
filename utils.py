"""Compatibility alias for Hermes' collision-safe utility namespace.

First-party code imports hermes_cli.shared_utils. Keep the module object shared
so legacy callers and monkeypatches retain the same state and private helpers.
"""

import importlib as _importlib
import sys as _sys

_implementation = _importlib.import_module("hermes_cli.shared_utils")
_sys.modules[__name__] = _implementation
