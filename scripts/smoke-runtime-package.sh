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
if [[ -d "$PACKAGE_DIR/app/ocr" && ! -x "$PACKAGE_DIR/app/ocr/bin/tesseract" ]]; then
  echo "发布包含 OCR 目录但缺少可执行引擎" >&2
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
        break
        ;;
    esac
  fi
  sleep 1
done

if [[ "${HEALTH:-}" != *'"status":"UP"'* ]]; then
  echo "等待健康检查超时:" >&2
  cat "$LOG_FILE" >&2
  exit 1
fi
if [[ -d "$PACKAGE_DIR/app/ocr" && "${HEALTH:-}" != *'"bundled":true'* ]]; then
  echo "发布包内置 OCR 未被应用自动发现: $HEALTH" >&2
  exit 1
fi

INPUT_FILE="$WORK_DIR/worker-smoke.txt"
OUTPUT_FILE="$WORK_DIR/worker-smoke.docx"
printf 'runtime worker smoke' > "$INPUT_FILE"
CREATED="$(curl -fsS -X POST -F "files=@$INPUT_FILE;type=text/plain" -F "targetFormat=docx" \
  "http://127.0.0.1:$PORT/api/tasks")"
TASK_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["taskId"])' <<<"$CREATED")"
TASK_STATUS=""
for _ in $(seq 1 60); do
  SNAPSHOT="$(curl -fsS "http://127.0.0.1:$PORT/api/tasks/$TASK_ID")"
  TASK_STATUS="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$SNAPSHOT")"
  if [[ "$TASK_STATUS" == "SUCCESS" || "$TASK_STATUS" == "FAILED" ]]; then break; fi
  sleep 1
done
if [[ "$TASK_STATUS" != "SUCCESS" ]]; then
  echo "发布包 Worker 转换失败: ${SNAPSHOT:-无任务响应}" >&2
  cat "$LOG_FILE" >&2
  exit 1
fi
curl -fsS "http://127.0.0.1:$PORT/api/tasks/$TASK_ID/download" -o "$OUTPUT_FILE"
python3 - "$OUTPUT_FILE" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    xml = archive.read("word/document.xml").decode("utf-8")
if "runtime worker smoke" not in xml:
    raise SystemExit("发布包 DOCX 缺少预期文字")
PY
echo "发布包 Worker 转换通过: $TASK_ID"
