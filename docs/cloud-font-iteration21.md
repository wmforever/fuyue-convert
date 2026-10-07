# Embedded PDF font metadata and editable Word fidelity

Parent `bc34be353aba56eb99db15ea9f72a5405b7c3932`; same independent branch/draft PR. Iteration20's rejected narrow inference remains removed and its negative evidence unchanged. This batch changes only `PdfFontNames` in production.

## Concrete baseline failure

Six independently generated PyMuPDF PDFs freeze actual Unicode, original run coordinates, nine source font files/faces and licensing metadata before conversion. They cover Serif regular/bold/italic/bolditalic, Mono regular/bold/italic, mixed Chinese/English punctuation and currencies, DejaVu symbols/Greek/Cyrillic/ligatures, separated lines with decimals/dates/zero-padded IDs, and an explicit unavailable-family control. Source inventories and page bounds pass. Manifest SHA256: `1344d023bd073d2262c553e51537684e0cbebb73cb8d082daec342368ba59916`.

The PDFs omit `/FontFamily` and provide full face names such as `Liberation Serif Regular`, `Liberation Mono Bold` and `DejaVu Sans Book`. Their embedded font programs retain correct family/subfamily/weight metadata, but the old mapper used the full face name as a Word family. Actual Office exported NotoSerif instead of LiberationSerif/Mono, and DejaVuSerif/NotoSerif instead of DejaVuSans. `Liberation Serif Bold Italic` also lost its bold flag because name-suffix inference only retained italic. All six baseline DOCX logical texts were already complete (CER0); this is primarily font/style fidelity, not recovered missing OCR content.

System `fc-match` diagnosis is distinct from actual Office: the invalid full family names matched OpenAI Sans in that diagnostic configuration, whereas measured Office output used NotoSerif. Neither result is silently substituted for the other. Correct family names resolve to the installed licensed source fonts, and final Office font resources independently confirm actual selection.

## Minimal change and boundaries

For embedded `PDTrueTypeFont` and `PDType0Font`/`PDCIDFontType2` (including the measured embedded OpenType CJK font), reuse PDFBox's already parsed `TrueTypeFont` naming and OS/2 metadata. A nonblank explicit PDF descriptor family retains priority. When it is absent, use the embedded family before falling back to PDF face names. Embedded subfamily and weight complement the original bold/italic evidence.

The code never reads an installed substitute as if it were embedded, never strips arbitrary suffixes, never opens another font stream and never copies a new font program into DOCX. Missing/unreadable metadata and unsupported font types retain the prior path. Existing standard aliases and unknown unembedded family boundaries remain tested. The licensed embedded Droid CJK fallback is unchanged; source-perfect CJK typography and portable availability of every source font are not claimed.

## Paired actual results

All six source→DOCX→Office PDF→API TXT chains succeed before and after. DOCX and API TXT CER remain0%, all Unicode/numeric inventories remain exact, original Word paragraph/line boundaries and one-page outputs are preserved. An actual edit changes only one `00731→00739` run, reopens through Office, then extracts exactly through API TXT. Native parser blocks compare exactly in recorded content/coordinates/IDs/baselines/transforms, with only intended font family/style fields allowed to differ; font size and color remain exact.

The following geometry metric is the median, across successfully matched source lines/runs, of the maximum bounding-box edge deviation from source PDF to Office PDF. It is not a universal visual score; match counts are included to expose incomplete matching.

| Source | Actual Office font after | Matched runs before→after | Median max edge deviation (pt) before→after |
| --- | --- | --- | --- |
| Serif four faces | LiberationSerif, Bold, Italic, BoldItalic |4→4|35.248→1.951|
| Mono three faces | LiberationMono, Bold, Italic |3→3|113.348→1.951|
| Bilingual punctuation | Correct requested source families plus retained Droid/Noto fallback |3→3 of4|26.813→3.937|
| Symbols/Greek/Cyrillic/ligatures | DejaVuSans |2→3|24.788→0.499|
| Separated numeric lines | LiberationSerif plus retained CJK fallback |4→4|17.620→1.951|
| Explicit unavailable family | Same NotoSerif fallback |1→1|36.748→36.748|

