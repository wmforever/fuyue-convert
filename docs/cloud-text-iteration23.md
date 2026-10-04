# Preserve declared PDF sections in text extraction

Parent `b11b15ef89f27139ab75303eb0c975a235d1361f`; same branch/draft PR. This batch fixes a measured PDF→TXT section-order failure without modifying Word rendering, source text, fonts, geometry, OCR or OFD extraction order.

## Exact route and evidence

`PdfToTextConverter` parses native blocks through `PdfLayoutParser`, analyzes tables, then previously passed the entire page to `OfdToTextConverter.text()`. Its `spatialOrder` first cuts a global vertical gutter. For the accepted22 Office artifact, output was heading1/left1, heading2/left2, then right1/right2: API CER36.923%, despite correct DOCX logical section order.

That PDF retains `/StructTreeRoot`→`Document`→separate paragraph containers for each heading and its frames. The `Standard` role maps to `P`; nested `Div`/`Span` nodes and page MCIDs associate all36 native font fragments with the proper container. Raw tag-child traversal within each paragraph is not reliably column-major, so the change uses declared paragraph boundaries and retains existing geometric ordering inside each boundary. The read-only [probe](../qa-samples/PdfTextSectionsProbe.java) records MCID/block mappings, legacy/final text and exact native-block equality.

Pure whitespace-first ordering was rejected during investigation: a continuous two-column passage with a large aligned paragraph gap can look like independent sections. The previously rejected20 narrow/reflow approach and its negative evidence were reviewed and remain removed. Neither approach was reimplemented or benchmarked again.

## Conservative text-only implementation

An optional `PdfTextSectionOrder` is created only for PDF→TXT. The existing parser captures the current marked-content group beside each existing native block; it neither splits nor rewrites blocks. Word's parse path receives no collector, and the shared layout models/renderers remain unchanged.

Adoption requires supported author-declared paragraph containers under one Document root, bounded acyclic traversal, unique page/MCID membership, and complete assignment of every native block. Active groups must contain multiple lines and occupy nonoverlapping vertical bounds in declared order. Each accepted group uses the unchanged text sorter. The page boundary and blank-page contract remain unchanged.

Any missing/conflicting/unsupported tags, overlapping/single-line/reversed groups, tables, images/OCR, rotated/skewed blocks, unbalanced/deep marked content or Form content retains the legacy page path. Form MCIDs are a separate stream namespace, so they are not inferred from page MCIDs. Metadata traversal is capped by the smaller of parse-entry limit and100,000 nodes, at most64 levels. Out-of-range IDs cannot wrap to another ID. No table intent, section wording or whitespace heuristic is introduced.

## Frozen paired corpus and actual results

Seven independently generated PDFs cover tagged wide regions, tagged mixed Chinese/Latin regions, unequal column tails, untagged continuous wide columns with an aligned paragraph gap, single column plus a genuinely blank page, a ruled numeric table and an ambiguous unruled ledger. An eighth input is an exact copy of the accepted22 Office PDF with its original truth. No completed font matrix was repeated. The new producer declares its own explicit text/tag groups and verifies Unicode inventory before conversions.

Manifest SHA256: `3ef1d50aeb075e2451acb285c7e524bb4e53742cddfbd6b160cea675c884527f`.

| Direct PDF→API TXT input | CER before | CER after |
| --- | ---: | ---: |
| Tagged wide heading regions |35.065%|0%|
| Tagged mixed-script regions |31.034%|0%|
| Tagged unequal tails |27.979%|0%|
| Retained22 Office PDF |36.923%|0%|
| Continuous wide columns with paragraph gap |0%|0%|
| Single column plus blank page |0%|0%|
| Ruled numeric table |0%|0%|
| Ambiguous unruled ledger, row-major reference |76.190%|76.190%|

The ledger reference is for measurement/inventory only; its row/column intent is not asserted or inferred. All four untagged controls retain exact output TXT bytes. All eight before/after character inventories, case, numeric values/IDs/signs/decimal surfaces remain exact ignoring whitespace. CER normalizes whitespace and Latin case; expected-order assertions independently ignore whitespace without changing actual source/output files. Native parsed and analyzed models compare exactly, including every text block's whitespace, IDs, boxes, baselines, transformations, fonts, size and color.

