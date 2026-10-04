# Iteration13: finite gate-coverage investigation

This batch starts at `7a941441e6624aaba5eb8f0d16a0377ddf8a7abf` and changes QA/docs
only. Production inputs, all223 application classes and the accepted JAR remain
unchanged. The prior413-pass/one-skip full build,97 HTTP contracts and28 Word
artifact comparisons remain evidence at7a941441; they were not repeated.

## Why the previous44 native samples did not cover the condition

Read-only instrumented copies are generated from the exact repository Java source.
They wrap each early-return predicate, preserve its single evaluation, and record
pass/reject/not-reached plus the source expression/line/hash. Actual production
arrangement and the traced copy must have identical text and all three flags.
Forty-four **existing TSVs were re-read; no prior OCR was rerun**. All44 trace
results match production. Every direct strict gate and outer reading-order gate
is retained in [the audit JSON](cloud-ocr-iteration13-results.json).

Forty-one cases have fewer than27 blocks and fail the first strict gate. Three
sparse Mono52 cases pass that stage but have only two first-row fragments instead
of the required3–6: the upright case has per-column fragment counts
[2,2,2,2]/[3,3,3,3]/[3,3,3,3]; −1.10° has2/2/3; −1.60° also splits some third-column
rows. These are recognition segmentation failures to qualify, not observations
that a qualifying tilted page is safe.

## Analytic feasibility and independent flag/resource checks

For equally sized fragments under a rotation theta, the important necessary
geometry relations are approximately:

- within-row center span D: `D*abs(sin(theta)) <= 0.35*medianRotatedHeight`;
- first-to-last row span S: `S*abs(sin(theta)) <= 0.5*medianRotatedHeight`;
- all three columns must also retain at least60% common vertical coverage,
  valid gutters,3–6 neighboring fragments and at least3 separated rows.

These relations do **not** imply that every genuine tilt is rejected. A separately
constructed actual-Java27-block model with60×20 boxes,140 center span and200 row
span passes at2° while4° is rejected. Already-ordered successful fragments have
`adjusted=false`, `multipleColumns=true`, `fragmentedColumnsValidated=true`.
Numeric fallback, expired deadlines,501 blocks and5001 words keep validation
false. Offsets49.9/exactly50 pass the60% boundary;50.1 rejects. All original source
objects remain identical. Ten independent checks pass. They establish geometry
and flag semantics, **not native OCR quality**. The source5000-word gate remains;
strict fragments have their own tighter≤2 words/block bound. Measurements and
budgets for these checks are recorded, not used to claim general runtime speed.

The known native qualifying family has about870px row span and21–24px ink height.
Its half-height edge budget is roughly0.69–0.79°, tighter than the short model.
The four preselected true angles±0.35°/±0.65° therefore target that feasible region.
The global projection detector still requires≥1° and can mistake inter-column
stagger for a larger angle; applied image angles and detector estimates are
separately recorded.

## Small frozen native corpus and explicit stopping result

Before any algorithm edit, seed130042026 froze one distinct Spruce/Lagoon/Orchid
upright page and four signed-angle variants. Mono29px,2400×1500/300DPI, three
columns720px apart, four rows290px apart, stagger0/35/70px; ticket07–10 truth is
written by the generator, never inferred from OCR. Manifest SHA-256:
`e0489fbea93da37e692647189be42440fa89b820ef976e6a54c04b9d40cc29ed`.

That upright native run produced32 blocks: third-column `Orchid keeps` became a
two-word block, leaving only two fragments per row. It failed the explicit3–6
fragment check. **Its four angle variants were not run**, and their existence is
not counted as safety evidence.

One final, reason-directed construction replaced only that merged label with the
known separately recognized Copper label. Seed130142026 froze all five inputs
before measurement; manifest SHA-256:
`d1fc1abf730aeca01b42a45693cb3c00e54e021ce63b18d935508f1b8663770e`.
The distinct upright now produces36 native blocks and passes every strict gate.
Both corpora and all ten generated PNGs reproduce byte for byte in new directories.
Font source/license/version/hash, true transform and source baselines are in
[the first corpus](cloud-ocr-iteration13-corpus.json) and
[the targeted corpus](cloud-ocr-iteration13-target-corpus.json).

|Target native case|Blocks|Strict gate|Detected global angle|1aee8c4 HTTP CER|7a941441 HTTP CER|
|---|---|---|---|---|---|
|Upright|36|Success|2.75°|21.05%|3.95%|
|−0.35°|28|Block word/letter consistency|2.50°|21.05%|21.05%|
|+0.35°|16|Block count<27|3.00°|21.93%|21.93%|
|−0.65°|32|Fragments per row|2.00°|21.49%|21.49%|
|+0.65°|16|Block count<27|3.25°|21.93%|21.93%|