The unavailable-family DOCX requests/styles and Office pixels remain exact. Source, baseline and final Mono previews were inspected; the final monospace geometry and distinct regular/bold/italic forms match source closely. Bilingual and symbol outputs were inspected as well. Original text/digits were not changed to obtain these results.

Raw Office extraction CER for the symbol sample improves5.479%→0%; mixed bilingual raw extraction remains2.941% with exact character inventory, while its API TXT CER is0% before/after. This raw-reader ordering limitation remains visible. MuPDF's span display abbreviates `LiberationSerif-BoldItalic` to `LiberationSerif-BoldItal`; the first comparison assertion rejected that spelling. Inspecting the actual PDF `/BaseFont` verified the full correct PostScript name and bold+italic span flags. The comparator now reports both raw resource names and display names, compares source faces to the actual PDF resource, and preserves the original observer failure/stderr. No HTTP matrix was repeated for this observation correction, and no text truth changed.

## Tests, runtime and provenance

- Focused **52 tests pass**, including three new hermetic embedded-family/explicit-family tests using the existing licensed bundled LiberationSans fixture, plus the19 prior alias/style/unknown-name font tests, heading order, columns, tables, renderer and cross-page regressions.
- Fresh clean package: **427 tests,426 pass,0 failures/errors,1 absent optional real signed-OFD fixture skip**. Bundled OCR and real Office tests execute. No separate new OCR accuracy sweep or local UI/desktop rerun.
- **38 authenticated HTTP contracts /38 direct backend JVM Worker identities**:18 baseline and20 final including the edit chain. Absolute deadlines and the published subreaper remain active. ECHILD and absent owned identities verified, zero new zombies; ten historical audit zombies unchanged. Baseline/final measured helper hashes are verified against their retained source snapshots.
- Baseline18-contract suite:52.9563s wall,153.6271s child CPU,426364KiB maximum-child RSS. Final20-contract suite including edit:49.1920s wall,144.1242s child CPU,429752KiB maximum-child RSS. Workloads differ and these are single observations, so no performance improvement claim.

Final JAR SHA256 `a576db38614824fca7e2e08e6e233e7cdf50a75297a874405a6be90ebb922fb0`; build-input fingerprint `5cdace2c72f278ce60c1af9d728400bd5ec8fc50ad9109eeaba71fbd77d2b29d`. All223 packaged application classes match fresh targets. Only `PdfFontNames` and its `Face` class change; other221 classes, including the accepted heading renderer, OCR/masking and coordinate parser, match the parent artifact. Post-commit verification binds all inputs and JAR to the delivered revision.

Runtime remains Temurin17.0.16+8, Maven3.9.11, Node22.17.0, Python3.12.14, Linux6.18.44, PyMuPDF1.26.6 and LibreOfficeDev26.8.0.0.alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`. Exact source font versions/hashes and rechecked bundled OCR model/runtime hashes are in [measurements](cloud-font-iteration21-results.json). Liberation2.1.5 and NotoSansCJK2.004 use SIL-OFL1.1; DejaVuSans2.37 uses Bitstream-Vera terms with public-domain DejaVu changes. Installed copyright/license notices and existing bundled font notices accompany local embedded-font samples; no new production font redistribution or dependency is introduced.

[Frozen plan](cloud-font-iteration21-plan.json), generators, metadata/native probes, measured runner and comparator are public. Original corpus and full raw failure/acceptance evidence remain in the review packet. Native Word/macOS/Windows packages are unrun; cloud validation does not update stale installers. Remaining priorities include true missing-font portability, CJK fallback visual fidelity, unsupported embedded font types, mixed-script raw extraction and the previously rejected narrow-frame baseline problem. No merge/release/Mac/Library retry. Parent independently confirmed the saved UI model setting “GPT-6 Astra 高”; this does not imply a retroactive hot-switch of earlier inference.
