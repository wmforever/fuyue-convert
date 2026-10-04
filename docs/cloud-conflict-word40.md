# Conflict Word visibility and durable review warnings (cloud batch40)

Repository `wmforever/fuyue-convert`, existing branch `improve/cloud-ocr-completeness-20261003`, draft PR1 to main. Parent baseline `9890918dd4cb87a2f52d57f47b8edd3248f82a00`; main remains `e3ffde164e90e766b7a9d6e3b1fae3f53b94d7d5`. This finite batch follows the accepted terminal-sign guard39. It does not repeat39's matrix or adopt a layout/rotation prototype.

## Measured result and change

Numeric source conflicts survive as editable text, but their visible Word/Office layout remains **unaccepted**. Actual Office renders contain overlapping glyphs and old scan pixels; ordinary punctuation deduplication also leaves ghost text after an edit. Existing API conflict warnings were absent from the downloaded Word properties.

`PoiDocxRenderer` now stores a review subject and description in Word core properties when document or page warnings include `OCR_APPLIED` or `OCR_RECOGNITION_CONFLICT`. Conflict subject: **OCR 数值冲突：需人工核对**. Description explains that original scan pixels will not change with an edit, colocated content may overprint, editable text is not proof of accuracy/readability, and conflicting sources are preserved without selecting or normalizing a value. Ordinary OCR receives a generic review subject. Documents without either warning retain the existing path.

Measured durable warning coverage increases **0/4 → 4/4 Word files**; actual Office→PDF also retains **4/4 subjects**. PDF export does not retain the full Word description. Properties are not an in-page banner: a consumer must inspect metadata, and API warnings from an Office-only conversion remain empty. This is a limited warning improvement, not a remedy for overprinting or old-value rereading.

## Frozen inputs and actual outputs

`prepare_conflict_word40.py` freezes existing synthetic sources and acceptance rules before conversion. Four independent controls are derived from35/39 with pinned Liberation Sans; these are Latin financial/layout controls, not a new bilingual OCR acceptance corpus. Neither conflicting source is designated financial truth.

| Control | Original native literal | Original raster literal | Conflict | Frozen edits |
| --- | --- | --- | --- | --- |
| accounting | `Amount 048.65` | `Amount (048.65)` | yes | native `Amount 061.42`; OCR `(079.53)` |
| percent | `Rate 10` | `Rate 10%` | yes | native `Rate 12`; OCR `14%` |
| post-minus | `Amount 048.65-` | `Amount 048.65` | yes | native `Amount 061.42-`; OCR `079.53` |
| ordinary | `Invoice No 2094` | `Invoice No. 2094` | no | native `Invoice No 2095` |

All **23 authenticated HTTP→separate JVM worker→download contracts succeed**: original4 OFD→Word +4 Word→PDF, seven exact-node edited Word→PDF, and changed-artifact4 OFD→Word +4 Word→PDF. All23 workers disappear, supervision reaches ECHILD, no new zombies. Success here means conversion completed; it does not certify visible numeric completeness.

Original three conflict Words and PDFs contain both literal representations. The ordinary formatting control has the native editable value and deduplicates its OCR field. All seven edits select exactly one predeclared whole `w:t` node; all other nodes, styles, images, relationships and package parts remain unchanged. Actual edited Office PDF native text contains the new synthetic literal. Raster pixels still contain the old value, and the edited rendered rows remain visibly overprinted. Seven after-metadata edited Office conversions are deliberately **unrun**: original/changed-artifact Word body, styles and scans are byte-exact; the seven original edit PDFs remain immutable evidence. No claim that those older edited files gained the new properties.

For all four unchanged normal controls, before/after differ only in `docProps/core.xml`; every other ZIP part is byte-exact. Source2550×3300 scan pixels, word boxes, native text and existing negative-z fallback masks are preserved. Full captured OCR TSVs are byte-exact. Actual Office native text and full300DPI page pixels are exact. Thus there is **no text-CER, completeness or visible-reading improvement** from this metadata change, and no source-coordinate/masking change. Literal equality and selectable text do not establish visual correctness.

The three exact native/OCR value lines overlap in actual PDF coordinates. For example, accounting native line bbox is `[76.35,226.23,167.89,241.30]`pt; OCR line `[72.10,219.83,171.94,236.01]`pt. Exact whole-line matching avoids confusing `Rate 10` with the substring of `Rate 10%`, or bare amount with a terminal-sign amount. Original masks preserve potentially distinct native glyphs; promoting them or choosing/moving a source without proof could silently hide a value. No such production change was made.

