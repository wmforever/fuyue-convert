# Iteration11: retain aligned original fragments before global deskew

This batch starts at accepted source `1aee8c4fc7a1c85f1c09ed42b0eaee0baec6cddc`.
It adds a conservative TXT deskew guard and a failing-before/passing-after
regression. An upright, slightly staggered three-column page can imitate a
nonzero global baseline. Rotating that entire page damages an original
recognition whose fragments already satisfy the existing strict column bounds.
The guard retains that original recognition; it does not correct text from truth.

## Frozen truth and independent discovery

Before changing the algorithm, seed110042026 generated16 image contracts,
14 unique images: English and Chinese prose tables and true columns, ruled
controls, upright numeric controls, four new shadow/font controls, and blank/noise
controls. The unruled table/aligned-column pair in each language intentionally
has identical pixels and different row/column reading truth. They are not
independent replicates. Pixel geometry cannot infer their declared reading intent.
Eight additional ±3° aligned/staggered-column controls were frozen before edits.

Manifest hashes are
`cac258df4f6f80046529d25c85cf039605cab55d89f039f670944e07f6dc1ab1` and
`14eedc97794be2ac779dc9667414b069fa48b3f5dc57103518b23dc530b3d815`.
All16+8 PNGs and both manifests reproduced byte for byte in new directories.
Images are2400×1500 at300DPI; fonts are LiberationMono/Serif2.1.5,
NotoSansCJK2.004 and NotoSerifCJK2.003. Public synthetic text/code is Apache-2.0;
fonts are SIL-OFL-1.1. Exact font hashes, versions, truth and generator hashes are
in [the frozen corpus](cloud-ocr-iteration11-corpus.json) and
[signed-angle controls](cloud-ocr-iteration11-rotated-corpus.json).

Fourteen sequential unique bundled-native PSM3 chi_sim+eng runs supplied actual
TSV records to Java parsing/reading-order code. Original source objects, character
inventory, numeric runs and coordinate evidence were retained. The preceding
hand-constructed Java counterexample (CER0→18.63%) was **not reproduced as an
already correct native-image result becoming wrong**. Native OCR itself grouped
unruled prose cells into columns. The same-pixel EN pair ends at20.18% CER against
row-major truth and2.63% against column-major truth; ZH ends22.22% and0%.
Ruled controls remain2.19% EN/0% ZH. No general table discriminator is adopted.

The new EN staggered-column image exposed a different reproducible failure:
original native fragments reconstruct to2.63% CER, while actual baseline HTTP
TXT applies a false2.50° whole-page rotation and produces22.37% CER. Original
confidence is0.829209; the rotated candidate is0.817283. Existing deskew selection
permits this within its0.05 confidence floor; the five-point enhancement gain is
a separate rule. Confidence did not establish completeness or correct order.

## Bounded implementation and validation

Before preparing any rotated image, the TXT deskew retry asks the existing
OcrReadingOrder arrangement whether the original has adjusted, multiple-column
fragments. Only that strict existing reconstruction can skip deskew. Its27–500
block and5000-word bounds, three columns, repeated3–6 fragments per row, at least
three rows, word/height/gutter/narrative/character/numeric/source-identity checks,
common vertical coverage≥60%, and original deadline all remain. No additional
recognition pass, PSM/model/threshold/confidence policy or timeout is introduced.
Word/PDF/OFD recognition still disables this TXT retry and retains its original
coordinate/masking behavior. Preserved scans are not a completeness metric.

A portable fake-engine test uses actual2400×1500 staggered ink,36 TSV fragments
and a marker that fails if deskew is attempted. The real projection detector must
report a nonzero angle; after the fix the marker remains absent, all36 original
words and exact column text remain, deskew degrees are0 and source bytes are
unchanged. That one new test failed before the production fix and passed after.
Focused deskew/fragment/reading-order/partial/contrast tests: **56 passed**, zero
failures/errors/skips. This is new validation; the prior39-focused and53 threshold
CLI experiments were not repeated.

The first full build ran while baseline Word HTTP held the global OCR permit.
The100ms timeout test received OCR_CAPACITY_EXCEEDED instead of OCR_TIMEOUT and
failed before recognition. The failed log is preserved. Post-build provenance
also rejected the old web JAR against newly compiled classes; no stale JAR was
accepted. After the HTTP matrix ended, the isolated clean test/package rerun
passed **411 total/410 passed/one optional signed-OFD skip**, zero failures/errors.
The capacity/timeout gates and test assertions were not weakened.

New JAR SHA-256:
`6d62577e4a646792727df153ab3169518c1f18e34e2f553aa01f2b7b01a4950e`.
Build-input fingerprint:
`799917032a04f529532bf70b6df2785b414be94f92ddf645c7054ebb976a035d`.
All223 packaged application classes match fresh target classes; aggregate
`53f265bf15c611fdffe595a80eb1af5337837712b595aa8659d571dd47d7f6c2`.
Execution used the1aee8c4 parent plus the recorded dirty source; the committed
revision is separately bound after publication. Exact-head CI belongs in the PR.

## Actual HTTP/Word artifact acceptance

The new JAR completed all16 frozen image contracts via authenticated upload →
independently observed JVM worker → artifact download. Fourteen positive images
ran TXT and direct DOCX→Office PDF plus image→scan PDF→DOCX→Office PDF. Blank/noise
retain explicit OCR_NO_TEXT in both TXT and DOCX. No failure is counted as a
recognized blank page. EN staggered TXT improves **22.37%→2.63% CER** (51→6 edits,
230→228 characters against228 truth, aligned recall97.37% after), retaining the
original recognized inventory and losing the false OCR_DESKEW_APPLIED warning.
The other15 TXT outputs/metrics are exactly baseline, including ambiguity pairs,
numbers and incomplete shadows.

