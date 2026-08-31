# Fuyue Convert

[Simplified Chinese](README.md) | [English](README_EN.md)

[![CI](https://github.com/wmforever/fuyue-convert/actions/workflows/ci.yml/badge.svg)](https://github.com/wmforever/fuyue-convert/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/source%20license-Apache--2.0-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-4c8cbf.svg)](pom.xml)

Fuyue Convert is an open-source document format conversion platform. It focuses on auditable, replaceable, local conversion routes for office documents, fixed-layout documents, images, spreadsheets, OFD, PDF, and domestic document formats.

The project is built with Java 17, Spring Boot, Vue 3, Apache POI, PDFBox, Poppler, OFDRW, and LibreOffice headless. It does not promise that every format can be converted with perfect visual fidelity and full structural editability. Instead, each route has an explicit status, quality goal, and failure reason so results can be verified and improved by the community.

> Project-authored source is Apache-2.0; build dependencies remain under their own licenses. The official Windows x64, macOS Intel, and macOS Apple Silicon Lite desktop builds pass dedicated provenance, license, hash, installed-payload, and smoke-test gates. See [Third-Party Notices](THIRD_PARTY_NOTICES.md).

## Download the desktop app

| System | Download |
| --- | --- |
| Windows 10/11 x64 | [Fuyue-Convert-0.1.5-win-x64.exe](https://github.com/wmforever/fuyue-convert/releases/download/v0.1.5/Fuyue-Convert-0.1.5-win-x64.exe) |
| macOS 13+, Intel | [Fuyue-Convert-0.1.5-macOS-Intel.dmg](https://github.com/wmforever/fuyue-convert/releases/download/v0.1.5/Fuyue-Convert-0.1.5-macOS-Intel.dmg) |
| macOS 13+, Apple Silicon | [Fuyue-Convert-0.1.5-macOS-Apple-Silicon.dmg](https://github.com/wmforever/fuyue-convert/releases/download/v0.1.5/Fuyue-Convert-0.1.5-macOS-Apple-Silicon.dmg) |

All three packages include an Eclipse Temurin Java Runtime, so no separate Java installation is required. The Release asset list contains only these three user-facing installers. Licenses, component inventories, and provenance are embedded; SHA-256 values are in the [v0.1.5 Release](https://github.com/wmforever/fuyue-convert/releases/tag/v0.1.5) notes, while audit evidence remains in the workflow run. GitHub's two automatic Source code entries cannot be hidden.

This is a Lite build: OCR/Tesseract, Poppler, and LibreOffice are not bundled. Core PDF routes have a built-in fallback, OCR routes report unavailable, and high-fidelity Office routes use an existing local LibreOffice installation. Windows may show SmartScreen or an unknown-publisher warning. The macOS packages are ad-hoc signed and not Apple-notarized; if first launch is blocked, use System Settings → Privacy & Security → Open Anyway.

## Positioning

- Open-source first: use local open-source libraries and command line tools by default.
- Extensible routes: each conversion capability is implemented as an independent `FileConverter`.
- Transparent route quality: the capabilities API returns `stable`, `beta`, `experimental`, dependencies, and limitations.
- Verifiable quality: provide visual comparison and data round-trip QA scripts.
- Transparent failures: unsupported formats, page mismatches, font substitution, image fallbacks, and other limitations are surfaced as errors or warnings.
- Clear trade-offs: editability-first and fidelity-first conversions are different goals and are marked explicitly.

## Current Matrix

Status legend:

- `stable`: covered by automated tests with clear input boundaries.
- `beta`: primary route works, but real documents may vary because of fonts, engines, or format-specific features.
- `experimental`: route is wired in, but output quality and compatibility still need more samples.

| Route | Status | Default strategy | Notes |
| --- | --- | --- | --- |
| OFD -> DOCX/TXT/PDF/PNG/JPG | beta | structure/layout | DOCX and TXT use structured parsing. DOCX files containing CJK text embed a licensed fallback font so glyphs remain visible on another machine. Scanned pages fail when OCR is not configured; local Tesseract can add positioned text from scan images. PDF/PNG/JPEG paint text, images, paths, and ordinary image-based seals at source coordinates. Embedded-OFD seal appearances are skipped with an explicit warning while body content is preserved. Multi-page images are zipped. |
| OFD -> XLSX | experimental | data first | Writes high-confidence bordered grid tables as real cells, per-page worksheets, and merged regions. It returns `NO_TABLE_FOUND` when no reliable table is found and `OCR_REQUIRED` for scanned pages. |
| CSV <-> XLSX | stable | data first | CSV supports UTF-8, BOM-marked UTF-16, GB18030, and comma/TAB/semicolon/pipe detection. Inputs stay text to prevent formula injection. XLSX exports cached formula results, formatted dates, and multi-sheet CSV ZIPs. |
| DOCX/XLSX/PPTX -> PDF | beta | layout first | Uses an isolated LibreOffice headless profile when available and validates the actual PDF page count. Local fonts affect visual output. |
| TXT -> DOCX/PDF | stable | content first | Supports UTF-8, BOM-marked UTF-16, and strict GB18030 decoding. Form feeds create real page breaks and PDF wraps CJK by glyph width. DOCX embeds the licensed fallback on demand unless an explicit East Asian font is configured. |
| DOCX -> TXT | beta | text extraction | Extracts paragraphs and tables in body order, then labeled headers, footers, text boxes, footnotes, endnotes, comments, and tracked revisions; layout is not retained. |
| PDF -> TXT | beta | extraction | Extracts text by page coordinates and multi-column reading order with page boundaries. Scanned pages fail with `OCR_REQUIRED` by default; explicit local OCR fills only content pages that have no real text. |
| PDF -> PNG/JPG | stable | rendering | Defaults to 160 DPI (configurable from 36-600); PNG preserves a transparent canvas, JPEG converts to RGB at 0.9 quality, and multi-page output is zipped. |
| PDF -> DOCX | beta | editability first | Restores real text, basic paragraphs, page sizes, and orientation. CJK output embeds the licensed Droid Sans Fallback while still avoiding full-page images. Scanned pages fail by default; explicit local OCR converts them to positioned editable text. |
| PDF -> OFD | experimental | fidelity first | Produces a real OFD package. A 160-DPI page image preserves appearance, while text PDFs also receive source-positioned OFD text objects. Poppler is preferred with a PDFBox fallback; complex objects are not yet reconstructed individually. |
| PDF compression/watermark | beta | fidelity first | Compression provides lossless, balanced, and strong modes, with source preview and real-result preview after completion. Watermarks support bilingual text, opacity, angle, color, position, tiling, page ranges, and a local live preview. Editing submitted settings requires regeneration. Both routes reject digitally signed PDFs. |
| PDF merge/split | stable | fidelity first | Merge follows upload order and keeps source preview bound during reordering. Split previews and validates page ranges before producing a numbered ZIP. Both operations rewrite the PDF and do not preserve digital-signature validity. |
| PNG/JPG -> PDF | stable | layout first | Reads PNG pHYs, JPEG JFIF/EXIF DPI, and EXIF orientation; transparent PNG composition is preserved. Missing DPI defaults to 96 with a warning. Same-format batches merge in upload order, show source-image order, and preview the real PDF result after conversion. |
| PNG/JPG -> TXT/DOCX | experimental/on demand | OCR extraction | Source/JAR deployments can use an explicitly configured system Tesseract. A future reviewed runtime may bundle a fixed OCR engine. TXT emits recognized text; DOCX maps positioned OCR text through `DocumentModel` to real editable text. Both expose page confidence and OCR warnings. |
| WPS/ET/DPS/UOF -> OOXML | experimental | compatibility first | Depends on LibreOffice import support. UOF is converted directly to editable DOCX, so pagination and object positions may change. |
| DOCX -> UOF | experimental | compatibility first | When LibreOffice is available, uses the explicit `UOF text` export filter to write real UOF XML and validates the UOF root. Paragraph and table text are covered by a LibreOffice reopen round trip. |

External dependencies:

- Result preview: size-bounded PDF, PNG/JPEG, TXT, and CSV results are loaded from the real output. PDF pages render lazily; text never executes HTML, links, or formulas. ZIP and oversized results remain download-only.
- LibreOffice: used for Office-engine conversions involving DOCX/XLSX/PPTX/WPS/ET/DPS/UOF and PDF. Image-to-PDF uses the built-in PDFBox route for deterministic DPI and EXIF handling.
- Poppler: used for PDF to PNG/JPEG rendering and visual regression checks.
- `FORMAT_CONVERTER_IMAGE_DPI`: PDF image-export resolution, default `160`, allowed range `36-600`; invalid configuration fails explicitly when the converter starts.
- `FORMAT_CONVERTER_OFFICE_REQUIRED_VERSION`: optional LibreOffice version lock fragment such as `24.8`. A mismatching `--version` marks the Office engine unavailable; the detected version is exposed by `/api/health` and `/api/diagnostics`.
- Source/standalone-JAR deployments can set `FORMAT_CONVERTER_OCR_ENABLED=true` and optionally select a system Tesseract binary. OCR is invoked only by explicit image OCR routes or detected scan pages. Set the value to `false` to disable it. Health and diagnostics expose `ocr.bundled` when a reviewed built-in runtime is present, along with versions, models, limits, confidence thresholds, and capability errors.
- OCR never replaces native PDF/OFD parsing and is not used for fixed-layout rendering. Mixed documents are processed page by page. Missing page models, no recognized text, confidence below the hard threshold, timeouts, and resource termination return `OCR_PAGE_MISSING`, `OCR_NO_TEXT`, `OCR_LOW_CONFIDENCE`, `OCR_TIMEOUT`, and `OCR_RESOURCE_EXHAUSTED` instead of publishing partial output.
- System fonts still affect pagination, line spacing, and font substitution in Office output. PDF/OFD-to-DOCX embeds the project's licensed CJK fallback to keep basic glyphs visible, although its metrics and design may differ from the source font. Basic PDF output routes also include fallback fonts, and a custom TrueType font can be selected with `FORMAT_CONVERTER_PDF_FONT`. TXT -> DOCX font names can be configured with `FORMAT_CONVERTER_DOCX_FONT` and `FORMAT_CONVERTER_DOCX_CJK_FONT`; without an explicit East Asian font, it also embeds the bundled fallback on demand.

See [docs/quality-standard.md](docs/quality-standard.md) for quality definitions.
See [docs/ocr-deployment.md](docs/ocr-deployment.md) for the deployment ownership and enablement contract of the optional local OCR engine.
See [docs/test-report.md](docs/test-report.md) for the committed test summary. Full local QA generates the ignored `qa-samples/report/qa-report.md`.

## Quick Start

Requirements:

- JDK 17
- Maven 3.9+
- Optional: LibreOffice or `soffice`
- Optional: Poppler `pdftoppm`
- Optional for source/standalone JAR: Tesseract 5.x and models. A local Docker build installs OCR from the distribution package manager; public image releases remain paused.

If `pdftoppm` is not on `PATH`, set `PDFTOPPM_BIN=/absolute/path/to/pdftoppm`. OFD image, PDF-to-OFD, and PDF-to-JPEG routes prefer Poppler and fall back to PDFBox when it is unavailable; PDF-to-PNG always uses PDFBox to preserve transparency semantics.

Clone and build:

```bash
git clone https://github.com/wmforever/fuyue-convert.git
cd fuyue-convert
mvn clean verify
```

Run:

```bash
java -jar web-api/target/web-api-*.jar
```

Open:

```text
http://127.0.0.1:8080
```

Health check:

```bash
curl --fail http://127.0.0.1:8080/api/health
```

Frontend hot-reload development (keep the backend on port 8080):

```bash
cd frontend
npm ci --no-audit --no-fund
npm run dev
```

## Desktop Application

`desktop/` is an independent Electron shell with a local dark-workbench UI. It
starts the same Java/Spring conversion service on a random loopback port rather
than reimplementing converters in JavaScript.

Development preview, with Java and Vite already running:

```bash
cd desktop
npm ci --no-audit --no-fund
npm run dev
```

The audited tag workflow produces exactly three official Lite installers:
Windows x64, macOS Intel, and macOS Apple Silicon. Locally generated installers
are development artifacts and are not official releases. See
[desktop/README.md](desktop/README.md).

## Local Runtime Package Build

The following script builds a Java-runtime bundle for local verification:

```bash
bash scripts/package-runtime.sh
```

Generated files are placed under `dist/`:

- macOS/Linux: `fuyue-convert-<version>-<os>-<arch>.tar.gz`, then run `start.command` or `bin/start.sh`.
- Windows: run `scripts/package-runtime.ps1` locally. The script no longer copies an arbitrary Poppler installation from the build machine; use the PDFBox fallback or explicitly configure a system `pdftoppm` at runtime.

> These generic runtime packages are for local engineering validation and are not official public downloads. Official desktop binaries are limited to the three Lite installers linked above. Do not upload existing local `dist/`, `desktop/release/`, or `qa-samples/` artifacts.

After startup, open:

```text
http://127.0.0.1:8080
```

Runtime packages open the browser by default. To disable it:

```bash
AUTO_OPEN_BROWSER=false ./bin/start.sh
```

Each file is converted in an independent JVM worker by default. The API process relays progress and enforces timeout and process-tree cleanup. Production settings:

```bash
FORMAT_CONVERTER_WORKER_ENABLED=true
FORMAT_CONVERTER_WORKER_MAX_MEMORY_MB=768
FORMAT_CONVERTER_WORKER_JAVA_BINARY=/path/to/java
FORMAT_CONVERTER_MAX_FILES_PER_TASK=100
FORMAT_CONVERTER_MAX_TASK_UPLOAD_BYTES=262144000
FORMAT_CONVERTER_MAX_TASK_OUTPUT_BYTES=536870912
FORMAT_CONVERTER_MIN_FREE_DISK_BYTES=536870912
FORMAT_CONVERTER_RESULT_TTL=24h
```

For a managed deployment, keep the JAR, `deploy/application.yml.example`, and
the management scripts together, or pass an external configuration explicitly:

```bash
./start.sh
./status.sh
./stop.sh
java -jar app.jar --spring.config.additional-location=./application.yml
```

The service binds to `127.0.0.1` by default. A non-loopback address such as `SERVER_ADDRESS=0.0.0.0` requires a `FORMAT_CONVERTER_API_TOKEN` of at least 32 characters with no surrounding whitespace, or startup fails. `FORMAT_CONVERTER_ALLOW_INSECURE_REMOTE=true` is an explicit escape hatch only when an outer network boundary already isolates the service. Production deployments should also use a TLS reverse proxy. Protected task APIs accept `X-Format-Converter-Token: <token>` or `Authorization: Bearer <token>`. File-count, size, output, disk, and task limits are enforced; JVM heap limits do not replace Docker/cgroup or systemd CPU, total-memory, and process limits.

## QA

Build an executable JAR first:

```bash
mvn -DskipTests package
```

Run end-to-end QA:

```bash
python3 qa-samples/run_qa.py
```

The complete corpus is not distributed with the repository. Before starting a service, the script reports every missing required fixture; the [English QA guide](qa-samples/README.md#english) documents required/optional files and the licensing/privacy boundary. When the corpus is present, QA uploads through HTTP and performs route-specific data or visual comparisons using LibreOffice/Poppler.

## Modules

- `layout-model`: library-independent page, text, line, paragraph, table, and warning models.
- `ofd-parser`: safe extraction, `OfdParser`/`OcrEngine` SPI, and OFDRW adapter.
- `table-recognizer`: line normalization, grids, merged cells, and text assignment.
- `docx-renderer`: DOCX generation with POI/OOXML.
- `task-service`: converter registration, async task state, batch conversion, ZIP output, cleanup, and restart recovery.
- `web-api`: Spring Boot REST API and bundled Vue 3 frontend.
- `qa-samples`: sample-driven end-to-end QA scripts and local test samples.

## Contributing

Contributions are welcome for parsers, converters, synthetic fixtures, compatibility reports, and failure cases. Read the [Contributing Guide](CONTRIBUTING.md), [Quality Standard](docs/quality-standard.md), [Security Policy](SECURITY.md), and [Code of Conduct](CODE_OF_CONDUCT.md) first. Do not report suspected vulnerabilities in a public Issue.

## Sponsorship

If this project helps you, sponsorship is welcome.

[Sponsor list](docs/sponsors.md)

<p>
  <img src="docs/assets/sponsor-wechat.png" alt="WeChat sponsor QR code" width="360">
  <img src="docs/assets/sponsor-alipay.png" alt="Alipay sponsor QR code" width="360">
</p>

Please verify the recipient information before scanning.

## License

Project-authored Fuyue Convert source is licensed under Apache License 2.0; see [LICENSE](LICENSE). Dependencies, fonts, external tools, and assembled applications remain under their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Do not redistribute local binary builds while the listed binary-release blockers remain open.