All ten paired authenticated HTTP contracts (five per exact JAR) passed via
upload→observed separate JVM Worker→download. The current upright preserves the
native recognized inventory, removes the false deskew warning and retains nine
original character errors; nothing is corrected from truth. The four genuine
tilts reject the guard and retain exact before/current text, numbers and metrics.
Six unique new native runs completed: the rejected upright, qualifying upright
and four true tilts. The first two upright runs have no isolated CPU/RSS counters;
the remaining four carry exact commands/exit/time/CPU/RSS. No failed experiment
is relabeled successful.

**This finite angle investigation stops here.** Geometry permits a qualifying
true tilt, but none of these four native tilted outputs qualifies because of
segmentation. No suppressed beneficial native deskew is demonstrated; no general
safety claim follows. The conditional suppression risk stays open. No additional
angle/font sweep, guess-based table classifier or production change is adopted.

The diagnostic helper initially failed compilation because a nanosecond literal
lackedL; that failure is recorded and corrected. A decimal-angle `with_suffix`
logging bug overwrote two earlier stderr paths. Surviving logs are empty; all TSV,
probe, per-run exit/resource and aggregate output records remain. The logger now
appends `.log`, and no OCR was repeated to conceal missing logs. Both limitations
are retained in local review evidence.

## Next finite improvement candidate: shadow candidate quality

A new independent actual-Java diagnostic reuses existing EN-serif and ZH-sans
original/enhanced TSVs. There is **no new shadow OCR, threshold/DPI sweep or gate
relaxation**. Both sources have stable original geometry, uncovered shaded ink and
numeric surfaces preserved by the candidate. Actual full/partial selectors retain
the exact original object. Existing HTTP warnings already expose possible omission.

Each candidate has four additional vertically separated rows. EN original
confidence0.921481 requires0.971481 under the retained five-point gain; candidate
is0.948468, and new-row averages are0.920150/0.916121/0.945537/0.966248. ZH original
0.926264 requires0.976264; candidate0.920874, new-row averages
0.925295/0.927217/0.907754/0.869941. **All eight new rows fail the unchanged gain**,
so bypassing only the page-average gate would still fail row quality. EN candidate
is complete against this truth while ZH is nearly complete; confidence still does
not establish completeness. Current EN48.85%/ZH55.42% CER remains unresolved.

The highest-value next bounded hypothesis is a separate **PSM6 enhanced-candidate
quality diagnostic** for these confirmed single-column missing-row cases, with
pinned engine/model and unchanged enhancement, minimum/gain/numeric/source-object/
geometry/deadline selectors. Compare against retained PSM3 candidates on these
two cases plus upright numeric and blank/noise controls before any adoption.
Accept only reproducible completeness gains that also pass every existing gate;
measure time/memory and forbid a new production retry unless that bounded evidence
justifies it. PSM6 is **unrun and unadopted here**; the previous53 threshold runs
remain untouched. This is a candidate-quality hypothesis, not a recommendation to
weaken confidence or infer intent.

## Reproduction and provenance

Generate tracing copies and compile against the unchanged accepted application
classes and pinned dependencies:

```sh
python3 qa-samples/trace_ocr_fragment_gates.py --out qa-samples/work/iteration13-trace
javac -cp 'task-service/target/classes:layout-model/target/classes:qa-samples/work/iteration9-threshold/deps/*' -d qa-samples/work/iteration13-classes qa-samples/work/iteration13-trace/*.java qa-samples/OcrFragmentGateTraceProbe.java qa-samples/OcrFragmentFeasibilityProbe.java qa-samples/OcrShadowCompletenessProbe.java
python3 qa-samples/generate_ocr_iteration13.py
python3 qa-samples/generate_ocr_iteration13_target.py
python3 qa-samples/summarize_ocr_iteration13.py
```

The audit needs the retained native/HTTP report paths documented in its source;
raw invocation records are in the local packet. The model/helper commands use
only existing TSV/image data; new native recognition is serialized before the
two HTTP matrices. Tokens, server logs and task storage remain excluded.

Accepted unchanged JAR SHA-256:
`1c3658c86536b0d06a76f583fdc39a6b68bfce77fb05b8f48e3c685e4ab7c112`.
Production-input fingerprint:
`7bd4b9d8566af920ba5c708c6ac06e9ec451e460f2035fbb9684b5d4f3318df2`.
Runtime/models/fonts remain those pinned in iteration12. New committed-source
binding and exact-head CI belong to the same draft PR; execution remains explicitly
at7a941441. No fresh Word/native-installer acceptance is asserted. Numeric/masking/
scan/source-coordinate/sparse-ink/cross-page/timeout behavior remains unchanged.
Native macOS/Windows/Microsoft Word/arbitrary edits/rotation/reflow are unrun.
No merge/release/main write/Mac access/Library403 retry occurs.
