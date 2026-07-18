#!/data/data/com.termux/files/usr/bin/bash
# Native Android/Termux installer for Hermes Agent.
# This path intentionally uses Termux packages + uv directly; it does not use
# proot, Ubuntu, pip, or a global third-party Python package index.
set -euo pipefail

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
INSTALL_DIR="${HERMES_INSTALL_DIR:-$HERMES_HOME/hermes-agent}"
BRANCH="main"
INSTALL_COMMIT=""
RUN_SETUP=true
SKIP_BROWSER=false
NO_SKILLS=false
NON_INTERACTIVE=false
USE_VENV=true
ANDROID_BUILD_API="${HERMES_ANDROID_API_LEVEL:-24}"
REPO_URL="${HERMES_REPO_URL:-https://github.com/NousResearch/hermes-agent.git}"

log() { printf '\033[0;36m→\033[0m %s\n' "$*"; }
ok() { printf '\033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m⚠\033[0m %s\n' "$*" >&2; }
die() { printf '\033[0;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

is_termux() {
    [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == *"com.termux/files/usr"* ]]
}

usage() {
    cat <<'USAGE'
Hermes Agent native Termux installer

Usage: install-termux.sh [OPTIONS]
  --branch NAME          Git branch to install (default: main)
  --commit SHA           Pin the checkout to an exact commit
  --dir PATH             Installation directory
  --hermes-home PATH     Hermes data directory
  --skip-setup           Skip API/provider setup
  --skip-browser         Skip optional Node browser dependency install
  --no-skills            Do not seed bundled skills
  --non-interactive      Never prompt
  --no-venv              Unsupported on native Termux (isolated venv required)
  -h, --help             Show this help

Environment overrides:
  HERMES_PYTHON=/path/to/python3.11
  HERMES_ANDROID_API_LEVEL=24
  HERMES_PYTHON_INDEX=https://pypi.org/simple
USAGE
}

parse_args() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --branch|-Branch)
                [ "$#" -ge 2 ] || die "$1 requires a value"
                BRANCH="$2"
                shift 2
                ;;
            --commit|-Commit)
                [ "$#" -ge 2 ] || die "$1 requires a value"
                INSTALL_COMMIT="$2"
                shift 2
                ;;
            --dir)
                [ "$#" -ge 2 ] || die "$1 requires a value"
                INSTALL_DIR="$2"
                shift 2
                ;;
            --hermes-home)
                [ "$#" -ge 2 ] || die "$1 requires a value"
                HERMES_HOME="$2"
                shift 2
                ;;
            --skip-setup) RUN_SETUP=false; shift ;;
            --skip-browser|--no-playwright) SKIP_BROWSER=true; shift ;;
            --no-skills) NO_SKILLS=true; shift ;;
            --non-interactive|-NonInteractive) NON_INTERACTIVE=true; shift ;;
            --no-venv) USE_VENV=false; shift ;;
            -h|--help) usage; exit 0 ;;
            --manifest|-Manifest|--stage|-Stage|--json|-Json|--include-desktop|-IncludeDesktop|--ensure|--postinstall)
                die "$1 is not supported by the native Termux installer"
                ;;
            *) die "Unknown option: $1" ;;
        esac
    done
    [ "$USE_VENV" = true ] || die \
        "Native Termux installs require an isolated venv; remove --no-venv"
}

python_is_supported() {
    local candidate="$1"
    [ -x "$candidate" ] || return 1
    "$candidate" -c \
        'import sys; raise SystemExit(0 if (3, 11) <= sys.version_info < (3, 14) else 1)' \
        >/dev/null 2>&1
}

find_supported_python() {
    local candidate resolved
    if [ -n "${HERMES_PYTHON:-}" ]; then
        resolved="$HERMES_PYTHON"
        command -v "$resolved" >/dev/null 2>&1 && resolved="$(command -v "$resolved")"
        python_is_supported "$resolved" || die \
            "HERMES_PYTHON must point to CPython 3.11, 3.12, or 3.13: $resolved"
        printf '%s\n' "$resolved"
        return 0
    fi

    for candidate in python3.13 python3.12 python3.11 python; do
        resolved="$(command -v "$candidate" 2>/dev/null || true)"
        if [ -n "$resolved" ] && python_is_supported "$resolved"; then
            printf '%s\n' "$resolved"
            return 0
        fi
    done
    return 1
}

