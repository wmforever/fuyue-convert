# Iteration12: distinguish strict fragment validation from text-order change

Starting source is `94b2b738bab122b615a9757cd97d02c86007212c`. This finite batch
compares two immutable exact JARs on new truly tilted images and corrects an
independently reported guard-coverage defect. It does not infer table intent,
relax a recognition safeguard or establish general deskew safety.

## Actual newly frozen risk evidence

Before any production edit, seed120042026 froze32 new contracts:24 genuinely
tilted positive images,6 upright matched controls and2 tilted blank/noise controls.
All text/layouts are new; the completed iteration11 angle corpus is not used as a
substitute. Six layouts vary Mono/Serif/Sans/CJK fonts, row counts3–4, short column
spans, baseline staggering and date/amount/quantity/percent content. Signed
rotation angles are−1.05°, +1.05°, −1.40°, +2.20°, plus upright controls. Frozen
truth records unrotated baselines, real transforms and numeric text. Images use
2800×1500 pixels/300DPI; fonts/versions/licenses/hashes are in
[the corpus](cloud-ocr-iteration12-corpus.json), manifest SHA-256
`d3bc5e7ac0beb0777ea19332b218f84498ecc22f183ead6f841de15c8b645195`.

The1aee8c4 JAR `057ebfdb3f86e5104d6ab5c47ef5c38484fd70dc178004ab0ce628618ca9c04e`
and94b2b738 JAR `6d62577e4a646792727df153ab3169518c1f18e34e2f553aa01f2b7b01a4950e`
each ran32 authenticated HTTP TXT contracts sequentially. All passed and all
text/order/metrics/error results are exact. Thirty-two separate native bundled
PSM3 chi_sim+eng runs supplied actual TSV words to Java strict arrangement and
global projection. Fourteen old HTTP cases selected deskew, but **none of these
inputs passed strict fragmentation**, so their parity does not bound the
conditional risk of suppressing beneficial deskew after strict validation.

That coverage hole led to a second, finite corpus frozen before production edits:
seed120142026,12 new sparse Mono controls with32/44/52px fonts,3/4 rows and280px
spacing at3200×1700/300DPI. Nine are genuinely rotated−1.10°, +1.10° or−1.60°;
three are upright. New Larch/Alder/Aspen lot-number truth is independent of OCR.
[The sparse manifest](cloud-ocr-iteration12-sparse-corpus.json) SHA-256 is
`773b2c444f95c5e450554d2ad8ae6568c96d1faf8994666a83297949c16218ba`.
Both JARs passed12/12 with identical text/metrics; twelve further native runs
again failed strict fragmentation. Some native results combine complete rows
across columns instead of the required repeated short fragments.

All44 PNGs and both manifests reproduce byte for byte in new directories.
All44 native runs use the same pinned engine/model/PSM and120-second per-run
budget; no extra production pass or threshold alternative is introduced. Original
objects and non-whitespace character inventory remain exact through Java
arrangement; original word boxes/confidence, projection angles, numeric boundaries,
full text/CER/recall and resource records are retained in
[the full results](cloud-ocr-iteration12-results.json).

|New matrix|Exact JAR|Positive TXT route sum|Child CPU user+system|Peak RSS KiB|Observed worker PIDs|
|---|---|---|---|---|---|
|32 dense|1aee8c4|79.778s|189.964+12.166s|296824|32|
|32 dense|94b2b738|80.942s|194.761+11.313s|290264|32|
|12 sparse|1aee8c4|38.960s|91.493+4.910s|291776|12|
|12 sparse|94b2b738|38.289s|87.160+4.950s|318220|12|

The44 native command wall sums30.987s, CPU27.303user+3.137system seconds,
max123788KiB; all exit0, no failure/timeout. Route sums omit startup/error calls;
maximum positive HTTP calls4.754s/3.967s stay below120-second limits. Observed
worker PIDs are sampled, not an exact completed-call counter. These host-dependent
counters establish measured costs, not a performance gain.

**The documented conditional real-tilt risk remains open.** Neither44-case parity
nor successful conversion proves safety for a truly tilted page that qualifies
for strict fragments. This batch finds no native-image regression against1aee8c4,
but also supplies no genuinely tilted qualifying native positive. It records that
limitation instead of adopting a local-angle guess or weakening safeguards.

## Independently reproduced guard hole and smallest fix

Static review found a different, concrete defect: `adjusted` reports whether
compact text order changes, not whether strict fragment validation succeeded.
The same2400×1500 upright staggered ink and36 source TSV words pass all strict
bounds in either column→part→row or column→row→part enumeration. The second order
already has correct compact text, so `adjusted=false` and the94b2b738 guard retries
a false nonzero global rotation. This is **fake-engine/real-Java evidence**, not a
claimed observed native regression against1aee8c4.

Before production edits, two new actual Maven tests ran: the already-ordered
valid case failed because the deskew marker was created, while the strict-rejected
numeric-fragment fallback correctly retried. The log retains2 tests/1 failure/
0 errors/0 skips. Its nonzero projection is generated from real pixel ink; only
recognition TSV output is faked. Original words, source bytes and coordinates are
held constant; only enumeration changes.

The internal Result now has `fragmentedColumnsValidated`, false by default for
unchanged/two-column/fallback results and true only after every existing strict
three-column reconstruction check has succeeded. `adjusted` keeps its existing
text-order meaning. TXT deskew reads the explicit validation flag. No public API
shape, threshold, model/PSM, confidence/numeric/geometry/inventory gate or deadline
changes. On the previously missed valid case, OCR_DESKEW_APPLIED is correctly
absent because the optional candidate is never attempted. Rejected layouts still
retry; simply dropping `adjusted` would have incorrectly suppressed them.

