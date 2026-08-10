#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "用法: $0 <目标 OCR 目录>" >&2
  exit 2
fi

DEST_DIR="$1"
TESSERACT_BIN="${FORMAT_CONVERTER_TESSERACT_BINARY:-$(command -v tesseract || true)}"
if [[ -z "$TESSERACT_BIN" || ! -x "$TESSERACT_BIN" ]]; then
  echo "未找到可执行的 Tesseract，无法生成内置 OCR 运行时" >&2
  exit 1
fi

LANGUAGES_TEXT="${FORMAT_CONVERTER_BUNDLED_OCR_LANGUAGES:-eng chi_sim chi_sim_vert}"
TESSDATA_SOURCE="${FORMAT_CONVERTER_TESSDATA_SOURCE:-}"
if [[ -z "$TESSDATA_SOURCE" ]]; then
  LIST_HEADER="$($TESSERACT_BIN --list-langs 2>&1 | head -n 1)"
  TESSDATA_SOURCE="$(sed -n 's/.*in "\([^"]*\)".*/\1/p' <<<"$LIST_HEADER")"
fi
if [[ -z "$TESSDATA_SOURCE" || ! -d "$TESSDATA_SOURCE" ]]; then
  echo "无法定位 tessdata；请设置 FORMAT_CONVERTER_TESSDATA_SOURCE" >&2
  exit 1
fi

mkdir -p "$DEST_DIR/bin" "$DEST_DIR/lib" "$DEST_DIR/tessdata"
cp "$TESSERACT_BIN" "$DEST_DIR/bin/tesseract"
chmod +x "$DEST_DIR/bin/tesseract"

for language in $LANGUAGES_TEXT; do
  model="$TESSDATA_SOURCE/$language.traineddata"
  if [[ ! -f "$model" ]]; then
    echo "缺少内置 OCR 语言模型: $model" >&2
    exit 1
  fi
  cp "$model" "$DEST_DIR/tessdata/"
done
if [[ -f "$TESSDATA_SOURCE/osd.traineddata" ]]; then cp "$TESSDATA_SOURCE/osd.traineddata" "$DEST_DIR/tessdata/"; fi
if [[ -d "$TESSDATA_SOURCE/configs" ]]; then cp -RL "$TESSDATA_SOURCE/configs" "$DEST_DIR/tessdata/"; fi
if [[ -d "$TESSDATA_SOURCE/tessconfigs" ]]; then cp -RL "$TESSDATA_SOURCE/tessconfigs" "$DEST_DIR/tessdata/"; fi
if [[ ! -f "$DEST_DIR/tessdata/configs/tsv" ]]; then
  echo "内置 OCR 缺少 TSV 输出配置: $TESSDATA_SOURCE/configs/tsv" >&2
  exit 1
fi

copy_linux_libraries() {
  ldd "$TESSERACT_BIN" | awk '
    /=> \// { print $3 }
    /^\// { print $1 }
  ' | while IFS= read -r library; do
    case "$(basename "$library")" in
      libc.so.*|libm.so.*|libpthread.so.*|libdl.so.*|librt.so.*|ld-linux*.so.*) continue ;;
    esac
    cp -L "$library" "$DEST_DIR/lib/"
  done
}

copy_macos_libraries() {
  local queue=("$TESSERACT_BIN")
  local index=0
  local seen=""
  local brew_prefix
  brew_prefix="$(brew --prefix 2>/dev/null || true)"
  while [[ $index -lt ${#queue[@]} ]]; do
    local current="${queue[$index]}"
    index=$((index + 1))
    while IFS= read -r raw_library; do
      [[ -z "$raw_library" ]] && continue
      case "$raw_library" in
        /System/*|/usr/lib/*) continue ;;
      esac
      local library="$raw_library"
      case "$library" in
        @loader_path/*) library="$(dirname "$current")/${library#@loader_path/}" ;;
        @executable_path/*) library="$(dirname "$TESSERACT_BIN")/${library#@executable_path/}" ;;
        @rpath/*)
          local name="${library#@rpath/}"
          if [[ -f "$(dirname "$current")/$name" ]]; then
            library="$(dirname "$current")/$name"
          elif [[ -n "$brew_prefix" ]]; then
            library="$(find "$brew_prefix" -type f -name "$(basename "$name")" -print -quit)"
          fi
          ;;
      esac
      if [[ ! -f "$library" ]]; then
        echo "无法解析 macOS OCR 动态库: $raw_library (from $current)" >&2
        exit 1
      fi
      if [[ "$seen" == *$'\n'"$library"$'\n'* ]]; then continue; fi
      seen+=$'\n'"$library"$'\n'
      local target="$DEST_DIR/lib/$(basename "$library")"
      if [[ -f "$target" ]]; then continue; fi
      cp -L "$library" "$target"
      queue+=("$library")
    done < <(otool -L "$current" | tail -n +2 | awk '{print $1}')
  done
}

case "$(uname -s)" in
  Linux) copy_linux_libraries ;;
  Darwin) copy_macos_libraries ;;
  *) echo "当前脚本仅支持 Linux/macOS；Windows 使用 prepare-ocr-runtime.ps1" >&2; exit 1 ;;
esac

ENV_ARGS=("TESSDATA_PREFIX=$DEST_DIR/tessdata")
if [[ "$(uname -s)" == "Darwin" ]]; then
  ENV_ARGS+=("DYLD_LIBRARY_PATH=$DEST_DIR/lib")
else
  ENV_ARGS+=("LD_LIBRARY_PATH=$DEST_DIR/lib")
fi
LANG_OUTPUT="$(env "${ENV_ARGS[@]}" "$DEST_DIR/bin/tesseract" --list-langs --tessdata-dir "$DEST_DIR/tessdata")"
for language in $LANGUAGES_TEXT; do
  if ! awk -v expected="$language" '$0 == expected { found=1 } END { exit !found }' <<<"$LANG_OUTPUT"; then
    echo "内置 OCR 自检未发现语言模型: $language" >&2
    exit 1
  fi
done

"$DEST_DIR/bin/tesseract" --version | head -n 1
echo "已生成内置 OCR 运行时: $DEST_DIR"
