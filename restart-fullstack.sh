#!/usr/bin/env bash
set -euo pipefail

SKIP_FRONTEND_BUILD="${SKIP_FRONTEND_BUILD:-false}"
SKIP_BACKEND_BUILD="${SKIP_BACKEND_BUILD:-false}"
FOREGROUND="${FOREGROUND:-false}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

resolve_repo_path() {
  local value="$1"
  local fallback="$2"

  if [[ -z "$value" ]]; then
    value="$fallback"
  fi

  if [[ "$value" = /* ]]; then
    echo "$value"
  else
    echo "$ROOT_DIR/$value"
  fi
}

list_listening_pids() {
  local port="$1"

  if command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null || true
    return
  fi

  if command -v ss >/dev/null 2>&1; then
    ss -ltnp 2>/dev/null | awk -v p=":$port" '$4 ~ p {print $6}' | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' || true
  fi
}

export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-8080}"
export DATA_DIR="$(resolve_repo_path "${DATA_DIR:-}" "data")"
export WEB_UI_DIR="$(resolve_repo_path "${WEB_UI_DIR:-}" "web-ui/build/client")"
export ASSETS_DIR="$(resolve_repo_path "${ASSETS_DIR:-}" "app/src/main/assets")"
export JWT_ENABLED="${JWT_ENABLED:-false}"
export ACCESS_PASSWORD="${ACCESS_PASSWORD:-}"

if [[ -x ".tools/gradle-9.1.0/bin/gradle" ]]; then
  GRADLE_EXE=".tools/gradle-9.1.0/bin/gradle"
else
  GRADLE_EXE="./gradlew"
fi

TMP_DIR=".tmp-backend"
PID_FILE="$TMP_DIR/backend.pid"
OUT_LOG="$TMP_DIR/backend.out.log"
ERR_LOG="$TMP_DIR/backend.err.log"
mkdir -p "$TMP_DIR"

stop_existing_backend() {
  if [[ -f "$PID_FILE" ]]; then
    old_pid="$(cat "$PID_FILE" || true)"
    if [[ -n "${old_pid}" ]] && kill -0 "$old_pid" 2>/dev/null; then
      kill "$old_pid" 2>/dev/null || true
      sleep 1
      kill -9 "$old_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi

  pids="$(list_listening_pids "$PORT")"
  if [[ -n "$pids" ]]; then
    for p in $pids; do
      kill "$p" 2>/dev/null || true
    done
  fi
}

echo "[1/4] Stopping existing backend on port $PORT..."
stop_existing_backend

if [[ "$SKIP_FRONTEND_BUILD" != "true" ]]; then
  echo "[2/4] Building web-ui..."
  (cd web-ui && npm run build)
else
  echo "[2/4] Skipped web-ui build"
fi

if [[ "$SKIP_BACKEND_BUILD" != "true" ]]; then
  echo "[3/4] Building backend classes..."
  "$GRADLE_EXE" :backend-server:classes --no-daemon
else
  echo "[3/4] Skipped backend build"
fi

if [[ "$FOREGROUND" == "true" ]]; then
  echo "[4/4] Starting backend in foreground..."
  exec "$GRADLE_EXE" :backend-server:run --no-daemon
fi

echo "[4/4] Starting backend in background..."
rm -f "$OUT_LOG" "$ERR_LOG"
nohup "$GRADLE_EXE" :backend-server:run --no-daemon >"$OUT_LOG" 2>"$ERR_LOG" &
launcher_pid=$!

health_url="http://127.0.0.1:${PORT}/api/system/health"
ready=false
for _ in $(seq 1 90); do
  if curl -fsS "$health_url" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done

if [[ "$ready" != "true" ]]; then
  echo "Backend failed to become healthy. Recent logs:"
  tail -n 80 "$OUT_LOG" 2>/dev/null || true
  tail -n 80 "$ERR_LOG" 2>/dev/null || true
  exit 1
fi

backend_pid="$(list_listening_pids "$PORT" | head -n 1 || true)"
if [[ -n "$backend_pid" ]]; then
  echo "$backend_pid" > "$PID_FILE"
else
  echo "$launcher_pid" > "$PID_FILE"
fi

echo "Done."
echo "Launcher PID: $launcher_pid"
if [[ -n "$backend_pid" ]]; then
  echo "Backend PID: $backend_pid"
fi
echo "Web URL: http://127.0.0.1:${PORT}/"
echo "Health: $health_url"
echo "DATA_DIR: $DATA_DIR"
echo "WEB_UI_DIR: $WEB_UI_DIR"
echo "Logs: $OUT_LOG | $ERR_LOG"
