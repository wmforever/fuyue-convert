# Cloud iteration 5: bounded Word line-frame rejection

Subsequent numeric/scope fixes and their measured regressions are documented in
[iteration 6](cloud-ocr-iteration6.md). This page remains historical rejected evidence.

Baseline `11f6ce779b118c623cebeeccb7bfe3e430f0f39a`, same wmforever branch and
draft PR #1. No production renderer change is adopted. The implemented offline
prototypes and measured results are committed so the next iteration does not
repeat an attractive extraction-only regression.

## Actual implementation and adoption decision

`experiment_word_line_frames.py` converts recognized per-word VML rectangles
into one line textbox with individual runs, original run properties and absolute
tab stops. Existing per-word masks and source media remain byte/structure-identical;
XML text is conserved with no second hidden line. Diagnostic alternatives use
continuous proportional flow or a monospaced substitution. Those alternatives
explicitly relax word geometry and are not production candidates.

The prototype falls back for existing tables, simultaneous disjoint columns or
a clear horizontal gutter, non-monotone positions, declared rotation, mixed
frame baselines and large unknown gaps. These are **DOCX-level experiment guards**,
not a validated production eligibility rule: the existing per-word frames have
already normalized their top/baseline, so original tilted word geometry cannot
be reconstructed reliably from that DOCX alone. This limitation is not hidden
by calling a tilted frame horizontal.

Eight frozen synthetic cases cover the two measured failures, independent serif
and CJK fonts, different line spacing, punctuation and signed/zero-padded digits,
the handoff upright/+6-degree controls, and English/Chinese columns. Truth and
source PNG hashes were frozen before these candidates; no private corpus is used.
The evaluated eight cases × three modes produce **24 actual Office PDF exports**.

| Input / candidate | Default PDF CER before → after | Maximum glyph center shift | Changed pixels outside original word masks |
|---|---:|---:|---:|
| English mono shadow / positioned runs | 5.41% → 5.41% | 0.050 pt | 0 |
| English mono uniform / positioned runs | 11.36% → 11.36% | 0.050 pt | 6 |
| English mono shadow / continuous flow | 5.41% → 0% | 10.867 pt | 1,123 |
| English mono uniform / continuous flow | 11.36% → 0% | 8.819 pt | 996 |
| English mono shadow / mono substitution | 5.41% → 0% | 4.165 pt | 159 |
| English mono uniform / mono substitution | 11.36% → 0% | 4.227 pt | 80 |
| Independent CJK uniform / positioned runs | 0% → 19.61% | 121.687 pt | 5,840 |

The table compares extraction with the already recognized text. CJK OCR itself
has 7.92% truth CER; the positioned candidate worsens PDF truth CER to **27.72%**
and moves `2026` before `00684`. Zero extraction error against OCR is not zero
OCR error. Committed [JSON results](cloud-word-line-frame-results-20261003.json)
include both recognized-text and independent-truth metrics.

Both column cases trigger fallback: XML/source/media, glyph coordinates and raster
remain unchanged. Their pre-existing Word/PDF extraction order remains wrong
despite improved TXT order. Upright independent serif/English positioned runs
remain at0% extraction CER with at most0.050pt rounding; continuous candidates
move glyphs by up to3.535pt even where extraction was already correct.
The +6-degree original Word fallback retains its previous partial recognition;
this experiment does not transfer the TXT-only deskew result into Word.

No variant meets the combined adoption condition. Positioned lines preserve the
two target geometries but do not improve extraction; other variants improve
their extraction by moving glyphs into previously unmasked gaps. CJK tabs can
wrap and overlap severely. Production word boxes, masks, numbers, source scans,
timeouts and editability therefore remain at the accepted baseline.

## Geometry, editability and cost evidence

PDF non-whitespace glyph inventories match before/after in all24 cases. Each
candidate preserves source-media SHA, exact XML text, per-word mask XML and one
page. An actual edited positioned DOCX replaces `Delivery` with `EDIT007`; Office
exports the changed word, removes the original and introduces no duplicate line.
These checks do not make the rejected typography safe.