`render_conflict_word40.py` renders11 actual Office PDFs at300DPI and reads the predeclared value ROI using11 bounded pinned-engine PSM6 calls (25s each). Baseline normal diagnostic reads include `AI和:68)`, `Raley`, `PRAT I SS-`, `Invoice NO.妈0和`; images independently show overprinting. These ROI results are diagnostic only, not source full-page PSM3 CER or HTTP acceptance. After normal pages are pixel-identical, so no diagnostic OCR is repeated. Source OCR calls8, Office15, diagnostic ROI OCR11, replay OCR0.

## Build, costs and provenance

Clean Linux build: **505 tests,504 passed,0 failures/errors,1 optional signed-OFD fixture skip**. All10 existing named bundled conditional tests execute; system OCR is not substituted. Frontend production build and twelve pinned runtime payload hashes verified. Only `PoiDocxRenderer` plus its three compiled nested classes change; other228 application classes and all application resources/frontend are byte-exact against39. Fresh232 classes match the packaged JAR.

JAR SHA256: `1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b`. Production input fingerprint: `07911c45cf0a510f7fa9f8ae279e977341c795e246c19711c3dce83aee95b198`. `iteration40-fixed-provenance.json` binds clean-build inputs, fresh classes and eventual committed revision. Previous JAR: `7f5fb6f3471e9e9f8a0c5c64145fe3df0c229d7cbedb1e452c3d47c24ff6200c`.

| Phase | HTTP contracts | Wall seconds | Child user/system CPU seconds | Maximum single-child RSS KiB |
| --- | ---: | ---: | ---: | ---: |
| Before normal | 8 | 40.929 | 98.395 / 8.528 | 359280 |
| Before exact edits | 7 | 22.331 | 53.475 / 5.052 | 318524 |
| After normal | 8 | 32.852 | 79.282 / 7.419 | 357672 |

One run per phase, with startup and scheduling variance; no speedup claim. RSS is the maximum single child, not aggregate backend+worker memory. No property fix changes recognition, rendering geometry, pixel/time/concurrency limits, confidence fallback, blank/sparse recovery or numeric39 guard.

Runtime: Temurin17.0.16+8/Maven3.9.11/Node22.17.0/Python3.12.14/PyMuPDF1.26.6/Linux6.18.44; Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1; fast models revision `87416418657359cb625c412a48b6e1d6d41c29bd`; `chi_sim+eng`, production PSM3,120s/25Mpixel/concurrency1/.35 minimum confidence. OfficeDev26.8alpha0 revision `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`; PDFBox3.0.8/OFDRW2.3.9/POI5.4.1. Source LiberationSans2.1.5 SHA `bade59d822652f76e6941aa87b40a87c13d1cc70db98ededb5011127efafd1d3`; bundled LiberationSans2.1.5 SHA `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`, DroidSansFallback2.55b SHA `21b96a0377f067833a93af3082eb28d4ffab7a8cd46bfd513286f1d64b7b0949`. All three rehashed; exact models/payloads/versions are in [machine evidence](cloud-conflict-word40-results.json).

Initial build used nonexistent POI `setSubject`; it failed at compilation and produced no accepted JAR. Inspected the installed5.4.1 API, corrected to `setSubjectProperty`, and completed the clean build above. First evidence verifier assumed one changed class; javac also changes the three renderer nested classes, so the incorrect assertion failed before rendering/engine calls. Both failed receipts/logs and original verifier are retained; corrected verification uses actual four-class scope. No successful matrix or active command was restarted.

## Reproduction and limits

Use the same prepared Linux runtime from35/39, environment activation, `FORMAT_CONVERTER_APP_HOME` and Office path described in the existing cloud QA docs. Each output directory/receipt is exclusive. Run `prepare_conflict_word40.py`; authenticated runner `capture_numeric_http35.py --jar ... --out .../iteration40-normal-http --corpus .../conflict-word40`; prepare seven edits with `--edits-from`; run `run_edit_iteration26.py` on `conflict-word40-edits`; run `render_conflict_word40.py` once. Build changed source with `accept_cloud_build.sh` bracketed by `record_cloud_provenance.py --before/--after`, run8 normal changed-artifact requests into `iteration40-after-http`, then `verify_conflict_word40.py` once. Helpers make no external uploads; private backend/task data and tokens are excluded from review evidence/Git.

Exact-head remote SHA, draft status and three CI jobs are recorded in PR1 and the finalized cloud checkpoint. Cloud validation is distinct from native macOS/Windows installers and Microsoft Word, all unrun. Original sparse OFD, adjacent long edits, small CJK recognition, per-mille accuracy and previous mixed Word→Office→API strict visibility/completeness failures remain unaccepted. API old-value rereading is not repaired by metadata. Preserve strict gates and unknown native/scan content; existing parent/dot coordinates a separate finite geometry/masking proof if warranted. No merge, release, Mac access, Library retry or parallel writer.
