# Native PDF text boundaries: iteration31

This finite batch starts from accepted `fed6b476476ef0b4dfc65d7b6f32f50877b77540` on the existing cloud branch and draft PR. The reproduced API defect is `RESERVE REVIEW2068` from an Office PDF whose source explicitly encodes `REVIEW 2068`.

## Source evidence and fix

The source U+0020 is at x=150.5 pt, baseline=33.30046 pt, width=4.141144 pt. The following `2` starts at x=148.75 pt and has a 7.255798 pt advance. PDFBox 3.0.8 visual sorting drops that overlapping space before application text assembly. The native source, emitted PDFBox characters and standard PDFBox output are identical before/after; the application now restores only the encoded source boundary. Its only changed native block is `REVIEW` → `REVIEW `, with every geometry, style, transform and warning field unchanged.

`PdfExplicitSpaces` records source-adjacent spaces before sorting. TXT parsing restores them only when their original neighboring ASCII alphanumeric/Han characters remain adjacent in the emitted line and same-baseline geometry corroborates the boundary. It adds no spaces based solely on a visual gap. Already ordered spaces remain unchanged; misplaced source spaces are removed from their sorted location and restored at their original boundary. Counts of one or two explicit spaces are preserved. Word/fixed-layout parsing uses the existing text path.

Recovery is optional and bounded: at most 4,096 candidate boundaries per page, eight consecutive source spaces, and 100,000 emitted positions per callback. Rotated pages, non-default UserUnit, any Form namespace, ambiguous/non-adjacent characters, unsupported endpoints and exhausted budgets retain the existing fallback. No OCR confidence/completeness gates, image-coordinate mapping, masking, numeric protection, timeout or native/OCR selection rule changed. Production class changes are limited to `PdfLayoutParser` and the new helper; every other application class is byte-identical to the accepted parent.

## Exact evidence

| Frozen PDF | Before API | Final API |
| --- | --- | --- |
| Independent Latin/year | `AUDIT2071` | `AUDIT 2071` |
| Independent numbers | `1234` | `12 34` |
| Two encoded spaces | `AUDIT2 074` | `AUDIT  2074` |
| Original accepted Office PDF | `REVIEW2068` | `REVIEW 2068` |

Five controls remain exact: intentional no-space IDs (including color changes and a large visual gap), decimal/sign/date surfaces and CJK mixed punctuation. Across all nine text comparisons, exact output improves **5/9 → 9/9**. Whitespace-normalized CER was already zero for the failed boundaries; it is not sufficient evidence of completeness. Raw edit distance and numeric-token checks are recorded separately in [results](cloud-boundaries31-results.json).

The final production JAR completed **16/16 authenticated HTTP → separate JVM Worker → artifact-download requests**. Two actual scan Word outputs preserve every ZIP part except creation-time metadata, including editable runs, original raster bytes, masks and shapes. The regenerated reserve Word opens through the actual Office API path; its one-page PDF is pixel-identical to the accepted parent at 300 DPI and its heading stays on one line. Replacing editable `00062.35` with `-054.80` produces exact Office native text and exact API text. A blank PDF remains a one-page empty TXT result. All 24 new baseline/final Workers exited; no new zombies, all supervised commands ended with ECHILD.

Eight paired native requests total 20.86 s before and 13.42 s after (median 2.57/1.53 s). These are one run per request with differing process warmup/scheduling, not a general speedup claim. The 16-request final suite takes 41.58 s wall time, 114.04 s child user CPU, 9.06 s system CPU, and 344,404 KiB maximum child RSS. The eight-request baseline takes 26.01 s wall, 72.74 s user, 6.36 s system and 307,780 KiB maximum child RSS; suite workloads differ.

## Build and reproduction

50 distinct focused regressions pass. The one final clean frontend production build and Maven package pass: **483 tests, 482 executed passes, one existing optional signed-OFD fixture skip**. Bundled-engine tests run against the verified pinned app-home, with all 12 runtime files matching the manifest. See [validation](cloud-boundaries31-validation.json) for precise versions, fonts/models/licenses, skips, helper hashes, class audit and build receipt.

Final JAR SHA-256: `0f0182d512009114fbba0165942f63f0f14fced8c6865c2f2c7478ad01d9ae61`. Build-input fingerprint: `cd399218437630422b3012ee5608f86c19336e32bb9c361e08d72cc708a0e10e`; all 232 packaged application classes match fresh target classes. Runtime: Java Temurin 17.0.16+8, Maven 3.9.11, Node 22.17.0, PDFBox 3.0.8, OFDRW 2.3.9, pinned Tesseract 5.5.2/Leptonica 1.87.0/libpng 1.6.57/zlib 1.3.1, tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`, LibreOfficeDev 26.8 alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`, Python 3.12.14, PyMuPDF 1.26.6 and Linux 6.18.44. Generator and Office font hashes are recorded separately.

```bash
mvn -B -ntp -pl task-service -am -Dskip.frontend=true \
  -Dtest=PdfExplicitSpaceTest,PdfTaggedTextSectionsTest,PdfToTextConverterTest,PdfOcrVisibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Compile the public generator/probe using the pinned application's BOOT-INF/lib/*
# classpath, or the Maven runtime classpath. Use a fresh output directory.
java -cp '<classes>:<runtime-libs>/*' GeneratePdfBoundaries31 \
  qa-samples/generated/boundaries31 task-service/src/main/resources/fonts
python3 qa-samples/run_edit_iteration26.py --jar '<accepted-parent.jar>' \
  --out qa-samples/report/iteration31-before --corpus qa-samples/generated/boundaries31
```

For the frozen cloud evidence, `verify_boundaries31.py --prepare` copies existing accepted iteration30 artifacts without rerunning old matrices; run `run_edit_iteration26.py` with the final JAR, `--out qa-samples/report/iteration31-after --corpus qa-samples/generated/boundaries31-final`, then `verify_boundaries31.py`. The reserve Office source is reproducible using `generate_heading30.py` and the accepted iteration30 scan→Word→Office chain. `PdfExplicitSpaceProbe` captures source glyphs, sorted output, native models and final section assembly. Generated inputs, raw trace, actual downloads, receipts and provenance remain in ignored QA directories; no private files or tokens enter Git.

## Remaining boundaries

The original sparse OFD still has the recorded strict `OCR_NO_NEW_TEXT` failure; iteration30's raster/placement diagnostic was preserved and not repeated. This fix does not claim recovery for arbitrary content order, rotated text, Forms, non-unit pages, unsupported punctuation endpoints, arbitrary-length Word edits or other fonts/readers. Native macOS/Windows installers, Microsoft Word and the optional signed-OFD fixture remain unrun. Cloud build acceptance is separate from native packaging; no merge or release is performed.
