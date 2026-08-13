#!/usr/bin/env bash
set -Eeuo pipefail

export TERMUX_VERSION="ci"
export HERMES_REPO_URL="file:///workspace"
export HERMES_HOME="$HOME/.hermes-ci"
export HERMES_INSTALL_DIR="$HOME/hermes-agent-ci"
# The bootstrap image intentionally has no git yet; Hermes must install it.
# Pre-authorize the read-only mounted PR checkout through Git environment
# configuration so the regression wrapper never calls git before install.
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=safe.directory
export GIT_CONFIG_VALUE_0=/workspace

echo "== native installer =="
bash /workspace/scripts/install-termux.sh \
  --branch termux-ci \
  --dir "$HERMES_INSTALL_DIR" \
  --hermes-home "$HERMES_HOME" \
  --skip-setup \
  --skip-browser \
  --no-skills \
  --non-interactive

echo "== CLI smoke =="
hermes --version
hermes --help >/dev/null
"$HERMES_INSTALL_DIR/venv/bin/python" - <<'PY'
from hermes_cli.main import _is_termux_startup_environment
assert _is_termux_startup_environment(), "Termux startup detection is false in native image"
print("native-termux-runtime-contract-ok")
PY

echo "native-termux-install-regression-ok"
