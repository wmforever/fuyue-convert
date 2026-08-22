# Fuyue Convert

[Simplified Chinese](README.md) | [English](README_EN.md)

Fuyue Convert is an open-source document format conversion platform. It focuses on auditable, replaceable, local conversion routes for office documents, fixed-layout documents, images, spreadsheets, OFD, PDF, and domestic document formats.

The project is built with Java 17, Spring Boot, Vue 3, Apache POI, PDFBox, Poppler, OFDRW, and LibreOffice headless. It does not promise that every format can be converted with perfect visual fidelity and full structural editability. Instead, each route has an explicit status, quality goal, and failure reason so results can be verified and improved by the community.

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
| OFD -> DOCX/TXT/PDF/PNG/JPG | beta | structure/layout | DOCX and TXT use structured parsing. DOCX files containing CJK text embed a licensed fallback font so glyphs remain visible on another machine. Scanned pages fail when OCR is not configured; local Tesseract can add positioned text from scan images. PDF/PNG/JPEG paint text, images, seal appearances, and paths at source coordinates; multi-page images are zipped. |
| OFD -> XLSX | experimental | data first | Writes high-confidence bordered grid tables as real cells, per-page worksheets, and merged regions. It returns `NO_TABLE_FOUND` when no reliable table is found and `OCR_REQUIRED` for scanned pages. |
| CSV <-> XLSX | stable | data first | CSV supports UTF-8, BOM-marked UTF-16, GB18030, and comma/TAB/semicolon/pipe detection. Inputs stay text to prevent formula injection. XLSX exports cached formula results, formatted dates, and multi-sheet CSV ZIPs. |
| DOCX/XLSX/PPTX -> PDF | beta | layout first | Uses an isolated LibreOffice headless profile when available and validates the actual PDF page count. Local fonts affect visual output. |
| TXT -> DOCX/PDF | stable | content first | Supports UTF-8, BOM-marked UTF-16, and strict GB18030 decoding. Form feeds create real page breaks and PDF wraps CJK by glyph width. DOCX embeds the licensed fallback on demand unless an explicit East Asian font is configured. |
| DOCX -> TXT | beta | text extraction | Extracts paragraphs and tables in body order, then labeled headers, footers, text boxes, footnotes, endnotes, comments, and tracked revisions; layout is not retained. |
| PDF -> TXT | beta | extraction | Extracts text by page coordinates and multi-column reading order with page boundaries. Scanned pages fail with `OCR_REQUIRED` by default; explicit local OCR fills only content pages that have no real text. |
| PDF -> PNG/JPG | stable | rendering | Defaults to 160 DPI (configurable from 36-600); PNG preserves a transparent canvas, JPEG converts to RGB at 0.9 quality, and multi-page output is zipped. |
| PDF -> DOCX | beta | editability first | Restores real text, basic paragraphs, page sizes, and orientation. CJK output embeds the licensed Droid Sans Fallback while still avoiding full-page images. Scanned pages fail by default; explicit local OCR converts them to positioned editable text. |
| PDF -> OFD | experimental | fidelity first | Produces a real OFD package. A 160-DPI page image preserves appearance, while text PDFs also receive source-positioned OFD text objects. Poppler is preferred with a PDFBox fallback; complex objects are not yet reconstructed individually. |
| PNG/JPG -> PDF | stable | layout first | Reads PNG pHYs, JPEG JFIF/EXIF DPI, and EXIF orientation; transparent PNG composition is preserved. Missing DPI defaults to 96 with a warning. Same-format batches merge in upload order. |
| PNG/JPG -> TXT/DOCX | experimental/on demand | OCR extraction | Official runtime bundles and the Docker image include Tesseract; source/JAR deployments can use an explicitly configured system engine. TXT emits recognized text; DOCX maps positioned OCR text through `DocumentModel` to real editable text. Both expose page confidence and OCR warnings. |
| WPS/ET/DPS/UOF -> OOXML | experimental | compatibility first | Depends on LibreOffice import support. UOF is converted directly to editable DOCX, so pagination and object positions may change. |
| DOCX -> UOF | experimental | compatibility first | When LibreOffice is available, uses the explicit `UOF text` export filter to write real UOF XML and validates the UOF root. Paragraph and table text are covered by a LibreOffice reopen round trip. |

External dependencies:

- LibreOffice: used for Office-engine conversions involving DOCX/XLSX/PPTX/WPS/ET/DPS/UOF and PDF. Image-to-PDF uses the built-in PDFBox route for deterministic DPI and EXIF handling.
- Poppler: used for PDF to PNG/JPEG rendering and visual regression checks.
- `FORMAT_CONVERTER_IMAGE_DPI`: PDF image-export resolution, default `160`, allowed range `36-600`; invalid configuration fails explicitly when the converter starts.
- `FORMAT_CONVERTER_OFFICE_REQUIRED_VERSION`: optional LibreOffice version lock fragment such as `24.8`. A mismatching `--version` marks the Office engine unavailable; the detected version is exposed by `/api/health` and `/api/diagnostics`.
- Official runtime bundles carry Tesseract, its native libraries, and the `eng`, `chi_sim`, and `chi_sim_vert` models under `app/ocr`; the application detects and enables this capability automatically. OCR is still invoked only by explicit image OCR routes or detected scan pages. Set `FORMAT_CONVERTER_OCR_ENABLED=false` to disable it. Source/standalone-JAR deployments can set the value to `true` and optionally select a system binary. Health and diagnostics expose `ocr.bundled` along with version, models, limits, confidence thresholds, and capability errors.
- OCR never replaces native PDF/OFD parsing and is not used for fixed-layout rendering. Mixed documents are processed page by page. Missing page models, no recognized text, confidence below the hard threshold, timeouts, and resource termination return `OCR_PAGE_MISSING`, `OCR_NO_TEXT`, `OCR_LOW_CONFIDENCE`, `OCR_TIMEOUT`, and `OCR_RESOURCE_EXHAUSTED` instead of publishing partial output.
- System fonts still affect pagination, line spacing, and font substitution in Office output. PDF/OFD-to-DOCX embeds the project's licensed CJK fallback to keep basic glyphs visible, although its metrics and design may differ from the source font. Basic PDF output routes also include fallback fonts, and a custom TrueType font can be selected with `FORMAT_CONVERTER_PDF_FONT`. TXT -> DOCX font names can be configured with `FORMAT_CONVERTER_DOCX_FONT` and `FORMAT_CONVERTER_DOCX_CJK_FONT`; without an explicit East Asian font, it also embeds the bundled fallback on demand.

