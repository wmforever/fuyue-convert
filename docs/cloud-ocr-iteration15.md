# Iteration15: retain omission warnings after accepted enhancement

修复一个已独立复现的警告缺陷：完整增强候选被采用后，若最终词框仍留下多个未覆盖的阴影区域，原实现会清除遗漏标志。现在在原页剩余时限内重查原图与最终词框，返回准确的“已采用候选但可能仍有遗漏”提示。**文字、数字、坐标、采用门禁和 OCR 准确率均未改变。**

## Bounded hypothesis and baseline

Start `038f1f163aa8bef0913db3581d844e5056f67131`. The
[initial fixed plan](cloud-ocr-iteration15-initial-plan.json) froze before new
measurement; SHA-256
`db1d5ef64c29ac3086fe156eadd7ff43bc4806f6e8b20c6e9cc4875988a42b34`
matches every original38-contract HTTP report. The
[controlled corpus](cloud-ocr-iteration15-controlled-corpus.json) manifest is
`37f6558bbb42ed4446fca4f26503810978e107ff2cc487a6753f64d9852c77eb`.
It copies the frozen EN-serif8-line shadow and derives native TSV responses
by keeping bottom2 original lines, then bottom4/all8 candidate lines. Injected
confidence fields are .60/.90/.62 to exercise accepted incomplete, accepted
complete and rejected-candidate branches. This is **controlled engine-contract
evidence, not measured bundled OCR confidence or accuracy**. Its detection-only
version response advertises5.5.2; the script's provenance is explicit.

Actual unchanged Java converter baseline adopts the .90 full four-line
candidate, and an independent actual coverage probe on selected words returns
true, but `possibleTextOmission=false`. The complete eight-line candidate has
probe=false. The rejected .62 candidate remains original. The same defect is
reproduced by authenticated real HTTP upload→separate observed production JVM
Worker→download for both TXT and DOCX: adopted incomplete candidate gets
`OCR_APPLIED`/`OCR_IMAGE_ENHANCED`, no omission warning. The new regression
fails on old production code exactly at the missing flag (one failure, no error).
All failure stdout/stderr remains local evidence.

## Smallest correction and retained boundaries

After the existing final original-pixel geometry refinement, accepted full
enhancement may invoke the unchanged coverage probe against those final boxes
and original pixels. It only runs for horizontal PSM3, non-partial/non-deskew
results and more than one second remaining. It uses the **existing original
page deadline**, does not reset time or launch another OCR process. Complete
coverage produces no warning; unresolved evidence produces the existing
`OCR_POSSIBLE_TEXT_OMISSION` code, with wording acknowledging that the full
enhanced candidate was actually adopted. Partial recovery wording and its
early return are unchanged. Original/rejected-result behavior, vertical/deskew
paths, min/gain/length/reliable-numeric selection and all resource caps remain.

Three new tests verify incomplete adoption, covered adoption and a nearly
exhausted5s page budget. An existing test's assumption that any adoption clears
coverage is corrected; both its word sets actually leave shaded ink bands.
Source pixels remain exact. Coverage is conservative evidence: graphics can
trigger it, coarse boxes can conceal real omissions, and a skipped/false probe
is not a completeness certificate. Low-confidence rejected results and
deskew-specific warning coverage are outside this correction.

## Validation and actual artifacts

- Targeted five-class suite: **59 pass**, no skip/failure/error, including all
  three new tests, numeric/partial/deadline/deskew/fragment and bundled cases.
- Clean `mvn -B -ntp -Dskip.frontend=true clean package`: **417 total,
  416 pass, one optional real signed-OFD fixture skip**, no failure/error.
  Bundled-runtime and actual Office tests execute. Existing frontend output is
  unchanged; UI/desktop20/69 suites are not rerun for this Java-only correction.
- Three before/after actual Java controlled results match in every selected
  field except the incomplete case's warning flag. The complete and rejected
  flags remain false. No words/digits/confidence/boxes are replaced by this fix.
- **38 initial paired HTTP contracts**: four controlled plus15 bundled/unaffected
  per JAR. The native set includes six handoff TXT inputs, EN-serif TXT,
  EN-mono DOCX→Office PDF→TXT, blank/noise stable failures and
  TXT→DOCX→Office PDF→TXT with Chinese/numeric/date/amount text. Every contract
  observes its separate production worker. Text/metrics/numbers/error codes and
  source frames match exactly; incomplete TXT/DOCX each add one omission warning.
