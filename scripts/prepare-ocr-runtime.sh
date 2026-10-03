#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo "用法: $0 <目标 OCR 目录>" >&2
  exit 2
fi
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec node "$ROOT_DIR/desktop/scripts/prepare-ocr-runtime.mjs" "$1"
