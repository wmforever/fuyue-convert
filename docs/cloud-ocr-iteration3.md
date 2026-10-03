# Cloud OCR iteration 3: shaded omissions and TXT column order

Bounded vertical-shadow alternatives, unresolved-coverage warnings and a
reviewable cloud build are recorded in [iteration4](cloud-ocr-iteration4.md).

Baseline `e06a4739f76239789faa2baa2df4c46b6fce379c`, same upstream branch and
draft [PR #1](https://github.com/wmforever/fuyue-convert/pull/1). Public warning
semantics were proposed in [#3](https://github.com/wmforever/fuyue-convert/issues/3)
before implementation. Only authenticated wmforever was used; main remains
`e3ffde164e90e766b7a9d6e3b1fae3f53b94d7d5`.

## Diagnosis and implementation

The frozen monospaced shadow case returned **24/259 characters at 88.93%
confidence**. Independently running the existing enhancer produced **259/259,
0% CER, 95.87% confidence**, passing the existing candidate selector unchanged.
Retry eligibility was the defect, rather than the enhancer or model.

`OcrCoverageProbe` examines a bounded RGB thumbnail and original-coordinate word
coverage. Strong shading, sufficient non-dense ink, multiple dispersed uncovered
bands and substantial uncovered ink permit the existing single contrast retry.
The probe never supplies text or consults truth. Its longest edge is 700,
word-box filling is capped at two million visits, and loops check the shared page
deadline. Blank, isolated dirt, uniform paper, dense content and extreme aspect
ratios remain guarded. Additional thumbnail/coverage pixel payload is at most
about 2.45 MB, excluding existing source pixels and Java object overhead.

Acceptance is unchanged: minimum confidence, at least five percentage points of
gain, at least 90% of original alphanumeric quantity, and ordered reliable text
with numeric-boundary protection. Source pixels and coordinates remain authoritative.
`OCR_IMAGE_ENHANCED` now accurately describes both low-confidence and coverage
recovery; not every adoption originates from low confidence.

`OcrReadingOrder` operates only on PNG/JPEG TXT output, after recognition. It
infers two prose columns from a wide global empty gutter, at least three lines
per side, substantial vertical overlap and a majority of narrative lines. Every
non-whitespace character is conserved; no word, amount, sign, decimal point or
leading zero is rewritten. Short numeric/label tables, crossing headings, narrow
gutters, missing word inventories and insufficient prose retain engine order.
Work is limited to 500 blocks/5000 words and the page deadline. Adoption returns
`OCR_READING_ORDER_ADJUSTED`. Original word boxes and Word/PDF/OFD layouts remain
unchanged. Long prose inside tables can still be ambiguous.

## Quality evidence

CER ignores whitespace and Latin case, retaining digits/punctuation. Recall is
aligned exact-character recall; exact-line counts cannot establish reading order.
Paired matrices use actual bundled OCR through authenticated HTTP, independent
JVM workers, downloaded artifacts and actual Office reopening. Expected text is
never fed to production code.

| Case | Before TXT CER | After TXT CER | Result |
|---|---:|---:|---|
| Frozen monospaced shadow | 90.73% | 0% | 24 → 259 characters; recall 9.27 → 100%; 0 → 8 exact lines |
| Frozen English columns +4° | 62.16% | 0% | All 259 characters conserved; order restored |
| Frozen Chinese columns −2° | 64.86% | 1.35% | All 148 characters/numbers conserved; existing punctuation errors remain |
| Independent English serif columns +3° | 50.29% | 0% | All 173 characters/numeric values conserved |
| Independent English sans columns −3° | 50.29% | 0% | Same conservation; explicit order warning |
| Independent Chinese columns +3° | 0% | 0% | Already correct; no order warning |
| Independent Chinese columns upright | 54.64% | 54.64% | Short-prose guard retains engine order |
| Independent table control | 5.88% | 5.88% | Same text/numbers/order; no order warning |
| Independent spanning-header control | 37.18% | 37.18% | Ambiguous page retained; no order warning |

Nine independent shading cases use different truth, fonts, paper ranges and
gradient directions, frozen before candidate API evaluation. All outputs are
unchanged. English vertical shading still returns 90/173 characters at **95.82%**
confidence (47.98% CER); Chinese vertical shading returns 46/97 at **92.81%**
(52.58% CER). The unchanged five-point gain rule remains a recovery limitation:
high confidence is not completeness. Three shaded blank/noise/single-mark
controls return `OCR_NO_TEXT` for TXT and DOCX.

All **40 candidate API cases** satisfy their operational contracts: 19 frozen
holdouts, nine independent shading cases, six independent column/control cases,
and six bilingual handoff cases. Exactly three frozen TXT outputs improve;
the other sixteen are unchanged. Independent shading is unchanged, two independent
English column outputs improve, and all six handoff outputs are unchanged from
the accepted e06 evidence. English +6° still preserves the wrong original
`80424` with a conflict warning although truth is `80421`; no number is supplied
from truth. All six negative cases return the same explicit no-text failures.
There are **217 observed independent worker processes** across the four candidate
matrices. All positive scan Word cases conserve decoded original RGB pixels
and scan media hashes; column DOCX/XML content remains unchanged.

Recovered monospaced editable DOCX and scan DOCX XML both contain all 259
characters. Actual Office export has **5.41% extraction CER**, 97.30% aligned
recall: PDF reading order groups some word frames separately. This is distinct
from 0% OCR/XML CER. Reopened PDF contains all numeric runs in expected order.
Original decoded RGB scan pixels are exactly preserved, scan media hashes match
before/after, paper RGB remains `(92,92,92)`, and unrecognized red ink reopens as
`(200,20,21)` versus source `(200,20,20)`. Independent shaded cases additionally
assert all original RGB pixels and unrecognized red probes after Office reopening.

## Coverage false positives

Seven controls include a real NASA astronaut photograph, the photograph on
shaded paper, geometric logo, three red marks, border, and dense/sparse halftones.
The photo is from scikit-image **v0.19.3**, whose
[official dataset documentation](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut)
identifies the NASA image as public domain. Only generator/provenance is
committed; photo/generated files remain ignored. Download SHA-256:
`88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5`.

Photo, embedded photo, logo, border and dense halftone do not trigger the probe.
Three red marks and sparse halftone **do trigger it**: two probe false positives
out of seven controls. In all seven actual bundled original/enhanced runs, word
count is zero and no replacement is accepted. These empty originals already
enter the existing retry, so new eligibility adds no extra retry to these specific
controls. This does not prove immunity to hallucinated high-confidence text on
other graphics. Eligibility and accepted replacement are separate evidence.

## Runtime, artifact identity and cost

Temurin **17.0.16+8**, Maven **3.9.11**, Node **22.17.0**; bundled Tesseract
**5.5.2**, Leptonica **1.87.0**, libpng **1.6.57**, zlib **1.3.1**;
LibreOfficeDev **26.8.0.0.alpha0**, commit
`2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`; Poppler **26.05.0**;
Pillow **12.3.0**, fontTools **4.61.1**, PyMuPDF **1.26.6**.
Exact font versions/hashes match [iteration 2](cloud-ocr-iteration2.md):
Liberation 2.1.5, Droid 2.55b, Noto Sans CJK 2.004 and Noto Serif CJK 2.003.
Droid is Apache-2.0; others are SIL-OFL-1.1. No new production dependency.

Runtime source/model/license verification and actual bilingual smoke passed
again. Binary SHA-256:
`90730922627269352568d54e788c7e42fd2e1783e03795d75560efa385de1489`;
policy SHA-256:
`54c6ae7291fc983cb7b946bc0b90832ec4b589d493fef01b5f974a0ab26d8735`.
Models use tessdata_fast revision `87416418657359cb625c412a48b6e1d6d41c29bd`:
eng `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`,
chi_sim `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730`.
API reports bundled chi_sim+eng, 120-second pages, concurrency one, 25M pixels.

Clean candidate JAR SHA-256:
`65403378cd3f475e86d2e3d95b348222062a5e6116d31a127d1d41a2047470e8`.
Build-input fingerprint:
`9fd9510129c2ab1b92edc34ad02ae96700ffcd1af1305b2d3ac50d5fc69b83a3`.
All **220** packaged application classes match fresh clean target classes;
aggregate SHA-256:
`7820c9e6dd622aa280b8d6c9bfaaef6b36b4c266c95aa397911deefca219ba6e`.
`record_cloud_provenance.py` verifies unchanged main sources/resources/POMs across
the clean build, then verifies them against the delivered Git revision. It does
not label an arbitrary old `--jar` with current HEAD. HTTP reports include JAR
identity and supplied provenance. Inherited health version remains 0.1.4 despite
JAR filename 0.1.5; hashes identify the tested candidate precisely.

Recovered monospaced API times: TXT **1.660 → 2.271 s**, image DOCX **2.262 →
3.698 s**, candidate scan DOCX **4.729 s**. These are single runs with occasional
other QA on the host, not a speed benchmark. Independent shading child CPU
user/system totals: **289.70/20.09 → 287.40/19.72 s**, largest-child RSS
**339160 → 345124 KiB**; columns **271.44/22.83 → 256.11/17.42 s**, RSS
**352796 → 352404 KiB**. RSS is the largest child, not summed process memory.
No resource reduction is claimed.

Frozen holdout child CPU user/system totals **744.03/52.15 → 692.17/45.02 s**,
largest-child RSS **381744 → 355804 KiB**; candidate handoff **274.62/18.30 s**,
RSS **335348 KiB**. These observations share the same timing limitations.

## Validation and reproduction

### Task deadline follow-up

The quality batch is commit `087d6c8c692b1454f4371a71898ec022f47640e7`.
Its [CI run](https://github.com/wmforever/fuyue-convert/actions/runs/37112671768)
passed OCR and Office integration but exposed an existing task-deadline race:
`multiFileDeadlineKeepsStableConversionTimeoutCode` saw two file results despite
only one converter call. Millisecond-truncated `Future.get` can time out just
before the absolute `Instant` deadline, admitting an unattempted second-file
timeout result.

The follow-up marks service-owned deadline exhaustion with a private
`TimeoutException` subtype and stops the batch after recording the current file.
It retains `CONVERSION_TIMEOUT`, timeout durations, cleanup and interruption.
An independent converter-local timeout still permits the next file when budget
remains. The new deterministic local-timeout regression and all **45** task-service
lifecycle tests pass; the original deadline assertion is unchanged. OCR, word
geometry, source pixels and reading-order classes are unchanged from 087.

The 40-case matrix and JAR hashes above identify the quality batch precisely.
The follow-up clean build has a separate `iteration3-deadline-provenance.json`
record and reruns six unchanged representative HTTP inputs. Latest full-suite,
artifact identity and exact-head CI evidence are recorded in the draft PR; an
old quality-matrix JAR is not relabelled as the follow-up artifact.

Final clean Maven: **384 tests, 383 passed, zero failures/errors, one optional
real signed-OFD skip**. New coverage/order regressions pass alongside sparse ink,
numeric/conflict, original coordinates, blank pages, masking, dense/pixel limits,
timeouts and cross-page tests. All nine deskew and four actual Office tests execute.
Frontend **20**, desktop **69**, desktop script checks, Python compilation,
privacy gate and `git diff --check` pass.

```bash
source /workspace/fuyue-env/activate.sh
export FORMAT_CONVERTER_APP_HOME="$PWD/desktop/.runtime"
python3 qa-samples/generate_ocr_shadow_cases.py
python3 qa-samples/generate_ocr_column_cases.py
python3 qa-samples/record_cloud_provenance.py --record qa-samples/work/build.json --before
python3 /workspace/fuyue-env/reap-run.py mvn -B -ntp -Dskip.frontend=true clean test
python3 /workspace/fuyue-env/reap-run.py mvn -B -ntp -Dskip.frontend=true -DskipTests package
python3 qa-samples/record_cloud_provenance.py --record qa-samples/work/build.json --after
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-holdouts \
  --out qa-samples/report/acceptance --provenance qa-samples/work/build.json \
  --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/record_cloud_provenance.py --record qa-samples/work/build.json \
  --verify-revision YOUR_COMMITTED_SHA
```

Generate unchanged 19 holdouts and six handoff samples with their existing
generators/font paths in iteration 2. Repeat HTTP for independent shading,
independent columns and handoff. Baseline uses preserved e06 JAR SHA-256
`83c44ce2b84d9a3bef810f9e5cc1ec641075b8b49271d2c21efba98db48ef8d2`.
Reports are ignored `qa-samples/report/iteration3-*`; diagnostics force the pinned
bundled runtime and never consult truth. Native Windows/macOS installers,
Microsoft Word, private corpus and real signed OFD remain unrun. Cloud dev Office
does not accept native release Office or stale installers. No rejected Word
rotation approach was retried/enabled; Word/PDF/OFD deskew remains off. No release
or merge.