Glyph centers are compared with **the prior actual Office PDF**, not asserted
original-source glyph truth. Nearest equal-character matching is bounded by an
exact inventory check; repeated characters can make this correspondence ambiguous.
Raster evidence is independent: at150DPI count channel differences greater than8
outside the original individual word masks, allowing one pixel for antialiasing.
Human review confirms the CJK tab wrapping. Existing CJK baseline overlay/source
alignment is itself imperfect; preserving it is not a claim of visual fidelity.
The HTML review links baseline/candidate raster images and actual DOCX/PDF files.

Twenty-four Office exports took18.30s in this single run; child peak RSS344812KiB,
child user/system14.97/4.40s including the edited reopen. These observations are
not a speed claim or summed memory. Prototype grouping reduces frame count but
provides no accepted extraction/geometry benefit.

Current executed versions: Temurin17.0.16+8; LibreOfficeDev26.8.0.0.alpha0,
commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5; **`/usr/bin/pdftotext25.03.0`**;
PyMuPDF1.26.6, Pillow12.3.0, NumPy2.3.5, fontTools4.61.1. Prior reports list
Poppler26.05.0; that historical label does not identify the executable used in
this experiment. All baseline/candidate PDFs were re-extracted using the same
current25.03.0 binary. Licensed Liberation2.1.5 and NotoSansCJK2.004 fixtures retain
their existing hash/font manifests. No new engine/dependency enters the app JAR.

## Production regression and reproducibility

Full Maven regression: **387 tests,386 passed,zero failures/errors,one optional
real signed-OFD skip**. Bundled contrast/runtime, actual Office, task lifecycle,
sparse ink, cross-page/masking and numeric/deadline checks execute.
Fresh authenticated handoff HTTP acceptance: **six cases,38 separate JVM workers**,
downloads and actual Office reopening. All TXT, editable/scan XML, scan media
hashes and original source RGB match the previous24-case acceptance report.
The wrong original80424 remains retained with a conflict warning; no truth number
is inserted. Child peak RSS341512KiB,user/system277.79/19.50s, single observation.
Frontend/desktop were not rerun because this batch changes only offline QA and
evidence; their prior20/69 passes remain attributed to the previous batch.

The fresh production run uses the unchanged accepted JAR SHA256
`9173918803d13de7ebde2a8bffea371a6040a2a08517222b25c9339a43e55c5c`.
Pinned bundled Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1 runtime
manifest is verified; system OCR does not substitute for this acceptance.
No app source/resources/POM changed; final source provenance explicitly verifies
that equivalence rather than claiming the offline prototypes are deployed.

```bash
source /workspace/fuyue-env/activate.sh
python3 qa-samples/evaluate_word_line_frames.py \
  --baseline qa-samples/report/iteration4-acceptance \
  --out qa-samples/report/iteration5-line-frames \
  --reaper /workspace/fuyue-env/reap-run.py
```

The baseline report/artifacts are generated by the prior synthetic API acceptance
recipe or extracted from its unchanged review bundle. `--baseline` refers to the
directory containing report.json, artifact-provenance.json and the eight scan
DOCX/PDF files; prototype generation never calls OCR or feeds truth to production.

## Delivery and next boundary

The existing107488113-byte Linux review bundle and SHA256
`2528c056a2c4582bfe3b26c8f82ea1f19bab24c074ce18d2c4d7ddb33b67f747` remain unchanged.
A separately identified experiment packet contains only public synthetic evidence,
offline prototypes and exact committed source; it is not a replacement app build
or an installer. Library saving remains blocked by the diagnosed403 network
tunnel before preparation; no retry or alternate access is attempted this batch.

The specific blocker is the extraction/geometry tradeoff, compounded by CJK tab
advance/wrapping and loss of original geometry metadata in the rendered DOCX.
A next bounded investigation could test whether tagged/ActualText export changes
logical extraction without repainting glyphs; that possibility is untested and
must not be promised. Source-relative glyph/mask fidelity, including the existing
CJK double-ink baseline, needs its own independent measurements before relaxing
position conservation. Arbitrary Word rotation, Microsoft Word and native
macOS/Windows package acceptance remain unresolved/unrun. No merge/release/main change.
