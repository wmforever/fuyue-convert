#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "用法: $0 <runtime-package.tar.gz>" >&2
  exit 2
fi

ARCHIVE="$1"
if [[ ! -f "$ARCHIVE" ]]; then
  echo "发布包不存在: $ARCHIVE" >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${SMOKE_PORT:-18083}"
WORK_DIR="$(mktemp -d)"
LOG_FILE="$WORK_DIR/app.log"
PID=""

cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

tar -xzf "$ARCHIVE" -C "$WORK_DIR"
PACKAGE_DIR="$(find "$WORK_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

if [[ -z "$PACKAGE_DIR" || ! -x "$PACKAGE_DIR/bin/start.sh" ]]; then
  echo "发布包缺少 bin/start.sh" >&2
  exit 1
fi
if [[ ! -x "$PACKAGE_DIR/runtime/bin/java" ]]; then
  echo "发布包缺少内置 Java Runtime" >&2
  exit 1
fi
if [[ ! -f "$PACKAGE_DIR/app/fuyue-convert.jar" ]]; then
  echo "发布包缺少应用 JAR" >&2
  exit 1
fi

"$PACKAGE_DIR/runtime/bin/java" -version >/dev/null

SERVER_PORT="$PORT" AUTO_OPEN_BROWSER=false "$PACKAGE_DIR/bin/start.sh" >"$LOG_FILE" 2>&1 &
PID="$!"

for _ in $(seq 1 45); do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "服务提前退出:" >&2
    cat "$LOG_FILE" >&2
    exit 1
  fi
  if HEALTH="$(curl -fsS "http://127.0.0.1:$PORT/api/health" 2>/dev/null)"; then
    case "$HEALTH" in
      *'"status":"UP"'*)
        echo "$HEALTH"
        exit 0
        ;;
    esac
  fi
  sleep 1
done

echo "等待健康检查超时:" >&2
cat "$LOG_FILE" >&2
exit 1