See [docs/quality-standard.md](docs/quality-standard.md) for quality definitions.
See [docs/ocr-deployment.md](docs/ocr-deployment.md) for the deployment ownership and enablement contract of the optional local OCR engine.

## Quick Start

Requirements:

- JDK 17
- Maven 3.9+
- Optional: LibreOffice or `soffice`
- Optional: Poppler `pdftoppm`
- Optional for source/standalone JAR: Tesseract 5.x and models; official runtime bundles and Docker include them

If `pdftoppm` is not on `PATH`, set `PDFTOPPM_BIN=/absolute/path/to/pdftoppm`. OFD image, PDF-to-OFD, and PDF-to-JPEG routes prefer Poppler and fall back to PDFBox when it is unavailable; PDF-to-PNG always uses PDFBox to preserve transparency semantics.

Build:

```bash
mvn clean verify
```

Run:

```bash
java -jar web-api/target/web-api-0.1.3.jar
```

Open:

```text
http://127.0.0.1:8080
```

## No-Java Runtime Packages

For non-developer users, build a package with a bundled Java Runtime. The user does not need to install JDK/JRE separately:

```bash
bash scripts/package-runtime.sh
```

Generated files are placed under `dist/`:

- macOS/Linux: `fuyue-convert-<version>-<os>-<arch>.tar.gz`, then run `start.command` or `bin/start.sh`.
- Windows: run `scripts/package-runtime.ps1` on Windows or use GitHub Actions. Releases include a regular ZIP, a portable `*-exe.zip`, and native `.exe` and `.msi` installers; all include a Java Runtime. Installers are currently unsigned, so Windows may show a SmartScreen or unknown-publisher warning.

GitHub Release smoke tests all three platforms with the bundled Runtime: start the service, check `/api/health`, perform a real `TXT -> DOCX` worker conversion, download the result, and verify its content. Windows additionally checks `FuyueConvert.exe`.

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
```

The service binds to `127.0.0.1` by default. Remote deployments must explicitly set `SERVER_ADDRESS=0.0.0.0` and should also configure `FORMAT_CONVERTER_API_TOKEN`; the bundled web UI accepts the token at the bottom of the page. File count, per-file size, total upload, total output, and free-disk watermarks are enforced. Failed and cancelled inputs are retained for the configured TTL starting when the task finishes. The worker memory setting limits the JVM heap only. Apply CPU, total-memory, and process-count limits with Docker/cgroups or systemd at deployment time.

## QA

Build an executable JAR first:

```bash
mvn -DskipTests package
```

Run end-to-end QA:

```bash
python3 qa-samples/run_qa.py
```

The QA script starts a local service, uploads samples through HTTP, downloads converted outputs, renders them with LibreOffice/Poppler, and compares the results. `strictPass` is route-specific: direct-fidelity routes require identical rendered pixels, editable documents require normalized content equality, and spreadsheet routes require data equality. Editable PDF-to-DOCX also requires matching page counts and zero embedded media for a generated text-only source, while an image-only PDF must fail with `OCR_REQUIRED`. Cross-engine re-rendering is reported separately as `visualPass`.

## Modules

- `layout-model`: library-independent page, text, line, paragraph, table, and warning models.
- `ofd-parser`: safe extraction, `OfdParser`/`OcrEngine` SPI, and OFDRW adapter.
- `table-recognizer`: line normalization, grids, merged cells, and text assignment.
- `docx-renderer`: DOCX generation with POI/OOXML.
- `task-service`: converter registration, async task state, batch conversion, ZIP output, cleanup, and restart recovery.
- `web-api`: Spring Boot REST API and bundled Vue 3 frontend.
- `qa-samples`: sample-driven end-to-end QA scripts and local test samples.

## Contributing

Contributions are welcome for new parsers, converters, samples, font compatibility reports, and failure cases. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/quality-standard.md](docs/quality-standard.md) before adding a new route.

## Sponsorship

If this project helps you, sponsorship is welcome.

[Sponsor list](docs/sponsors.md)

<p>
  <img src="docs/assets/sponsor-wechat.png" alt="WeChat sponsor QR code" width="360">
  <img src="docs/assets/sponsor-alipay.png" alt="Alipay sponsor QR code" width="360">
</p>

Please verify the recipient information before scanning.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE). Licenses for bundled third-party components and fonts are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