install_side_by_side_python311() {
    command -v pkg >/dev/null 2>&1 || die \
        "pkg is unavailable; install the current Termux app"
    log "System Python is outside Hermes' supported 3.11–3.13 range"
    log "Installing TUR python3.11 side-by-side without replacing python/python3"
    pkg install -y tur-repo >/dev/null
    apt update >/dev/null

    local tmp deb staged_prefix
    tmp="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/hermes-python311.XXXXXX")"
    (
        cd "$tmp"
        apt download python3.11 >/dev/null
    )
    deb="$(find "$tmp" -maxdepth 1 -type f -name 'python3.11_*.deb' | head -1)"
    [ -n "$deb" ] || {
        rm -rf "$tmp"
        die "TUR did not provide a python3.11 package"
    }
    dpkg-deb -x "$deb" "$tmp/root"
    staged_prefix="$tmp/root$PREFIX"
    [ -x "$staged_prefix/bin/python3.11" ] || {
        rm -rf "$tmp"
        die "Downloaded python3.11 package did not contain $PREFIX/bin/python3.11"
    }

    # The TUR package's maintainer scripts create unversioned aliases when
    # installed normally. Copying only its data payload, after deleting any
    # aliases defensively, keeps Termux's system python/python3 untouched.
    rm -f \
        "$staged_prefix/bin/python" "$staged_prefix/bin/python3" \
        "$staged_prefix/bin/pip" "$staged_prefix/bin/pip3" \
        "$staged_prefix/bin/idle" "$staged_prefix/bin/pydoc" \
        "$staged_prefix/bin/2to3"
    cp -a "$staged_prefix/." "$PREFIX/"
    rm -rf "$tmp"
    hash -r
    python_is_supported "$PREFIX/bin/python3.11" || die \
        "Side-by-side Python 3.11 failed its launch check"
    ok "$($PREFIX/bin/python3.11 --version) installed side-by-side"
}

install_system_packages() {
    log "Installing native Termux build/runtime packages"
    pkg install -y \
        git curl ca-certificates uv clang rust make pkg-config binutils patchelf \
        libffi openssl nodejs ripgrep ffmpeg >/dev/null

    # Pillow uses these when present. Keep them best-effort so a repository
    # mirror that temporarily lacks one codec does not block the core CLI.
    local package
    for package in libjpeg-turbo libpng freetype libwebp openjpeg littlecms libtiff; do
        pkg install -y "$package" >/dev/null 2>&1 || warn \
            "Optional image codec package unavailable: $package"
    done
    ok "Termux toolchain and uv are ready"
}

prepare_repository() {
    mkdir -p "$(dirname "$INSTALL_DIR")"
    if [ -d "$INSTALL_DIR/.git" ]; then
        log "Updating existing Hermes checkout"
        local stash_ref=""
        if [ -n "$(git -C "$INSTALL_DIR" status --porcelain 2>/dev/null)" ]; then
            git -C "$INSTALL_DIR" stash push --include-untracked -m \
                "hermes-termux-installer-$(date -u +%Y%m%dT%H%M%SZ)" >/dev/null
            stash_ref="$(git -C "$INSTALL_DIR" stash list -1 --format='%gd')"
            warn "Local changes were stashed as $stash_ref"
        fi
        git -C "$INSTALL_DIR" fetch origin "$BRANCH"
        git -C "$INSTALL_DIR" checkout "$BRANCH" 2>/dev/null || \
            git -C "$INSTALL_DIR" checkout -B "$BRANCH" "origin/$BRANCH"
        git -C "$INSTALL_DIR" merge --ff-only "origin/$BRANCH"
        if [ -n "$stash_ref" ]; then
            if git -C "$INSTALL_DIR" stash pop >/dev/null; then
                ok "Restored local checkout changes"
            else
                warn "Could not automatically restore $stash_ref; it remains in git stash"
            fi
        fi
    elif [ -e "$INSTALL_DIR" ]; then
        local backup="${INSTALL_DIR}.broken-$(date -u +%Y%m%d-%H%M%S)"
        warn "Moving non-git install aside to $backup"
        mv "$INSTALL_DIR" "$backup"
        git clone --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
    else
        log "Cloning Hermes Agent"
        git clone --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
    fi

    if [ -n "$INSTALL_COMMIT" ]; then
        git -C "$INSTALL_DIR" fetch origin "$INSTALL_COMMIT" || true
        git -C "$INSTALL_DIR" checkout --detach "$INSTALL_COMMIT"
    fi
    ok "Repository ready at $INSTALL_DIR"
}

