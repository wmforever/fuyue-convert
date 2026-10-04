#!/usr/bin/env bash
# Linux cloud review only. Requires prepared pinned tools/runtime and npm dependencies.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${FORMAT_CONVERTER_APP_HOME:?Set the prepared app-home containing ocr/OCR-RUNTIME.json}"
: "${FORMAT_CONVERTER_OFFICE_BINARY:?Set the tested Linux Office executable}"
test -x "$FORMAT_CONVERTER_OFFICE_BINARY"
export FORMAT_CONVERTER_OCR_ENABLED=true
export FORMAT_CONVERTER_OCR_LANGUAGES=chi_sim+eng
export FORMAT_CONVERTER_OCR_MAX_CONCURRENCY=1
unset FORMAT_CONVERTER_TESSERACT_BINARY
node --input-type=module -e 'import {verifyOcrRuntime} from "./desktop/scripts/lib/ocr-runtime.mjs"; await verifyOcrRuntime(process.env.FORMAT_CONVERTER_APP_HOME+"/ocr")'
npm --prefix frontend run build
mvn -B -ntp -Dskip.frontend=true clean package
