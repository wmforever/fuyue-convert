# Synthetic desktop smoke fixtures

`smoke.txt` is synthetic application smoke-test text.

`ocr-smoke.png` is a generated image containing only `文档转换 12345`; it contains no user document content. It was rendered with the repository's Droid Sans Fallback (Chinese) and Liberation Sans (digits). The original font licenses remain in `task-service/src/main/resources/fonts/`.

The OCR packager recognizes this PNG with `chi_sim+eng` and verifies the complete TSV text. It must work after copying the runtime into a directory containing spaces, without a system Tesseract installation or third-party library search path.
