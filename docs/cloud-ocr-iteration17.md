# OFD retains each image's OCR warnings

OFD used a three-argument page `RecognitionResult` after recognizing individual
scans. That correctly calculated the word-weighted confidence, but discarded
image enhancement, possible omission, conflict and partial-recovery semantics.
A low-confidence scan could also be hidden by the page average. Start revision
`2fe947d156b34339735de3078dd047ec665e8267`; [fixed plan](cloud-ocr-iteration17-plan.json)
was recorded before new measurements.

Two font-independent controlled regressions fail before the production change:
missing enhancement warning and missing image-scoped low-confidence warning.
The text/numeric and weighted-confidence assertions pass before the missing
warning failure. Original-scan/offset-coordinate assertions later in each test
are reached after the correction; the independent paired artifact comparison
also verifies unchanged geometry. The correction retains one page `OCR_APPLIED` weighted summary and
adds the existing supplemental warnings with `OFD 第 N 页图片 M` scope. It does
not OR image flags onto the page result. Thus a partial-recovery warning retains
its own image's wording; it does not claim another scan was partially recovered.
No OCR selection, confidence, coordinate, image, masking, process or timeout
implementation changed.

## Measured acceptance

- Six focused OFD tests pass with no skip/failure/error, including both new
  regressions. A .60 image alongside a .97 image has page confidence .785, yet
  correctly retains its own .60 low-confidence warning. A .90 enhanced incomplete
  image alongside a .97 covered image has page confidence .935 and only image1
  receives enhancement/omission warnings. Source bytes and original offset boxes
  survive.
- Fresh `mvn -B -ntp -Dskip.frontend=true clean package`: **419 tests, 418 pass,
  one optional real signed-OFD fixture skip**, zero failure/error. Actual bundled
  and Office tests execute; frontend output is unchanged. UI20/desktop69 suites
  were not rerun for this Java-only change.
- **26 paired authenticated HTTP contracts /26 observed independent production
  JVM Workers**, thirteen per artifact: incomplete single-image TXT/DOCX,
  complete single-image TXT, mixed same-page TXT/DOCX, bundled bilingual numeric
  same-page TXT/DOCX; each DOCX also reopened through actual Office PDF→TXT.
  All succeeded. Controlled response confidence is injected, not native accuracy.
- Old OFD responses have only `OCR_APPLIED`. New incomplete responses add one
  image enhancement and one omission warning; complete adds only enhancement;
  mixed adds two enhancements and one omission warning for image1. The one
  weighted page warning is exact. Bundled/native and Office conversion warnings
  remain exact.
- All downloaded text, numeric sequences, file/page success results and original
  media hashes match. Three Word pairs retain respectively **23/69/118 editable
  frames** and **23/69/107 masks**; every frame/style/order, mask, original decoded
  RGB scan pixel matches. Actual Office page counts, word boxes/order and rendered
  pixels match. This fixes warning correctness, not recognition or editability.

|Frozen OFD case|Before/after CER|Before/after aligned recall|
|---|---:|---:|
|Controlled incomplete four of eight lines|48.846%|51.154%|
|Controlled complete eight lines|0.000%|100.000%|
|Same page incomplete + complete|24.423%|75.577%|
|Bundled EN/ZH upright numeric control|0.767%|99.233%|

Every TXT/DOCX metric pair is equal, including absent numeric tokens in the
incomplete scan. A correct warning does not recover those missing numbers.

|13-contract set|Before|After|
|---|---:|---:|
|Wall seconds, including server/worker/Office startup|63.849|67.716|
|Child CPU user + system seconds|169.394 +9.358|181.667 +9.722|
|Maximum child RSS KiB|358860|339404|

Single passes with JIT/cache/load variation; RSS is a child maximum, not summed
concurrent memory. No speed/resource improvement claim. OCR concurrency one,
page deadline120s, whole suite480s. No additional OCR operation is introduced.
Only `OfdOcrSupport.class` changes; other222 packaged application classes match
the accepted baseline. All223 fresh packaged classes match clean targets.
New JAR SHA-256
`70cb1d5ab5bda5756116a61cfc14480e6aa2c98a9bc33fca744028af05468b0e`,
build-input fingerprint
`d7917249f93721db6dfa63af3e2e51589a2114ec2eb25a336dc30114647d7f6a`.

[Full paired receipts](cloud-ocr-iteration17-results.json) contain task warnings,
text/truth metrics, hashes, workers, costs, class diff and skipped-test reason.
Exact final Git revision/JAR provenance and CI are recorded in the draft PR and
review packet, without a circular self-referencing report commit.

## Reproduction and limits

See [fresh-checkout fixture dependencies](cloud-ocr-controlled-reproduction.md).
Iteration11/15 manifests were independently reconstructed byte-for-byte from a
fresh source export with pinned fonts and explicitly cached native TSVs. Reports
alone are not self-contained. The OFD fixture generator wraps frozen original
PNGs; regenerated OFD ZIP metadata can differ. Compare pixels, geometry and truth.
The current runner imports checked-in `qa-samples/qa_process_guard.py`; Linux
subreaper and Python pidfd support are required. No private workspace helper is
needed. Its teardown correction and fresh-export execution are validated in
[iteration18](cloud-ocr-iteration18.md); the old iteration17 matrix was not rerun.
Run `run_ocr_iteration17_http.py --jar <artifact> --out <fresh-directory> --scope
ofd` with bundled app-home/explicit Office path, then
`summarize_ocr_iteration17.py` for the fixed before/after paths. Separate raw logs
and original artifacts remain in ignored QA directories and review packet.

Pinned Temurin17.0.16+8, Maven3.9.11, Node22.17.0; bundled Tesseract5.5.2,
Leptonica1.87.0, libpng1.6.57, zlib1.3.1, tessdata_fast
87416418657359cb625c412a48b6e1d6d41c29bd; Liberation2.1.5,
NotoSansCJK2.004/NotoSerifCJK2.003; Pillow12.3.0/NumPy2.3.5. Full hashes and
font SIL-OFL-1.1 provenance are included in the receipts. Actual explicit
LibreOfficeDev26.8.0.0.alpha0 commit
2c87e51eeaa2b413ff4ae097b2705eea1995d8e5 is unchanged. System OCR CI is separate
from this bundled acceptance.

No PSM/angle/threshold sweep or completed cleanup case was repeated. The bounded
[cleanup audit](cloud-ocr-iteration16.md) is negative for product faults, with
the harness failure and ten residual PID1 zombies explicitly retained. Iteration17
servers exit via their live subreaper and add no residual processes.

End-to-end OFD partial/conflict injection was unrun in this iteration and is
subsequently covered by [iteration18](cloud-ocr-iteration18.md). Arbitrary
signed/vendor OFDs, native desktop packages/Microsoft Word and detached-child
cleanup races remain unrun.
Coverage false positives/negatives, deadline skips, low-confidence rejected,
deskew/vertical coverage gaps, EN−6° accuracy and true-tilted suppression risk
remain. Existing installers are stale; Linux JAR acceptance is distinct from
native macOS/Windows acceptance. No merge/release/Mac/Library403 retry.
