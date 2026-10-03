# Cloud iteration 6: numeric preservation and multi-gutter scope

Subsequent bounded partial recovery and exact source-region validation are in
[iteration7](cloud-ocr-iteration7.md). This page records the preceding safety head.

The review fixes real correctness defects. Reliable `.95` could become `95`
while merging a corrected `0.95` candidate; separate `12` and `34` could become
`1234`. Enhancement also accepted `95` inside `.95`. Regression tests failed
before the fixes. Original whole numeric-bearing words are now preserved rather
than splicing regex fragments into a candidate; ambiguous multi-word merges are
rejected. Separate decimal/sign/currency/percent/accounting tokens are retained.
ISO currency names use the JDK currency catalogue, not an unbounded parser.
Generic neighboring labels such as `Amount` or `金额` do not create a numeric
conflict when the number itself remains unchanged. Signed/fullwidth decimals,
grouping, currency, percent, leading zeros and accounting context are exercised.

The single-gutter TXT reorder also corrupted already-correct three-column engine
order. Two or more significant internal gutters now preserve exact engine lines
and return `OCR_READING_ORDER_UNCERTAIN` (issue #5). Existing two-column eligibility
and character/coordinate conservation remain. This deliberately limits inference;
it does **not** reconstruct three/four-column reading order. All nine actual
Tesseract fixtures below contain merged rows, so this fallback is worse than the
old partial reorder. The positive protection case is a Java geometry regression,
not a measured real-engine accuracy gain. Word/PDF/OFD ordering is unchanged.

## Accuracy and completeness costs

Before artifact: JAR `9173918803d13de7ebde2a8bffea371a6040a2a08517222b25c9339a43e55c5c`,
source verified against `11f6ce779b118c623cebeeccb7bfe3e430f0f39a` (production
code unchanged in iteration 5). After artifact: JAR
`bb3f6577c0f269afcc5cd3b408090e82421dc3b83e58e7071fa6e66537f8a391`.
The fresh clean build began with modified inputs at parent `26aa471`; acceptance
uses those exact inputs, not a JAR rebuilt from an older commit. After committing,
the provenance verifier binds unchanged inputs and JAR bytes to the final revision.
The intermediate `8f196c46…` run is diagnostic only and is not relabeled final.

| Frozen input | TXT truth CER before → after |
| --- | --- |
| English 0° | 0% → 0% |
| English −6° | 0% → **27.99%** |
| English +6° | 0.34% → 0.34% |
| Chinese 0° | 3.06% → 3.06% |
| Chinese −6° | 1.02% → 1.02% |
| Chinese +6° | 1.02% → 1.02% |
| Severe monospaced shade | 0% → **90.73%** |
| English tilted two-column | 0% → 0% |
| Chinese tilted two-column | 1.35% → 1.35% |
| Known English/Chinese vertical shade | 47.98% / 52.58% → unchanged |
| Shaded blank | Expected no-text errors → unchanged |

The guards cannot safely decide that reliable source `20` and `26` are fragments
of `2026`, or that reliable `-17` is a suffix of `2026-09-17`. They reject recovery
rather than silently fabricate numeric semantics. This leaves more missing text:
the shaded sample falls from 259 to 24 recognized characters. Its editable scan
DOCX XML CER also regresses from 0% to 90.73%; source pixels remain visible.
Numeric preservation therefore has a measured completeness cost. The intentionally
wrong reliable `80424` still survives the `80421` candidate with a conflict warning;
the guard does not prove that the original value is correct.

| TXT-only layout | Before CER | After CER |
| --- | --- | --- |
| Sans 3 / 4 / compact 3 columns | 4.37 / 5.65 / 3.57% | 6.75 / 6.25 / 5.95% |
| Serif 3 / 4 / compact 3 columns | 2.38 / 3.57 / 2.38% | 4.76 / 4.17 / 4.76% |
| Mono 3 / 4 / compact 3 columns | 5.16 / 6.25 / 6.35% | 7.54 / 6.85 / 8.73% |

All nine succeeded and emitted the uncertainty warning. Character inventories do
not establish reading order. Font confusion (`01` versus `1`, for example) also
remains. No generic three-column accuracy improvement is claimed.

## Production-chain and resource evidence

Clean `mvn -B -ntp -Dskip.frontend=true clean package`: **396 tests, 395 passed,
one signed-OFD fixture skipped**, no failures/errors. Bundled-runtime tests really
ran with the pinned runtime. Frontend20/desktop69 were previously verified in this
cloud environment; they were not rerun for this backend/QA batch. Exact-head CI
checks Java, frontend build, scripts, system OCR and Office separately.

Fresh final-JAR acceptance: 12/12 operational contracts, 69 distinct JVM workers;
11 positive inputs produced TXT, editable DOCX, scan-PDF→DOCX and reopened Office
PDFs. All 11 retained original scan pixels/media and margin/color probes. One
English control was edited and reopened, proving that change appeared in Office;
other edit fields are explicitly unrun. Deskew/reordering remains TXT-only:
four DOCX/TXT comparisons differ as expected. Positioned Word extraction and
rotation remain unresolved; scan DOCX CER is distinct from Office extraction CER.

Observed 12-case summed positive-route times: 187.925s historical → 210.517s
current. This is not a speed benchmark: the historical resource record covers
24 cases, and the current 12-case run overlapped the independent nine-case TXT
run. Current child peak RSS was 353124KiB, child CPU 513.010 user + 34.576 system
seconds; nine-case TXT run peak318380KiB, CPU88.300+6.480s. These are whole-run
child counters, not isolated per-worker process-tree peaks. Full per-case times,
CER/recall, warnings, numeric runs, Word/Office metrics, identities and resources
are in [the machine comparison](cloud-ocr-iteration6-results-20261003.json).
No timeout, concurrency or pixel limit was increased.

Runtime verified again: Temurin17.0.16+8, Maven3.9.11, Node22.17.0;
bundled Tesseract5.5.2, Leptonica1.87.0, libpng1.6.57, zlib1.3.1.
Binary SHA `90730922627269352568d54e788c7e42fd2e1783e03795d75560efa385de1489`;
policy SHA `54c6ae7291fc983cb7b946bc0b90832ec4b589d493fef01b5f974a0ab26d8735`.
Models: tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`,
eng `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`,
chi_sim `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730`.
Office: LibreOfficeDev26.8.0.0.alpha0,
commit `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`.
Actual Poppler/pdftotext is **25.03.0** (older iteration4 label26.05.0 is incorrect);
all22 baseline PDFs were re-extracted with the current default pdftotext command
and matched recorded metrics. PyMuPDF1.26.6, Pillow12.3.0, NumPy2.3.5,
fontTools4.61.1; Liberation2.1.5, Noto Sans CJK2.004, repository Droid fallback.
Font filenames/SHA/version/licences are retained in manifests; no font dependency
or runtime component was added.

## Reproduction and delivery boundaries

Use existing handoff, holdout and independent shading generators with the recorded
licensed fonts, then `python3 qa-samples/prepare_ocr_review_cases.py`. This selects
the same12 truths without deriving any from OCR. Generate the additional nine with
`python3 qa-samples/generate_ocr_multicolumn_boundaries.py`.

```bash
source /workspace/fuyue-env/activate.sh
export FORMAT_CONVERTER_APP_HOME=/workspace/fuyue-convert/desktop/.runtime
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration6-contracts \
  --out qa-samples/report/review-full --reaper /workspace/fuyue-env/reap-run.py \
  --provenance qa-samples/work/iteration6-reviewed-provenance.json
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-multicolumn-boundaries \
  --out qa-samples/report/review-columns --text-only --reaper /workspace/fuyue-env/reap-run.py \
  --provenance qa-samples/work/iteration6-reviewed-provenance.json
```

The review bundle builder accepts the current evidence document and optional
separate TXT-only reports, with source/hash/revision checks. The old107488113-byte
`11f6ce7` ZIP remains unchanged and represents an older runtime behavior; the Word
line-frame experiment packet remains rejected evidence. Neither was delivered to
Library: preparation was blocked by network403 before transfer. No further retry
or alternate upload is attempted. A new cloud ZIP must have its own final SHA and
revision; a local artifact is not a user download or a release.

Native macOS Intel/Apple Silicon, Windows packages, Microsoft Word, released native
Office, private business scans and handwriting remain unrun. This batch uses
public synthetic truth, not a fresh real-document accuracy corpus. It does not
adopt the rejected Word line-frame prototypes or imply installer acceptance.
