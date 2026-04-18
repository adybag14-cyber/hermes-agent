#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  chatgpt-web-vnc-auth.sh start [options]
  chatgpt-web-vnc-auth.sh status [options]
  chatgpt-web-vnc-auth.sh stop [options]

Options:
  --session-dir PATH   Override runtime directory
  --display :N         X display to use (default: :99)
  --screen WxHxD       Xvfb screen spec (default: 1280x900x24)
  --vnc-port PORT      VNC TCP port (default: 5901)
  --web-port PORT      noVNC/websockify port (default: 6080)
  --debug-port PORT    Chromium DevTools port (default: 9222)
  --bind-host HOST     Bind host for websockify (default: 0.0.0.0)
  --public-host HOST   Hostname/IP shown in the printed URL
  --timeout SECONDS    hermes auth browser timeout (default: 3600)
  --password VALUE     VNC password. If omitted, a random 8-char password is generated
  --hermes-bin PATH    Hermes executable (default: hermes)
  -h, --help           Show this help
EOF
}

action="${1:-}"
if [[ -z "$action" || "$action" == "-h" || "$action" == "--help" ]]; then
  usage
  exit 0
fi
shift || true

hermes_home="${HERMES_HOME:-$HOME/.hermes}"
session_dir="${CHATGPT_WEB_VNC_SESSION_DIR:-$hermes_home/remote-chatgpt-web-auth}"
display="${CHATGPT_WEB_VNC_DISPLAY:-:99}"
screen="${CHATGPT_WEB_VNC_SCREEN:-1280x900x24}"
vnc_port="${CHATGPT_WEB_VNC_VNC_PORT:-5901}"
web_port="${CHATGPT_WEB_VNC_WEB_PORT:-6080}"
debug_port="${CHATGPT_WEB_VNC_DEBUG_PORT:-9222}"
bind_host="${CHATGPT_WEB_VNC_BIND_HOST:-0.0.0.0}"
public_host="${CHATGPT_WEB_VNC_PUBLIC_HOST:-}"
timeout_seconds="${CHATGPT_WEB_VNC_TIMEOUT:-3600}"
password="${CHATGPT_WEB_VNC_PASSWORD:-}"
hermes_bin="${CHATGPT_WEB_VNC_HERMES_BIN:-hermes}"
browser_runtime_dir="${CHATGPT_WEB_VNC_BROWSER_BASE_DIR:-$HOME/hermes-chatgpt-web-browser}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --session-dir) session_dir="$2"; shift 2 ;;
    --display) display="$2"; shift 2 ;;
    --screen) screen="$2"; shift 2 ;;
    --vnc-port) vnc_port="$2"; shift 2 ;;
    --web-port) web_port="$2"; shift 2 ;;
    --debug-port) debug_port="$2"; shift 2 ;;
    --bind-host) bind_host="$2"; shift 2 ;;
    --public-host) public_host="$2"; shift 2 ;;
    --timeout) timeout_seconds="$2"; shift 2 ;;
    --password) password="$2"; shift 2 ;;
    --hermes-bin) hermes_bin="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

run_dir="$session_dir/run"
log_dir="$session_dir/logs"
runtime_dir="$session_dir/runtime"
meta_file="$session_dir/session.env"
passwd_file="$session_dir/vnc.pass"

mkdir -p "$run_dir" "$log_dir" "$runtime_dir"
chmod 700 "$runtime_dir"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

