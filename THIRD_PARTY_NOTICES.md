# Third-Party Notices

## Scope and binary-release status

The source code authored for Fuyue Convert is licensed under Apache License 2.0.
Third-party libraries, fonts, runtimes, native tools, and generated application
bundles remain subject to their own licenses. The project is therefore not a
claim that every assembled binary is distributed solely under Apache-2.0.

Public binary redistribution is currently paused. The release workflow on the
current default branch is fail-closed until the repository variable
`FORMAT_CONVERTER_BINARY_RELEASE_APPROVED` is explicitly enabled after a
component-level license, provenance, checksum, notice, and source-obligation
review. Historical releases, workflow artifacts, and tags must also be reviewed
or removed before the repository is made public. This file is a human-readable
disclosure for the source tree; it is not by itself an approval to redistribute
a generated runtime package.

## OFDRW and its conversion dependency chain

- OFDRW modules: `org.ofdrw:*` 2.3.9
- Source: `ofdrw/ofdrw`
- Declared license: Apache License 2.0

The current signed-seal appearance path also uses dependencies pulled by
`ofdrw-converter` and invoked directly by the project:

- iText 7 `kernel`, `io`, `commons`, `layout`, and `font-asian` 7.2.6 — GNU
  Affero General Public License v3 according to the upstream parent POM, unless
  covered by a separate commercial license.
- UJMP Core 0.3.0 — GNU Lesser General Public License. The artifact contains
  the LGPL v3 text while its embedded notice states LGPL v2 or later; a binary
  release must resolve and document the applicable terms.
- JSON-java `org.json:json:20141113` — The JSON License, including its
  additional use restriction.

These dependencies are among the unresolved reasons binary releases remain
disabled. Do not publish the
fat JAR, runtime archive, container image, or installer as an Apache-2.0-only
artifact. The preferred remediation is to replace the iText-dependent path;
any alternative distribution model requires a dedicated license review and
complete corresponding notices/source obligations.

## Tesseract OCR and tessdata

- Tesseract source: `tesseract-ocr/tesseract`
- Language models: `tesseract-ocr/tessdata_fast`
- Runtime location in platform packages: `app/ocr/`
- License: Apache License 2.0

When explicitly enabled for local verification, packaging scripts obtain the engine from the operating-system package manager, copy its required native runtime, and include only the selected `eng`, `chi_sim`, `chi_sim_vert`, and supporting `osd` data. Windows model downloads are pinned to a source commit and verified by SHA-256 before packaging.

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

Official release automation, when re-enabled, requires a Java 17 runtime from
Eclipse Temurin/Adoptium and preserves its `legal/` tree. A locally detected
Oracle or otherwise unreviewed JDK must not be reused as a public runtime
bundle merely because `jlink` can process it.

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
