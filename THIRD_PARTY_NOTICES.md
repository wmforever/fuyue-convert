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

## Mozilla PDF.js

- Package: `pdfjs-dist` 5.4.149
- Source: `mozilla/pdf.js`
- Runtime use: local, in-browser PDF rendering for the watermark preview
- License: Apache License 2.0

The PDF.js worker and viewer library are bundled with the frontend assets and do not load code or documents from a third-party CDN.

## Electron and Chromium

- Electron source: `electron/electron`
- Runtime version: `44.0.0`
- Electron license: MIT
- Copyright: Electron contributors and GitHub Inc.

Electron is included only in desktop application packages. Its runtime carries the full Electron license and Chromium's generated `LICENSES.chromium.html`; those files remain inside the packaged application.

## electron-builder

- Source: `electron-userland/electron-builder`
- Build-time version: `26.15.3`
- License: MIT

`electron-builder` is used only to assemble desktop artifacts and is not part of the application conversion engine.