Tests retain original source-object identity and check valid changed/already
ordered output, numeric/date/fullwidth lexemes, missing words, spanning headings,
dense/expired work, complete lines, merged rows, narrow gutters, disjoint sections
and the exact60% vertical-overlap boundary. Fallback/expired results must never
advertise validation; accepted boundary cases must advertise it. The fake-engine
integration checks both valid enumeration orders plus a rejected numeric layout.
**Focused59 tests passed**, zero failures/errors/skips.

## Final build provenance

Isolated clean test/package completed414 tests:413 passed,one optional signed-OFD
skip,zero failures/errors. New JAR SHA-256:
`1c3658c86536b0d06a76f583fdc39a6b68bfce77fb05b8f48e3c685e4ab7c112`.
Production-input fingerprint:
`7bd4b9d8566af920ba5c708c6ac06e9ec451e460f2035fbb9684b5d4f3318df2`.
All223 packaged application classes match fresh targets, aggregate
`a0fff897f1aaedf2bd5c032f3c7d672d46576018cfc2a2b58fd2ffb86eab4ab1`.
Execution uses the94b2b738 parent with recorded modified production inputs; final
committed-source verification is recorded separately after publication. Exact
final commit/remote SHA and CI jobs are in the same draft PR and packet receipt.

## Final actual artifact acceptance

The new JAR passed **97/97 final authenticated HTTP contracts** through upload →
separate observed JVM Worker → artifact download:44 new risk controls,16 previously
accepted image/Word contracts and37 prior TXT regressions. All44 old/current/final
texts, ordering, numeric boundaries, completeness metrics and error contracts are
exact. The earlier8-angle corpus is used here only for regression parity. All
prior gains/errors remain exact, including the2.63% upright staggered EN result.

**28 actual direct/scan Word artifact comparisons** retain1688 source frames,
721 masks and1060 Office word objects. Every original scan/media, frame text,
coordinates/style/rank, actual Office glyph boxes/order and complete rendered
pixel image is exact. All direct/scan editable and Office text/metrics match the
prior accepted source. This is artifact parity, not complete recognition of truth.
All positive Word samples were reopened through real Office conversion; blank/noise
retain explicit TXT/DOCX OCR_NO_TEXT. No new general edit/reflow or OFD wrapper
acceptance is claimed.

Final Word positive routes sum249.625s, child CPU613.006user+
35.571system seconds, max393044KiB and
90 observed worker PIDs. Maximum positive
conversion5.782s remains below120 seconds. Full final resources for each
new/regression matrix are in the results JSON; these are not isolated performance
benchmarks. All failed-before evidence and both original risk baselines remain
preserved; no failed artifact/test is counted as passing acceptance.

## Acceptance boundaries and reproduction

All resource-using stages are serialized: old HTTP, current HTTP, native
inspection, supplement HTTP/native, focused tests, clean full build, then final
HTTP matrices. This avoids the previously observed shared-permit timeout/capacity
contention. Failed-before logs remain intact. No previous39-focused or53 threshold
CLI diagnostic is repeated as baseline evidence.

Activate `/workspace/fuyue-env/activate.sh` and set
`FORMAT_CONVERTER_APP_HOME=/workspace/fuyue-convert/desktop/.runtime`.
Generate only into fresh directories:

```sh
python3 qa-samples/generate_ocr_iteration12.py
python3 qa-samples/generate_ocr_iteration12_sparse.py
```

Use run_cloud_ocr.py with `--text-only`, each frozen sample directory, a distinct
report directory, the chosen exact `--jar`, its matching `--provenance`, and
`--reaper /workspace/fuyue-env/reap-run.py`. The1aee8c4/current reports use suffixes
before/current; final source reports use final. Native inspection is explicit:

```sh
javac -cp 'task-service/target/classes:layout-model/target/classes:qa-samples/work/iteration9-threshold/deps/*' -d qa-samples/work/iteration12-classes qa-samples/OcrDeskewGuardRiskProbe.java
python3 qa-samples/inspect_ocr_iteration12.py --classpath 'qa-samples/work/iteration12-classes:task-service/target/classes:layout-model/target/classes:qa-samples/work/iteration9-threshold/deps/*'
```

The inspector accepts explicit sample/output/before/current/manifest-hash arguments
for the sparse corpus. It never overwrites completed experiments. Compile against
the chosen immutable baseline classes to retain historical diagnostic identity.
Final audit: `python3 qa-samples/summarize_ocr_iteration12.py` after all documented
reports exist. Raw native TSV/probes/helpers and exact test/build logs are kept
with local review evidence; credentials/server logs/task storage stay excluded.

Runtime remains Temurin17.0.16+8, Maven3.9.11, Node22.17.0;
Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1;
tessdata_fast87416418657359cb625c412a48b6e1d6d41c29bd;
LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5;
pdftotext25.03.0, Pillow12.3.0, PyMuPDF1.26.6, fontTools4.61.1.
LiberationMono/Serif/Sans2.1.5 and NotoSansCJK2.004 are SIL-OFL-1.1. Exact font,
model, binary/policy and source/artifact hashes remain traceable in the evidence.

Sparse-ink recovery, cross-page masking, original media/text/coordinates and all
accepted numeric/timeout safeguards remain. High-confidence shadow omissions,
pixel-identical conflicting table truth, older shaded/−6° regressions, private
handwriting, Word rotation/reflow/arbitrary edits remain unresolved. Native
macOS/Windows installers/Microsoft Word acceptance remains unrun. There is no
merge/release/main write/Mac access/Library403 retry.