The mixed-region and ruled-table inputs additionally traverse actual HTTP→independent JVM Worker→DOCX→Office PDF→API TXT before and after. Mixed-region Office API CER31.034%→0%; table remains0%. Both Word document XMLs and package parts match, except core timestamps and independently verified random font-obfuscation keys. Each embedded font is deobfuscated and compared byte-for-byte with the original bundled font. Office pixels and word boxes match exactly;16 real editable table cells remain exact. Preserved candidate mixed/table previews were inspected; final Office pixels are proven identical to both candidate and baseline. Word/OCR/font fidelity and raw Office traversal order are not claimed improved; this is the TXT/API layer.

Total:14 baseline,14 preserved pre-content-ID-guard candidate and14 accepted HTTP contracts,42 direct Worker identities, all successful. The final matrix was necessary because strict content-ID validation changed production classes; earlier completed runs are not relabeled. Baseline32.226s wall/98.094 child-CPU seconds/430760KiB max-child RSS; final34.374s/99.967CPU seconds/411340KiB. Same14-contract workload, single observations; read-only native/tag probes overlapped the end of the final run, so wall time is not an isolated performance benchmark. No speed improvement claim. Existing480s suite/120s contract/30s request/15s teardown limits and subreaper remain active. All Worker identities are absent, ECHILD confirmed, zero new zombies; ten historical conversion and seven older git zombies are unchanged and unsignalled.

## Tests, build binding and limits

An initial constructor integration compile failed before tests; its log is preserved. After correcting PDFBox3 operator constructor signatures,63 focused tests passed. The first clean build had446 tests/445 pass/one optional signed-OFD skip. Scope was then restricted to separated multiline groups; the447-test candidate and its14 successful HTTP contracts are preserved.

Precommit review added strict content-side integer validation. The first malformed-ID fixture producer normalized the invalid values to0 and is excluded as negative evidence. Corrected fixtures emit raw overflow/fractional operands and assert the saved content bytes. One incremental replay reused cached strict classes and is also excluded. The forced old-source recompilation is bound by parser/helper class hashes to the preserved candidate JAR and fails2/17 tests; strict-source recompilation passes17/17. Logs, original invalid fixtures, corrected raw fixtures and class bindings are retained.

Final fresh clean package: **449 tests,448 pass,0 failures/errors,1 absent optional real signed-OFD fixture skip**. All17 tagged-extraction regressions pass, including partial/duplicate/cyclic tags, unsupported/table/stream namespaces, traversal limits, unmarked content, nesting/unclosed markup, Form aliasing, structure/content integer overflow, fractional content IDs, overlapping groups and blank-page preservation. Bundled OCR and actual Office tests execute; no new OCR accuracy sweep or local frontend/desktop suite rerun.

Final JAR SHA256 `278cccdb1c53943158a9ce9f32ba6786eb610d42868db38fe51185a3f8af7e1c`; build-input fingerprint `f62fa7aea6742b8d1e99afc34cc244c095013b09855b05a300ab4adcd43db647`. All225 packaged classes match fresh targets. Two new section-helper classes are added; eight parser/converter classes differ (including nested/debug line-number changes),215 parent classes are identical. All Word renderer, font mapper, OCR, masking and layout-model classes are byte-identical. Post-commit verification binds every build input and the measured JAR to the delivered revision.

Runtime: Temurin17.0.16+8, Maven3.9.11, Node22.17.0, Python3.12.14, PyMuPDF1.26.6, FontTools4.61.1, Linux6.18.44; LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5. LiberationSans2.1.5/NotoCJK2.004 SIL-OFL1.1 fonts and licenses are retained. Bundled Tesseract5.5.2/four pinned tessdata_fast models were rehashed; exact versions/hashes are in [measurements](cloud-text-iteration23-results.json). [Frozen plan and scope review](cloud-text-iteration23-plan.json), generator, bounded runner, probes and comparator are public.

Remaining: untagged or unsupported structure, single-line/overlapping groups, incomplete/incorrect author metadata, unruled ledger intent, raw Office extraction, unchanged CJK missing-font portability/fidelity, and earlier rejected narrow/OCR/deskew limits. Structure tags express source grouping and are not a universal guarantee of reading intent. Native Microsoft Word/macOS/Windows packages remain unrun and installers stale. No merge/release/Mac/Library retry; parent-confirmed saved model is Astra/high.
