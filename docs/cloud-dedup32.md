# OFD deduplication integrity: iteration32

Finite batch from accepted `16dab17b4135a35f9120311d0ecf8dbc2ee75d41`, on the same cloud branch and draft PR. This corrects three measured false numeric duplicates while keeping strict no-new-text and usable-result contracts. It also records a separate existing mixed-page Word masking failure; that chain is **not quality-passed**.

## Original sparse gate: traced, not bypassed

The saved iteration30 recognition already contains all four original bilingual lines. Full image OCR passes `requireUsableResult`, which checks nonempty blocks and minimum confidence. `OfdOcrSupport` then compares the full OCR blocks against original native text, forms `beyondNative`, and throws `OCR_NO_NEW_TEXT` when a geometrically required image has no novel blocks. This is an explicit novelty requirement, not a pixel-coverage calculation accidentally run on additions. The later page summary also checks nonempty collected additions and weighted confidence; image coverage/conflict warnings remain at image scope.

The original native text layer fails the scan detector's independent geometry/character backing criterion, and all four OCR lines match existing native text under the current comparator. OCR agreement does not establish that every pixel contains complete, correct text. No safe basis was proven for converting that case into complete SUCCESS. The original exact source/recognized models were **replayed with zero OCR calls**, and remain strict with zero additions. Existing original sparse raster recognition and parameter diagnostics were not rerun.

Supported zero-addition behavior is narrower: a parser page with sufficient native backing needs no OCR and keeps its native result; a second fully overlapping scan may add zero blocks after matching earlier OCR on that page, while the first scan supplies a usable result. Both are actual HTTP controls. A required raster matching only the insufficient native layer still fails `OCR_NO_NEW_TEXT`. Blank/noise raster pages retain `OCR_NO_TEXT`; they do not become empty complete successes.

## Proven wrong numeric comparison and minimal correction

Eleven independent mixed native/raster OFDs were frozen before edits. Four unique new raster recognitions capture complete words, native coordinates and duplicate decisions without changing OCR settings. The comparator previously removed all non-alphanumeric characters and accepted an 80%-length contained string. That makes distinct source values look identical or similar:

| Native value | Full actual OCR value | Baseline | Final |
| --- | --- | --- | --- |
| `Record 00793` | `Record 007930` | falsely duplicate; `OCR_NO_NEW_TEXT` | both values retained, conflict warning |
| `Amount 048.65` | `Amount -048.65` | falsely duplicate; `OCR_NO_NEW_TEXT` | both values retained, conflict warning |
| `Amount 04865` | `Amount 048.65` | falsely duplicate; `OCR_NO_NEW_TEXT` | both values retained, conflict warning |

`OcrTextDeduplicator` now requires **exact ordered numeric surfaces** before discarding a geometrically overlapping, text-similar block. Leading zeros, signs, decimal/thousands/date separators, digit extensions and separate numeric tokens remain distinct. Existing nonnumeric formatting tolerance and geometry checks stay intact. Same-lexeme content at different coordinates still remains separate. The shared guard also applies to PDF OCR deduplication; accepted masked-edit and explicit-whitespace PDF controls remain exact.

`OfdOcrSupport` adds the existing `OCR_RECOGNITION_CONFLICT` warning at image scope when overlapping, same-label native and OCR text contain different numeric tokens. It preserves native blocks and OCR additions; it does not select or replace a source value based on confidence. Four numeric-conflict cases warn, including an already-retained one-digit substitution. A one-letter difference remains retained. Original image data, native blocks, OCR geometry/word confidence, image-level recovery warnings and timeout settings are unchanged.

These conflicting inputs have **two inconsistent source layers**, not one authoritative amount. Their flat TXT can concatenate two descriptions on the same visual line, and Word retains both layers. Successful conversion with a conflict warning is not resolved business data or a completeness guarantee. Exact source-layer coverage and numeric lexemes are verified separately from interface status; no normalized CER shortcut decides correctness.

## Validation and the separate masking fault

28 focused tests pass, including production `OfdOcrSupport` model checks for exact duplicate refusal, numeric-conflict warning scope, unchanged native blocks, source image bytes/coordinates, original OCR word bounds and native-only no-op. One final clean frontend production build and Maven package pass: **489 tests, 488 executed passes, one original optional real signed-OFD skip**, no failures/errors. Bundled OCR tests executed; all 12 runtime files are reverified against the fixed manifest. Only `OcrTextDeduplicator.class` and `OfdOcrSupport.class` differ from the parent; the other 230 classes and seven frontend resources are byte-identical.

