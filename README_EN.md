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
| OFD -> DOCX/TXT/PDF | beta | editability first | Text-based OFD can preserve text, tables, and images. Incompatible seal appearances produce an explicit warning while body content is retained; scanned OFD reports that OCR is required. |
| CSV <-> XLSX | stable | data first | Covers strict CSV to XLSX and back-to-CSV data round trips. |
| DOCX/XLSX/PPTX -> PDF | beta | layout first | Uses LibreOffice headless when available. Local fonts affect visual output. |
| TXT -> DOCX/PDF | stable | content first | Useful for generating simple office documents from plain text. |
| DOCX -> TXT | stable | text extraction | Extracts body text without layout. |
| PDF -> TXT/PNG/JPG | stable | extraction/rendering | PDF to PNG/JPEG uses Poppler or PDFBox page rendering. Multi-page PDFs are returned as ZIP files. |
| PDF -> DOCX | beta | editability first | Restores real text, basic paragraphs, page sizes, and orientation from text-based PDFs. Scanned or image-only pages fail explicitly until OCR is available. |
| PNG/JPG -> PDF | beta | layout first | Uses the Office engine when available, with PDFBox fallback. |
| WPS/ET/DPS/UOF | experimental | compatibility first | Depends on LibreOffice import support. UOF is converted directly to editable DOCX, so pagination and object positions may change. |

External dependencies:

- LibreOffice: used for Office-engine conversions involving DOCX/XLSX/PPTX/WPS/ET/DPS/UOF and PDF.
- Poppler: used for PDF to PNG/JPEG rendering and visual regression checks.
- System fonts: affect pagination, line spacing, and font substitution in Office output. Basic PDF text routes include fallback fonts, and a custom TrueType font can be selected with `FORMAT_CONVERTER_PDF_FONT`.

See [docs/quality-standard.md](docs/quality-standard.md) for quality definitions.

## Quick Start

Requirements:

- JDK 17
- Maven 3.9+
- Optional: LibreOffice or `soffice`
- Optional: Poppler `pdftoppm`

Build:

```bash
mvn clean verify
```

Run:

```bash
java -jar web-api/target/web-api-0.1.1.jar
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
- Windows: run `scripts/package-runtime.ps1` on Windows or use GitHub Actions. The regular package starts with `start.bat`; the `*-exe.zip` package starts with `FuyueConvert.exe`.

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
```

The worker memory setting limits the JVM heap only. Apply CPU, total-memory, and process-count limits with Docker/cgroups or systemd at deployment time.

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
