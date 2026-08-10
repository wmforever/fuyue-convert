#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$APP_DIR/format-converter.pid"
LOG_DIR="$APP_DIR/logs"
JAR_FILE="${FORMAT_CONVERTER_JAR:-$(find "$APP_DIR" -maxdepth 1 -name 'web-api-*.jar' ! -name '*.original' | head -n 1)}"

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "服务已运行，PID=$(cat "$PID_FILE")"
  exit 0
fi
if [[ -z "$JAR_FILE" || ! -f "$JAR_FILE" ]]; then
  echo "未找到可运行 JAR，请设置 FORMAT_CONVERTER_JAR"
  exit 1
fi

mkdir -p "$LOG_DIR"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -Djava.awt.headless=true}"
if [[ -f "$APP_DIR/application.yml" ]]; then
  nohup "$JAVA_BIN" $JAVA_OPTS -Dformat.converter.app.home="$APP_DIR" -jar "$JAR_FILE" \
    "--spring.config.additional-location=$APP_DIR/application.yml" >"$LOG_DIR/console.log" 2>&1 &
else
  nohup "$JAVA_BIN" $JAVA_OPTS -Dformat.converter.app.home="$APP_DIR" -jar "$JAR_FILE" >"$LOG_DIR/console.log" 2>&1 &
fi
echo $! > "$PID_FILE"
echo "服务已启动，PID=$!，日志=$LOG_DIR/console.log"