Actual HTTP → independent JVM Worker → artifact download: 11 baseline TXT requests, five previously unrun parent Word-chain comparisons, and 21 final requests. Final interface status is **18 successes and three expected strict failures**, with all 37 Workers absent, ECHILD and zero new zombies. The final controls verify three corrected false duplicates, four numeric-conflict warnings, sufficient-native no-op, overlapping OCR no-op, namespace aliasing, scan pixels/editable text, and accepted masked negative-amount/`REVIEW 2068` PDF regressions.

Two new Word/API content checks **fail on both parent and final JARs**. For the partial-native OFD, Word/Office retains the original scan amount visibly underneath editable text. After `048.65 → 147.80`, the Office native layer has only `147.80`, but the API returns both `048.65` and `147.80`; even unedited output duplicates `048.65`. The parent and final Word ZIP parts are exact except creation metadata; unedited and edited Office pixels are exact at 300 DPI. The recorded amount crop visibly superimposes old and new glyphs. This is an existing rendering/masking fault, not a regression introduced by the numeric comparator.

The renderer explicitly limits foreground sampled masks to scan-only pages to avoid covering native body text on mixed pages (`FixedLayoutDocxRenderer.prepareOcrAppearance`, `scanOnly` guard). A finite follow-up should evaluate safe word-level masks only where original/native collision checks justify them, preserving original coordinates, scans and all ambiguous fallbacks. This batch nominates that concrete fault and does not broaden the rendering change. [Results](cloud-dedup32-results.json) retain both failed comparisons; [validation](cloud-dedup32-validation.json) records versions, model/font hashes, skips, source/build/helper provenance and resource costs.

Final JAR SHA-256: `ac495004efdaba493bdd439bd815158ba5e52d7719cdead0119c8bc247748528`. Build-input fingerprint: `faed89f5f62ba402827dee105b724633f2074a9484c406244ec330e5d4312983`; all 232 packaged classes match fresh target classes. Runtime remains Temurin 17.0.16+8/Maven 3.9.11/Node 22.17.0, PDFBox 3.0.8/OFDRW 2.3.9, pinned Tesseract 5.5.2/Leptonica 1.87.0/libpng 1.6.57/zlib 1.3.1, tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`, LibreOfficeDev 26.8 alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`, Liberation Sans 2.1.5 and the exact recorded bundled fonts. Version observations from the accepted parent are retained; runtime/model/font hashes were reverified this batch.

Final 21-request suite: 81.68 s wall, 193.79 s child user CPU, 17.80 s system CPU, 392,116 KiB max child RSS. Baseline 11-request suite: 42.64 s wall, 102.93 s user, 8.68 s system, 346,856 KiB max child RSS. Different workloads and one run per request do not establish a speedup or general resource bound. Existing 120 s per-request/480 s suite deadlines and production limits remain unchanged.

## Reproduction

```bash
# Requires the accepted synthetic native-backed template from generate_sparse_trace30.py.
python3 qa-samples/generate_dedup32.py
python3 qa-samples/run_edit_iteration26.py --jar '<accepted-parent.jar>' \
  --out qa-samples/report/iteration32-before --corpus qa-samples/generated/dedup32 --keep-going
python3 qa-samples/generate_dedup32_final.py
python3 qa-samples/run_edit_iteration26.py --jar '<final.jar>' \
  --out qa-samples/report/iteration32-after --corpus qa-samples/generated/dedup32-final --keep-going

mvn -B -ntp -pl task-service -am -Dskip.frontend=true \
  -Dtest=OcrTextDeduplicatorTest,OfdNumericDedupTest,OfdOcrConverterTest,OfdOcrWarningsTest,OfdOcrPartialWarningsTest,PdfOcrVisibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`OcrSparseTraceProbe` captures only the new independent raster models, caching identical new rasters. `OcrDedupReplay32` consumes that saved trace plus the existing iteration30 original trace with **zero recognition invocations**, exposing full OCR, original native blocks, beyond-native blocks, final additions and reading assembly. `verify_dedup32.py` validates downloads, source-layer numerical coverage, explicit warnings/no-ops/strict failures, original scan pixels and the frozen parent/final Word masking failures. Private task data/tokens and generated inputs do not enter Git.

Original sparse novelty semantics, the nominated mixed-page mask fault, ambiguous source-layer reconciliation, arbitrary-length edits, complex rotated/multi-image/Form visibility, native macOS/Windows installers, Microsoft Word and the optional signed-OFD fixture remain open/unrun. No merge, release, Mac access, Library retry or external upload.
