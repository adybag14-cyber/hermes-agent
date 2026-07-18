#!/bin/bash
# ============================================================================
# Hermes Agent Installer
# ============================================================================
# Installation script for Linux, macOS, and Android/Termux.
# Uses uv for desktop/server installs and Python's stdlib venv + pip on Termux.
#
# Usage:
#   curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
#
# Or with options:
#   curl -fsSL ... | bash -s -- --no-venv --skip-setup
#
# ============================================================================

set -e

# Native Termux uses a dedicated uv-based installer. Keep the established
# Linux/macOS installer below in this file: bootstrap integrations and
# regression tests intentionally inspect its functions in place.
if [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == *"com.termux/files/usr"* ]]; then
    _hermes_script_dir=""
    _hermes_source="${BASH_SOURCE[0]:-}"
    if [ -n "$_hermes_source" ] && [ -f "$_hermes_source" ]; then
        _hermes_script_dir="$(cd "$(dirname "$_hermes_source")" 2>/dev/null && pwd || true)"
    fi

    if [ -n "$_hermes_script_dir" ] && [ -f "$_hermes_script_dir/install-termux.sh" ]; then
        exec bash "$_hermes_script_dir/install-termux.sh" "$@"
    fi

    _hermes_install_ref="${HERMES_INSTALL_REF:-main}"
    _hermes_previous=""
    for _hermes_arg in "$@"; do
        if [ "$_hermes_previous" = "--branch" ] || [ "$_hermes_previous" = "-Branch" ] || \
           [ "$_hermes_previous" = "--commit" ] || [ "$_hermes_previous" = "-Commit" ]; then
            _hermes_install_ref="$_hermes_arg"
            _hermes_previous=""
            continue
        fi
        case "$_hermes_arg" in
            --branch|-Branch|--commit|-Commit) _hermes_previous="$_hermes_arg" ;;
        esac
    done

    if ! command -v curl >/dev/null 2>&1; then
        echo "Hermes native Termux installer requires curl" >&2
        exit 1
    fi

    _hermes_base="${HERMES_INSTALL_SCRIPT_BASE_URL:-https://raw.githubusercontent.com/NousResearch/hermes-agent/${_hermes_install_ref}/scripts}"
    _hermes_tmp="$(mktemp 2>/dev/null || printf '%s/hermes-install-termux.%s.sh' "${TMPDIR:-/tmp}" "$$")"
    if ! curl -fsSL "$_hermes_base/install-termux.sh" -o "$_hermes_tmp"; then
        echo "Failed to download the native Termux installer: $_hermes_base/install-termux.sh" >&2
        rm -f "$_hermes_tmp"
        exit 1
    fi

    if bash "$_hermes_tmp" "$@"; then
        _hermes_rc=0
    else
        _hermes_rc=$?
    fi
    rm -f "$_hermes_tmp"
    exit "$_hermes_rc"
fi

# Guard against environment leakage when the installer is launched from another
