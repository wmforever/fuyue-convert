# Third-Party Notices

## Scope and binary-release status

The source code authored for Fuyue Convert is licensed under Apache License 2.0.
Third-party libraries, fonts, runtimes, native tools, and generated application
bundles remain subject to their own licenses. The project is therefore not a
claim that every assembled binary is distributed solely under Apache-2.0.

The official public binary path is restricted to the reviewed Windows x64,
macOS Intel, and macOS Apple Silicon Lite desktop packages. They exclude
bundled OCR, Poppler, and LibreOffice. The release
workflow remains fail-closed unless the repository variable
`FORMAT_CONVERTER_BINARY_RELEASE_APPROVED` is deliberately enabled for an
audited tag. The workflow verifies the staged applications, the payload
installed by the final Windows executable, and both mounted macOS disk images.
Component metadata, notices, and source records are embedded in each app;
checksums are published in the Release notes and audit evidence remains in the
workflow artifacts. The main Release contains only the three user installers.

Historical v0.1.0 and v0.1.1 binary assets and expired workflow artifacts were
withdrawn before the repository became public. Locally assembled packages are
not official releases and must not be redistributed merely because they build
successfully.

## OFDRW

- OFDRW modules: `org.ofdrw:*` 2.3.9
- Source: `ofdrw/ofdrw`
- Declared license: Apache License 2.0

The current runtime uses the OFDRW reader, core, package, font, graphics, and
layout modules. It does not include `ofdrw-converter`, iText, UJMP, or the legacy
`org.json:json:20141113` dependency chain. That chain appeared in older local
and historical builds and was removed before the reviewed Lite release.

Image-based PNG/JPEG seal appearances remain supported. A seal appearance that
is itself an embedded OFD document is skipped with an explicit warning while
the document body is preserved; the project does not silently substitute an
unreviewed renderer.

## Tesseract OCR and tessdata

- Tesseract source: `tesseract-ocr/tesseract`
- Language models: `tesseract-ocr/tessdata_fast`
- Runtime location in platform packages: `app/ocr/`
- License: Apache License 2.0

When explicitly enabled for local verification, packaging scripts obtain the engine from the operating-system package manager, copy its required native runtime, and include only the selected `eng`, `chi_sim`, `chi_sim_vert`, and supporting `osd` data. Windows model downloads are pinned to a source commit and verified by SHA-256 before packaging. The official Lite packages do not include this directory.

Tesseract packages also depend on native libraries such as Leptonica and image
codec/archive libraries. Those libraries vary by operating system package
manager and are not yet covered by a fixed, reviewed redistribution manifest.
Consequently, detecting Tesseract on a build machine is not sufficient approval
to publish the copied OCR runtime.

## Poppler

- Source: `freedesktop/poppler`
- Runtime use: optional external `pdftoppm` command for selected fidelity and
  QA routes
- License family: GPL and other component-specific terms; review the exact
  build before redistribution

Poppler is not automatically copied from a Windows build machine into release
packages. Local Electron staging can still include an explicitly supplied
`FORMAT_CONVERTER_POPPLER_HOME`; such a local bundle is not approved for public
redistribution. The official release path must keep Poppler external unless a
future pinned bundle gains a complete version, source, checksum, license, and
corresponding-source review.

## Java Runtime

The official Windows x64 and both native macOS Lite builds pin Eclipse Temurin
17.0.20.1+1, verify vendor, version, build, and architecture, and preserve the
complete `jlink` `legal/` tree. Each application embeds the exact binary/source
provenance, source SHA-256, and a corresponding-source availability statement;
the Release notes link the matching upstream source. A locally detected Oracle
or otherwise unreviewed JDK must not be reused as a public runtime bundle merely
because `jlink` can process it.

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

## Vue.js

- Package: `vue` 3.5.18
- Source: `vuejs/core`
- Runtime use: desktop renderer user interface
- License: MIT

The full Vue license is copied into the desktop package independently of the
minified production assets.

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

## NSIS

- Source: `nsis-dev/nsis`
- Installer compiler version: 3.12
- electron-builder toolset: `nsis@1.2.1`
- License used by the official installer path: zlib/libpng

The exact NSIS source archive, checksum, full license text, and provenance
record are published with the official installer. The release workflow pins
the unified toolset bundle that supplies `makensis` 3.12 instead of the legacy
3.0.4.1 bundle. Electron-builder only prepares `win-unpacked`; the final
installer is compiled from the repository's tracked core-only script using
zlib compression and built-in NSIS instructions. It does not contain StdUtils,
UAC, WinShell, nsProcess, nsis7z, `elevate.exe`, the bzip2 module, or the LZMA
module. Source linting, final-EXE archive inspection, extraction, and installed
payload verification fail the release if a forbidden helper appears.
