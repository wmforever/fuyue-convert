# Cloud OCR follow-up: inflated boxes, holdouts and Word rotation

Baseline: `d51693b7e0bc5846bb4f5dcf4d1dad8cbf48b54e`, 2026-10-03,
Linux x86_64, wmforever/fuyue-convert only. No main, merge or release changes.

The subsequent shaded-omission and TXT column-order results, including retained
failures and coverage false positives, are in [iteration 3](cloud-ocr-iteration3.md).

## Measured change

Chinese −6° recovery was rejected despite a 93.24% confidence candidate with
nearly complete text. Native diagnostic evidence found three unreliable
geometric extents on **reliable recognized tokens**: `客户` had a 329×70 pixel
original box, and two `。` tokens had 68/87-pixel-high boxes. The corrected
identical tokens occupied only 23.19%, 11.61% and 9.68% of those boxes.
The original 25% overlap test therefore rejected the entire page.

Keep that original overlap threshold. Add a separate conservative correspondence
only when it fails: identical normalized text, candidate confidence ≥85%, unique
eligible candidate, candidate center inside the original box, ≥50% of candidate
area covered, and no prior claim on the candidate. This uses text and candidate
containment to handle inflated extents; it does not relax numeric conflict
protection or use fixture truth. The three candidate coverage fractions are
75.46%, 83.10% and 69.12%. Both matching passes share the existing deadline and
an actual cumulative two-million-comparison cap.

The rebuilt HTTP six-sample matrix now improves Chinese −6° **32.65% → 1.02%
CER**, aligned recall **74.49% → 98.98%**, exact lines **1 → 7**. It preserves
`12345` and returns explicit reliable-text conflict warnings. The remaining
error is a colon substitution. Other handoff results are unchanged, including
English +6° at **0.34% CER**, which deliberately retains `80424` despite truth
`80421`, with `OCR_RECOGNITION_CONFLICT`. This is not numeric truth correction.
Word/PDF/OFD layout OCR still disables deskew; scan text/media are unchanged.

## Frozen broader holdouts

Sixteen new bilingual positive cases cover 0°, ±2°, ±4° and ±7°, six font
families, single/two-column layouts, grayscale shadows, colored unknown marks,
leading zeros, signed amounts, decimals and dates. Three controls cover white
blank, gray blank and isolated noise. Truth is independently fixed source text;
no field, angle or source text is adjusted based on candidate output.

The first generator incorrectly painted ASCII digits with Droid's missing
glyph. That baseline run was stopped and **excluded**. Glyph coverage now
selects explicit licensed Latin fallback or fails for an unsupported character.
Valid input hashes were frozen before the production matching change.

| Holdout | TXT CER before → after | Exact lines before → after |
|---|---:|---:|
| en-sans-upright.png | 0.00% → 0.00% | 8 → 8 |
| en-sans-negative2.png | 0.00% → 0.00% | 8 → 8 |
| en-sans-positive7.png | 0.00% → 0.00% | 8 → 8 |
| en-serif-negative7.png | 0.00% → 0.00% | 8 → 8 |
| en-serif-positive4.png | 0.00% → 0.00% | 8 → 8 |
| en-mono-positive2.png | 0.00% → 0.00% | 8 → 8 |
| en-mono-shadow.png | 90.73% → 90.73% | 0 → 0 |
| en-sans-columns4.png | 62.16% → 62.16% | 8 → 8 |
| zh-droid-upright.png | 0.68% → 0.68% | 7 → 7 |
| zh-droid-negative4.png | 2.03% → 2.03% | 5 → 5 |
| zh-droid-positive2.png | 3.38% → 3.38% | 4 → 4 |
| zh-droid-positive7.png | 25.68% → 25.68% | 0 → 0 |
| zh-sans-negative7.png | 12.16% → 12.16% | 2 → 2 |
| zh-serif-positive4.png | 6.76% → 6.76% | 2 → 2 |
| zh-serif-shadow.png | 4.05% → 4.05% | 5 → 5 |
| zh-droid-columns2.png | 64.86% → 64.86% | 7 → 7 |

The new rule changes **none** of these 16 holdout outputs. This supports the
no-regression check on these cases; it does not show a broad accuracy gain.
All three controls return `OCR_NO_TEXT` for TXT and DOCX both before and after.
All 19 operational outcomes succeed as expected, including negative contracts.
All positive scan media hashes and editable scan Word text match baseline.
Unmasked scan margins remain visible after actual Office reopening.

Two-column high CER reflects reading order: English still has eight exact lines.
The shadowed monospaced case has only 24 of 259 expected characters at **88.93%
mean confidence**. It remains unresolved and demonstrates that confidence is
not completeness. Chinese +7° and some font variants also remain inaccurate.
These are retained failures, not removed from the corpus or reclassified as wins.

