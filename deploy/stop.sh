#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$APP_DIR/ofd-to-word.pid"
if [[ ! -f "$PID_FILE" ]]; then echo "服务未运行"; exit 0; fi
PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then rm -f "$PID_FILE"; echo "服务未运行"; exit 0; fi

kill "$PID"
for _ in $(seq 1 30); do
  if ! kill -0 "$PID" 2>/dev/null; then rm -f "$PID_FILE"; echo "服务已停止"; exit 0; fi
  sleep 1
done
echo "服务未在 30 秒内退出，请检查日志"
exit 1