**28 exact artifact comparisons** cover every positive direct/scan Word variant:
1688 source text frames,721 masks and1060 actual Office word objects. All original
coordinates, text/style/rank, original scan media, Office glyph boxes/order and
complete rendered pixels are exact. Direct/scan editable and Office text remains
unchanged. This is artifact parity, not completeness against truth. This batch
does not repeat the earlier Warehouse→Depot edit or claim arbitrary editability.

|16-image Word matrix|Sum positive route wall|Child CPU user+system|Peak RSS KiB|Observed worker PIDs|
|---|---|---|---|---|
|Baseline1aee8c4|285.514s|620.758+50.194s|392140|88|
|New JAR|240.407s|590.797+39.617s|391620|88|

Route sums exclude startup/error calls; maximum positive calls8.993s/5.939s remain
below the unchanged120-second budgets. PID sampling is not an exact completed-call
counter. Shared host/build activity and differing schedules prevent a performance
claim. Full per-route text/completeness, confidence/coordinates and resources are
in [the measured results](cloud-ocr-iteration11-results.json).

The8 signed-angle controls and20+9 prior TXT regression contracts also passed
with exact text/order, numeric boundaries, completeness metrics and expected
failure behavior. Together the final HTTP matrices cover53 contracts. This53 is
the contract count; the old53 threshold CLI diagnostic remains untouched. No new
PDF/OFD wrapper or signed-OFD/native-installer acceptance is claimed; existing
artifact acceptance remains documented in iteration10.

## Fresh shadow diagnostic and limits

A new bounded diagnostic compares identical enhanced pixels without DPI metadata
and with the original300DPI pHYs metadata. Four shadow images and noise each ran
two sequential bundled CLI variants: **10 runs**, no failure/timeout. Both blank
alternatives were unrun because enhancement returned null. Every candidate text,
confidence and word box is exactly identical between metadata variants. This
contains new source images and is not a repeat of the prior53 threshold runs.

Empty-original EN mono and ZH serif shadows already recover completely via the
accepted enhancement path. EN serif remains48.85% CER, despite a complete
candidate whose confidence improves only2.70 points, below the retained five-point
gate. ZH sans remains55.42%; its near-complete candidate decreases confidence
by0.54 points. High-confidence missing text remains unresolved. No threshold or
DPI production change is justified by these observations; no safeguard is relaxed.

The new deskew guard may conservatively suppress a beneficial global retry on an
unmeasured tilted page whose original still qualifies as aligned fragments. The
signed-angle controls and prior regression matrices bound this risk; they do not
prove arbitrary layouts safe. Six original record0N→recordoN errors remain.
Reading order gains do not manufacture missing digits. Pixel-identical table
intent, high-confidence shadow omissions, older shaded/English−6° regressions,
Word rotation/reflow and arbitrary editing remain unresolved.

Linux cloud bundled-runtime acceptance is separate from native macOS/Windows
installers and Microsoft Word, which remain unrun. Existing releases/installers
are stale. No release, merge, main write, Mac access or Library403 retry occurs.

## Reproduction and runtime

Activate the published cloud toolchain and set
`FORMAT_CONVERTER_APP_HOME=/workspace/fuyue-convert/desktop/.runtime`.
Generate truth only in fresh directories; never overwrite a frozen manifest:

```sh
python3 qa-samples/generate_ocr_iteration11.py --out qa-samples/generated/cloud-iteration11
python3 qa-samples/generate_ocr_iteration11_rotated.py --out qa-samples/generated/cloud-iteration11-rotated
python3 qa-samples/record_cloud_provenance.py --record qa-samples/work/iteration11-build-provenance.json --before
python3 /workspace/fuyue-env/reap-run.py mvn -B -ntp -Dskip.frontend=true clean test package
python3 qa-samples/record_cloud_provenance.py --record qa-samples/work/iteration11-build-provenance.json --after
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration11 --out qa-samples/report/iteration11-final-word --provenance qa-samples/work/iteration11-build-provenance.json --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/run_cloud_ocr.py --text-only --samples qa-samples/generated/cloud-iteration11-rotated --out qa-samples/report/iteration11-final-rotated --provenance qa-samples/work/iteration11-build-provenance.json --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/run_cloud_ocr.py --text-only --samples qa-samples/generated/cloud-iteration7-contracts --out qa-samples/report/iteration11-final-prior20 --provenance qa-samples/work/iteration11-build-provenance.json --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/run_cloud_ocr.py --text-only --samples qa-samples/generated/cloud-multicolumn-boundaries --out qa-samples/report/iteration11-final-prior9 --provenance qa-samples/work/iteration11-build-provenance.json --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/summarize_ocr_iteration11.py
```

Run matrices sequentially; their independent worker JVMs share the host OCR
capacity gate. The runner creates ephemeral private authentication, observes
separate worker PIDs, downloads artifacts and tears down server/children. Tokens,
server logs and task storage are excluded from source and review packets.
Native discovery uses pinned Tesseract with `--psm 3 -l chi_sim+eng tsv`, then
OcrReadingOrderImageProbe parses those TSV words at the original300DPI geometry.
The packet preserves its exact commands,14 native TSV/logs, measured counters and
10 new DPI diagnostic records/inputs/helpers. The Java probe never edits source
objects or recognizes words from truth.

Runtime: Temurin17.0.16+8, Maven3.9.11, Node22.17.0;
Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1;
tessdata_fast revision87416418657359cb625c412a48b6e1d6d41c29bd;
LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5;
pdftotext25.03.0, PyMuPDF1.26.6, Pillow12.3.0, NumPy2.3.5, fontTools4.61.1.
Exact runtime binary/model/policy hashes are recorded in the full measurement JSON.