- Initial direct image DOCX artifacts have **zero masks**. Before additional
  execution, the [bounded extension](cloud-ocr-iteration15-plan.json) selects
  only controlled incomplete and bundled EN-mono inputs for
  PNG→PDF→scanned DOCX→actual Office PDF→TXT, old/new JARs: **16 additional
  contracts**, no old run repeated. Controlled scan: **23 editable frames/23
  masks**; bundled scan: **46 frames/85 masks**. All frame/style/rank signatures,
  masks/media hashes and original decoded RGB pixels match exactly; Office
  reopening has identical page count, every word/bbox/order and rendered pixels.
  Controlled scan adds the third correct omission warning, bundled complete
  scan warnings stay identical. No scan content or editability gain is claimed.

Total **54 paired HTTP contracts/54 observed independent workers**, including
four expected blank/noise failures. Success/failure behavior is unchanged.
Fresh PDF scan/mask and unaffected-route checks do not substitute for arbitrary
OFD/multipage/Word-edit or native-package acceptance. Existing broader97 HTTP/
28 Word evidence remains tied to its prior exact artifact; it is not presented
as fresh acceptance of this new JAR.

Native CER and aligned completeness are unchanged, respectively:

|Frozen handoff input|Before/after CER|Before/after aligned recall|
|---|---:|---:|
|EN0°|0.000%|100.000%|
|EN−6°|27.986%|86.007%|
|EN+6°|0.341%|99.659%|
|ZH0°|3.061%|96.939%|
|ZH−6°|1.020%|98.980%|
|ZH+6°|1.020%|98.980%|

Controlled incomplete selected CER48.846%/recall51.154% also stays unchanged;
the warning accurately describes remaining risk without recovering any text.
EN−6° and high-confidence omissions remain unresolved. No new angle,
threshold/PSM sweep or confidence tuning; iteration14's negative PSM6 decision
stands.

## Runtime, resource costs and source binding

Pinned Temurin17.0.16+8/Maven3.9.11/Node22.17.0; bundled
Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1, tessdata_fast
87416418657359cb625c412a48b6e1d6d41c29bd; Liberation2.1.5,
NotoSansCJK2.004/NotoSerifCJK2.003. Exact prior binary/model/font hashes/licenses
remain in iteration14/11 manifests; none changes. Cloud LibreOfficeDev
26.8.0.0.alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5` is independently
checked. Current shell PATH lacked its alias; explicit
`/opt/codex/runtimes/codex-primary-runtime/dependencies/bin/override/soffice`
restores the existing runtime without installing/replacing it.

|HTTP set|Before/after wall s|Before/after CPU user+system s|Before/after peak child RSS KiB|
|---|---:|---:|---:|
|4 controlled|24.477 /23.328|68.877+4.239 /67.205+3.468|348420 /308992|
|15 bundled/unaffected|39.828 /40.299|96.919+6.033 /101.136+5.556|306940 /320672|
|4 controlled scan|15.139 /16.001|41.547+2.120 /44.596+2.278|385004 /386000|
|4 bundled scan|17.175 /16.835|44.685+2.278 /43.134+2.474|384000 /385604|

One pass per set, includes startup/worker/Office costs; maximum RSS is a child
maximum, not concurrent memory sum. Differences include JIT/cache/load variance,
so **no speed improvement is claimed**. All requests stay within original120s
limits; the optional coverage probe never acquires a new deadline. The probe's
existing700px thumbnail/tile/2m box-visit bounds are unchanged. Separate raw logs,
downloaded artifacts, full selected coordinates and resource receipts are retained.

New JAR SHA-256
`718eeb8181bcd31d4a86dc41954736e4ee2c4ade73b328724687e502cbdc62ed`;
build-input fingerprint
`4e8f5422912b47c36f528fe81b0bf3e64d29c5b4226a79d10740cdff5a4bcb1c`.
All223 packaged classes equal clean targets; eight byte-changed classes are
TesseractOcrConverter and its nested classes (including debug source positions);
other215 application classes, renderers/masks/models/selection/process cleanup
classes remain byte-identical. Exact final commit/source/JAR binding and CI
are recorded separately in the draft PR.

[Full before/after proof](cloud-ocr-iteration15-results.json) includes every
controlled result, HTTP warning/text/artifact/metric/resource record and class
diff. Reproduce generator/probe, failing-before test, focused/clean commands and
`run_ocr_iteration15_http.py` in fresh ignored output directories; runner refuses
overwrites. `summarize_ocr_iteration15.py` verifies the paired artifacts. Controlled
engine scripts are explicitly synthetic and not bundled-runtime acceptance.

Remaining limits: conservative coverage false positives/negatives, nearly
exhausted budget skips, low-confidence rejected/deskew/vertical warning gaps,
original accuracy regressions, true-tilted qualifying suppression risk,
handwriting/arbitrary complex layouts/multipage scan edits remain. Native
macOS/Windows packages/Microsoft Word unrun; existing installers are stale.
No merge/release/Mac/Library403 retry.