create_venv() {
    local python="$1"
    local backup=""
    cd "$INSTALL_DIR"
    if [ -d venv ]; then
        backup="venv.pre-native-termux-$(date -u +%Y%m%d-%H%M%S)"
        mv venv "$backup"
        warn "Preserved previous venv as $INSTALL_DIR/$backup until validation succeeds"
    fi

    if ! uv venv --python "$python" venv; then
        [ -n "$backup" ] && [ -d "$backup" ] && mv "$backup" venv
        die "uv failed to create the Hermes virtual environment"
    fi
    VENV_BACKUP="$backup"
    export VENV_BACKUP
    ok "Virtual environment ready: $(venv/bin/python --version)"
}

install_python_dependencies() {
    cd "$INSTALL_DIR"
    local venv_python="$INSTALL_DIR/venv/bin/python"
    local work="$HERMES_HOME/cache/termux-install"
    local direct="$work/direct.in"
    local lock_constraints="$work/lock-constraints.txt"
    local resolved="$work/resolved.txt"
    local cargo_home="$HERMES_HOME/cache/cargo-termux"
    mkdir -p "$work" "$cargo_home" "$HERMES_HOME/cache/uv-termux"

    export UV_NO_CONFIG=1
    export UV_LINK_MODE=copy
    export UV_PYTHON="$venv_python"
    export UV_CACHE_DIR="$HERMES_HOME/cache/uv-termux"
    export UV_CONCURRENT_BUILDS=1
    export UV_CONCURRENT_INSTALLS=1
    export UV_CONCURRENT_DOWNLOADS=4
    export CARGO_BUILD_JOBS=1
    export CARGO_HOME="$cargo_home"
    export HERMES_ANDROID_API_LEVEL="$ANDROID_BUILD_API"
    export ANDROID_API_LEVEL="$ANDROID_BUILD_API"
    export UV_DEFAULT_INDEX="${HERMES_PYTHON_INDEX:-https://pypi.org/simple}"
    export UV_INDEX_STRATEGY=first-index
    unset UV_INDEX UV_EXTRA_INDEX_URL PIP_INDEX_URL PIP_EXTRA_INDEX_URL \
        _PYTHON_HOST_PLATFORM

    log "Installing build prerequisites with uv"
    uv pip install --python "$venv_python" \
        'setuptools>=77,<83' wheel packaging cython pycparser

    "$venv_python" scripts/termux_requirements.py \
        --pyproject pyproject.toml \
        --lock uv.lock \
        --requirements "$direct" \
        --constraints "$lock_constraints" \
        --python-version "$($venv_python -c \
            'import platform; print(platform.python_version())')"

    log "Resolving the Android-safe Termux dependency graph"
    uv pip compile "$direct" \
        --python "$venv_python" \
        --constraint "$lock_constraints" \
        --output-file "$resolved" \
        --no-annotate

    log "Prebuilding setuptools extensions with Android wheel tags"
    "$venv_python" scripts/install_android_wheels.py \
        --uv "$(command -v uv)" \
        --python "$venv_python" \
        --requirements "$resolved"

    install_resolved() {
        uv pip install --python "$venv_python" --requirements "$resolved" \
            --constraint constraints-termux.txt
    }

    log "Installing resolved dependencies with serial native builds"
    if ! install_resolved; then
        local broken_cargo="${cargo_home}.broken-$(date -u +%Y%m%d-%H%M%S)"
        warn "Native dependency build failed; retrying with a fresh isolated Cargo registry"
        [ -d "$cargo_home" ] && mv "$cargo_home" "$broken_cargo"
        mkdir -p "$cargo_home"
        install_resolved || die \
            "Hermes dependency installation failed on the clean Cargo retry"
        rm -rf "$broken_cargo"
    fi

    log "Installing Hermes editable entrypoints with uv"
    uv pip install --python "$venv_python" --no-deps --editable .
    uv pip check --python "$venv_python"

    "$venv_python" - <<'PY'
import importlib

required = [
    "hermes_cli",
    "psutil",
    "yaml",
    "cffi",
    "PIL",
    "pydantic_core",
]
for module in required:
    importlib.import_module(module)
print("Hermes native dependency smoke test passed")
PY
    ok "Python dependencies installed"
}

install_launcher() {
    local launcher="$PREFIX/bin/hermes"
    local venv_python="$INSTALL_DIR/venv/bin/python"
    local venv_hermes="$INSTALL_DIR/venv/bin/hermes"
    [ -x "$venv_hermes" ] || die \
        "Hermes console entrypoint was not generated: $venv_hermes"
    cat > "$launcher" <<EOF_LAUNCHER
#!$PREFIX/bin/bash
unset PYTHONPATH
unset PYTHONHOME
export UV_LINK_MODE=copy
case "\${1:-}" in
    --version|-V|version)
        exec "$venv_python" -c 'from hermes_cli import __release_date__, __version__; import sys; print(f"Hermes Agent v{__version__} ({__release_date__})"); print(f"Install directory: $INSTALL_DIR"); print(f"Python: {sys.version.split()[0]}")'
        ;;