| Font | Version | SHA-256 |
|---|---|---|
| sans | Version 2.1.5 | `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8` |
| droid | Version 2.55b | `21b96a0377f067833a93af3082eb28d4ffab7a8cd46bfd513286f1d64b7b0949` |
| serif | Version 2.1.5 | `9caef765d2e891c10dd73658894f01e660fe0c1c83e0bda7d6edf561d8f623d4` |
| mono | Version 2.1.5 | `5883330d94debd992952cd8f0571b225f478c2d797d3f36c7521b0a5c9bde0f2` |
| cjk-sans | Version 2.004;hotconv 1.0.118;makeotfexe 2.5.65603 | `b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a` |
| cjk-serif | Version 2.003;hotconv 1.1.1;makeotfexe 2.6.0 | `5d9c31a059600193c9d7968a998bde886ccdc77e934006ad243b41794c496a7d` |

Repository Droid is Apache-2.0; the other listed fonts are SIL-OFL-1.1.
System-font licenses were checked in the Debian font package copyright files.
No font binaries are added. Pillow 12.3.0 and fontTools are QA dependencies.
Runtime/model/font details shared with the prior batch remain pinned as documented
in [the first cloud report](cloud-ocr-batch-20261003.md).

## Word rotation: rejected compatible-OOXML probes

`probe_word_rotation.py` constructs isolated VML and DrawingML WPS frames,
opens each with actual Office and measures PDF text baseline direction through
PyMuPDF 1.26.6. Source is editable `w:txbxContent`, not an image of text.

| Representation | Requested angle | Actual PDF baseline | Searchable text |
|---|---:|---:|---|
| VML control | 0° | 0° | complete |
| VML frame | +6° | 0° | complete |
| DrawingML WPS | +6° | 0° | complete |
| DrawingML WPS | −6° | 0° | complete |
| WPS WordArt textPlain | +6° | no text baseline | absent; outlines |

All probes reopen as one page. WordArt visibly rotates but stretches spacing
and exports drawing paths; it fails ordinary editable/searchable-text acceptance.
The textNoShape/fromWordArt variations also keep ordinary text horizontal.
Earlier grouped WPS and Office's own rotated ODT→DOCX roundtrip failed as well.
No experimental renderer is enabled. These are isolated frame probes; scan-media
conservation is tested by the separate HTTP matrix.

This is measured on LibreOfficeDev 26.8.0.0.alpha0 commit
`2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`, not a claim about Microsoft Word
or the pinned native release runtime. Bounded alternatives for later work are:
retain original scan layout with present OCR limits; provide a separately labeled
horizontal transcript; or test a pure-scan whole-page upright presentation that
keeps original media and an explicit source-coordinate transform. The latter
would require page/mask/geometry acceptance and must not silently affect mixed
native-text pages. None of these alternatives is adopted in this batch.

## Validation and cost

Full bundled Maven: **377 tests, 376 pass, zero failures/errors, one optional
real signed-OFD skip**. All nine deskew tests run. A final targeted rerun verifies
that high page-average confidence cannot make a weak exact-match token reliable.
Ambiguous identical candidates, duplicate claims, outside centers and changed
numbers reject recovery. Existing numeric conflicts, inverse coordinates,
wide-edge projections, optional timeout/failure cleanup and vertical/layout/
anisotropic guards still execute. Packaging, Python/Java QA compilation,
`git diff --check` and local-only privacy gate pass.

Baseline JAR SHA-256:
`7c97bcdb152446db75f1920fcd12ee9f432f3642e6e72fca5a68d79f3a702b3f`.
Candidate JAR SHA-256:
`83c44ce2b84d9a3bef810f9e5cc1ec641075b8b49271d2c21efba98db48ef8d2`.
The valid holdout runners observe 103/102 independent JVM worker PIDs; the six
handoff rerun observes 37. Before/after holdout child peak RSS is
355,824/352,264 KiB; child user/system CPU is 748.476/51.175 versus
698.709/47.189 seconds. These are shared-host single observations and **not a
speed claim**. Peak RSS is the largest child, not concurrent aggregate memory.
The matching change adds no image or OCR process; extra matching remains bounded.

Raw reports, warnings, source hashes, Office PDFs, rasters and resource observations
remain ignored under `qa-samples/report/iteration2-*`. Private corpus, real
signed-OFD, Microsoft Word and native macOS/Windows packages remain unrun.

## Reproduction

Activate the pinned cloud environment and bundled OCR as in the first report.
Preserve the baseline JAR before rebuilding candidate source.

```bash
python3 qa-samples/generate_ocr_holdouts.py \
  --serif /usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf \
  --mono /usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf \
  --cjk-sans /usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc \
  --cjk-serif /usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc
python3 qa-samples/run_cloud_ocr.py --jar <preserved-baseline.jar> \
  --samples qa-samples/generated/cloud-holdouts --out qa-samples/report/iteration2-before \
  --reaper /workspace/fuyue-env/reap-run.py
# Run the full bundled suite and package candidate, then repeat with its JAR.
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-holdouts \
  --out qa-samples/report/iteration2-after --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/probe_word_rotation.py --out qa-samples/report/iteration2-word-probe
```

Font paths are explicit; another environment must provide matching licensed
fonts and verify manifest hashes. `OcrDeskewDiagnostic.java` is package-local QA
that invokes single-pass OCR to record original/candidate geometry and decisions;
it does not alter the production selection policy.