find_novnc_web() {
  local candidate
  for candidate in \
    /usr/share/novnc \
    /usr/share/novnc/utils/websockify \
    /usr/share/novnc/www \
    /opt/novnc \
    "$HOME/noVNC"; do
    if [[ -d "$candidate" && -f "$candidate/vnc.html" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

generate_password() {
  python3 - <<'PY'
import secrets
alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
print("".join(secrets.choice(alphabet) for _ in range(8)))
PY
}

write_metadata() {
  local effective_host="$public_host"
  if [[ -z "$effective_host" ]]; then
    effective_host="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  cat >"$meta_file" <<EOF
DISPLAY=$display
SCREEN=$screen
VNC_PORT=$vnc_port
WEB_PORT=$web_port
DEBUG_PORT=$debug_port
BIND_HOST=$bind_host
PUBLIC_HOST=$effective_host
SESSION_DIR=$session_dir
VNC_PASSWORD=$password
BROWSER_RUNTIME_DIR=$browser_runtime_dir
NO_VNC_URL=http://$effective_host:$web_port/vnc.html
EOF
  chmod 600 "$meta_file"
}

pid_file() {
  printf '%s/%s.pid\n' "$run_dir" "$1"
}

is_pid_live() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

read_pid() {
  local file="$1"
  [[ -f "$file" ]] || return 1
  tr -d '[:space:]' <"$file"
}

assert_not_running() {
  local name="$1"
  local pid
  pid="$(read_pid "$(pid_file "$name")" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && is_pid_live "$pid"; then
    echo "$name is already running with PID $pid" >&2
    exit 1
  fi
}

wait_for_http() {
  local url="$1"
  local attempts="${2:-60}"
  local sleep_seconds="${3:-1}"
  local i
  for ((i=0; i<attempts; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_seconds"
  done
  return 1
}

wait_for_tcp() {
  local host="$1"
  local port="$2"
  local attempts="${3:-60}"
  local sleep_seconds="${4:-1}"
  local i
  for ((i=0; i<attempts; i++)); do
    if python3 - "$host" "$port" <<'PY'
import socket, sys
host = sys.argv[1]
port = int(sys.argv[2])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(1.0)
try:
    s.connect((host, port))
except Exception:
    sys.exit(1)
else:
    sys.exit(0)
finally:
    s.close()
PY
    then
      return 0
    fi
    sleep "$sleep_seconds"
  done
  return 1
}

start_bg() {
  local name="$1"
  shift
  local log="$log_dir/$name.log"
  nohup "$@" >>"$log" 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" >"$(pid_file "$name")"
}

stop_pid_file() {
  local name="$1"
  local pattern="$2"
  local file
  file="$(pid_file "$name")"
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  local pid
  pid="$(read_pid "$file" || true)"
  if [[ -z "$pid" ]]; then
    rm -f "$file"
    return 0
  fi
  if ! is_pid_live "$pid"; then
    rm -f "$file"
    return 0
  fi
  local cmdline
  cmdline="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  if [[ "$cmdline" != *"$pattern"* ]]; then
    echo "Refusing to stop PID $pid for $name because command line did not match '$pattern'" >&2
    return 1
  fi
  kill "$pid" 2>/dev/null || true
  local i
  for ((i=0; i<10; i++)); do
    if ! is_pid_live "$pid"; then
      rm -f "$file"
      return 0
    fi
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$file"
}

status() {
  if [[ -f "$meta_file" ]]; then
    cat "$meta_file"
  else
    echo "No session metadata found in $session_dir"
  fi
  echo
  local name pid cmdline
  for name in xvfb openbox auth x11vnc websockify; do
    pid="$(read_pid "$(pid_file "$name")" 2>/dev/null || true)"
    if [[ -z "$pid" ]]; then
      echo "$name: not tracked"
      continue
    fi
    if is_pid_live "$pid"; then
      cmdline="$(ps -p "$pid" -o args= 2>/dev/null || true)"
      echo "$name: pid=$pid live=1 cmd=$cmdline"
    else
      echo "$name: pid=$pid live=0"
    fi
  done
  echo
  ss -ltnp 2>/dev/null | awk -v v1=":$vnc_port" -v v2=":$web_port" -v v3=":$debug_port" '
    index($4, v1) || index($4, v2) || index($4, v3) { print }
  ' || true
  echo
  tail -n 20 "$log_dir/auth.log" 2>/dev/null || true
}

start() {
  require_cmd python3
  require_cmd curl
  require_cmd Xvfb
  require_cmd x11vnc
  require_cmd openbox
  require_cmd websockify
  require_cmd "$hermes_bin"
  local novnc_web
  novnc_web="$(find_novnc_web)" || {
    echo "Could not find a noVNC web root (expected vnc.html)." >&2
    exit 1
  }

  assert_not_running xvfb
  assert_not_running openbox
  assert_not_running auth
  assert_not_running x11vnc
  assert_not_running websockify

  if [[ -z "$password" ]]; then
    password="$(generate_password)"
  fi
  x11vnc -storepasswd "$password" "$passwd_file" >/dev/null 2>&1
  chmod 600 "$passwd_file"
  write_metadata

  : >"$log_dir/xvfb.log"
  : >"$log_dir/openbox.log"
  : >"$log_dir/auth.log"
  : >"$log_dir/x11vnc.log"
  : >"$log_dir/websockify.log"

  start_bg xvfb env XDG_RUNTIME_DIR="$runtime_dir" XAUTHORITY="$session_dir/.Xauthority" \
    Xvfb "$display" -screen 0 "$screen" -ac -nolisten tcp
  sleep 1
  if ! is_pid_live "$(read_pid "$(pid_file xvfb)")"; then
    echo "Xvfb failed to start. Check $log_dir/xvfb.log" >&2
    exit 1
  fi

  start_bg openbox env DISPLAY="$display" XDG_RUNTIME_DIR="$runtime_dir" HOME="$HOME" openbox
  sleep 1
  if ! is_pid_live "$(read_pid "$(pid_file openbox)")"; then
    echo "openbox failed to start. Check $log_dir/openbox.log" >&2
    exit 1
  fi

  start_bg x11vnc env DISPLAY="$display" XDG_RUNTIME_DIR="$runtime_dir" HOME="$HOME" \
    x11vnc -display "$display" -rfbport "$vnc_port" -rfbauth "$passwd_file" -forever -shared
  if ! wait_for_tcp 127.0.0.1 "$vnc_port" 30 1; then
    echo "x11vnc failed to open port $vnc_port. Check $log_dir/x11vnc.log" >&2
    exit 1
  fi

  start_bg websockify env XDG_RUNTIME_DIR="$runtime_dir" HOME="$HOME" \
    websockify --web="$novnc_web" "$bind_host:$web_port" "127.0.0.1:$vnc_port"
  if ! wait_for_http "http://127.0.0.1:$web_port/vnc.html" 30 1; then
    echo "websockify/noVNC failed to start. Check $log_dir/websockify.log" >&2
    exit 1
  fi

  start_bg auth env DISPLAY="$display" XDG_RUNTIME_DIR="$runtime_dir" HOME="$HOME" HERMES_HOME="$hermes_home" \
    HERMES_CHATGPT_WEB_BROWSER_BASE_DIR="$browser_runtime_dir" \
    "$hermes_bin" auth browser chatgpt-web --timeout "$timeout_seconds" --debug-port "$debug_port" --keep-open

  if ! wait_for_http "http://127.0.0.1:$debug_port/json/version" 90 1; then
    echo "Browser did not open a DevTools endpoint on port $debug_port. Check $log_dir/auth.log" >&2
    exit 1
  fi

  cat <<EOF
Started ChatGPT Web browser auth session.
URL: $(awk -F= '$1=="NO_VNC_URL"{print $2}' "$meta_file")
Password: $password
Display: $display
VNC port: $vnc_port
Web port: $web_port
Debug port: $debug_port
Session dir: $session_dir

Use:
  $(basename "$0") status --session-dir $session_dir
  $(basename "$0") stop --session-dir $session_dir
EOF
}

stop() {
  stop_pid_file websockify websockify || true
  stop_pid_file x11vnc x11vnc || true
  stop_pid_file auth "hermes auth browser chatgpt-web" || true
  stop_pid_file openbox openbox || true
  stop_pid_file xvfb "Xvfb $display" || true
  echo "Stopped tracked remote auth session in $session_dir"
}

case "$action" in
  start) start ;;
  status) status ;;
  stop) stop ;;
  *)
    echo "Unknown action: $action" >&2
    usage >&2
    exit 2
    ;;
esac
