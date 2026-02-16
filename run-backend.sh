#!/usr/bin/env bash
set -euo pipefail

export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-8080}"
export DATA_DIR="${DATA_DIR:-data}"
export WEB_UI_DIR="${WEB_UI_DIR:-web-ui/build/client}"
export ASSETS_DIR="${ASSETS_DIR:-assets}"
export JWT_ENABLED="${JWT_ENABLED:-false}"
export ACCESS_PASSWORD="${ACCESS_PASSWORD:-}"

./gradlew :backend-server:run --no-daemon