esac
exec "$venv_hermes" "\$@"
EOF_LAUNCHER
    chmod 755 "$launcher"
    hash -r
    ok "Installed launcher at $launcher"
}

configure_hermes() {
    mkdir -p "$HERMES_HOME"/{cron,sessions,logs,pairing,hooks,image_cache,audio_cache,memories,skills}
    if [ ! -f "$HERMES_HOME/.env" ]; then
        if [ -f "$INSTALL_DIR/.env.example" ]; then
            cp "$INSTALL_DIR/.env.example" "$HERMES_HOME/.env"
        else
            touch "$HERMES_HOME/.env"
        fi
    fi
    chmod 600 "$HERMES_HOME/.env"
    if [ ! -f "$HERMES_HOME/config.yaml" ] && \
       [ -f "$INSTALL_DIR/cli-config.yaml.example" ]; then
        cp "$INSTALL_DIR/cli-config.yaml.example" "$HERMES_HOME/config.yaml"
    fi
    if [ ! -f "$HERMES_HOME/SOUL.md" ]; then
        printf '%s\n' \
            'You are Hermes Agent, an intelligent AI assistant created by Nous Research.' \
            > "$HERMES_HOME/SOUL.md"
    fi

    if [ "$NO_SKILLS" = true ]; then
        printf '%s\n' 'Bundled skill seeding disabled by --no-skills.' \
            > "$HERMES_HOME/.no-bundled-skills"
    elif [ -f "$INSTALL_DIR/tools/skills_sync.py" ]; then
        "$INSTALL_DIR/venv/bin/python" "$INSTALL_DIR/tools/skills_sync.py" || \
            warn "Bundled skill sync failed"
    fi
    ok "Hermes configuration directory is ready"
}

install_optional_node_tools() {
    [ "$SKIP_BROWSER" = false ] || return 0
    command -v npm >/dev/null 2>&1 || {
        warn "npm unavailable; skipping browser tools"
        return 0
    }
    log "Installing optional browser command dependencies"
    if (
        cd "$INSTALL_DIR"
        npm install --workspaces=false --silent --no-fund --no-audit --progress=false
    ); then
        ok "Browser command dependencies installed"
    else
        warn "Browser command dependency install failed; core Hermes remains usable"
    fi
}

run_setup() {
    [ "$RUN_SETUP" = true ] || return 0
    [ "$NON_INTERACTIVE" = false ] || return 0
    if [ -r /dev/tty ] && [ -w /dev/tty ]; then
        "$PREFIX/bin/hermes" setup </dev/tty >/dev/tty || \
            warn "Setup wizard did not complete"
    else
        warn "No interactive terminal is available; run 'hermes setup' later"
    fi
}

main() {
    parse_args "$@"
    is_termux || die "This installer is only for native Termux/Android"
    [ -n "${PREFIX:-}" ] || die "PREFIX is not set; launch this from Termux"
    case "$ANDROID_BUILD_API" in
        ''|*[!0-9]*) die "HERMES_ANDROID_API_LEVEL must be numeric" ;;
    esac
    [ "$ANDROID_BUILD_API" -ge 21 ] || die "Android build API must be at least 21"

    unset PYTHONPATH PYTHONHOME
    install_system_packages

    local python
    python="$(find_supported_python || true)"
    if [ -z "$python" ]; then
        install_side_by_side_python311
        python="$PREFIX/bin/python3.11"
    fi
    ok "Using $($python --version) at $python"

    prepare_repository
    create_venv "$python"
    install_python_dependencies
    install_launcher
    configure_hermes
    install_optional_node_tools

    "$PREFIX/bin/hermes" --version
    echo "git" > "$INSTALL_DIR/.install_method"
    if [ -n "${VENV_BACKUP:-}" ] && [ -d "$INSTALL_DIR/$VENV_BACKUP" ]; then
        rm -rf "$INSTALL_DIR/$VENV_BACKUP"
    fi
    run_setup
    ok "Hermes Agent is installed natively in Termux"
    printf '%s\n' "Run: hermes"
}

if [ "${HERMES_TERMUX_INSTALL_TESTING:-0}" != "1" ]; then
    main "$@"
fi
