# Third-Party Notices

## Tesseract OCR and tessdata

- Tesseract source: `tesseract-ocr/tesseract`
- Language models: `tesseract-ocr/tessdata_fast`
- Runtime location in platform packages: `app/ocr/`
- License: Apache License 2.0

Platform release workflows obtain the engine from the operating-system package manager and bundle only the selected `eng`, `chi_sim`, `chi_sim_vert`, and supporting `osd` data. Windows model downloads are pinned to a source commit and verified by SHA-256 before packaging.

## Droid Sans Fallback

- File: `task-service/src/main/resources/fonts/DroidSansFallback.ttf`
- Source: Android Open Source Project, `platform/frameworks/base/data/fonts/DroidSansFallback.ttf`
- Source revision: `1cdfff555f4a21f71ccc978290e2e212e2f8b168`
- SHA-256: `21b96a0377f067833a93af3082eb28d4ffab7a8cd46bfd513286f1d64b7b0949`
- License: Apache License 2.0

The complete upstream notice is distributed beside the font as
`task-service/src/main/resources/fonts/DroidSansFallback-NOTICE.txt`.

## Liberation Sans Regular

- File: `task-service/src/main/resources/fonts/LiberationSans-Regular.ttf`
- Source: Liberation Fonts 2.1.5
- SHA-256: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`
- License: SIL Open Font License 1.1

The complete upstream license is distributed beside the font as
`task-service/src/main/resources/fonts/LiberationSans-LICENSE.txt`.
