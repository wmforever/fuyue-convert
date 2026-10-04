# Iteration14: finite enhanced PSM6 candidate diagnostic

结论：**不采用 PSM6，不修改生产实现**。两页未解决阴影虽得到更完整的候选文字，仍不满足现有实际采用门禁；有线表格的 PSM6 候选为空。保留生产 PSM3、原数字/坐标、原时限及回退。

## Frozen hypothesis and execution

Starting source `54adeb5233b32d5f7e0b1ccddf2332e64f7add46`. Before any new OCR,
[the fixed plan](cloud-ocr-iteration14-plan.json) declared10 cases, seed140042026,
at most18 serial CLI runs,120s per command and1200s whole-diagnostic limits,
1GiB CLI virtual-address-space bound and262144KiB observed RSS adoption ceiling.
[Frozen corpus](cloud-ocr-iteration14-corpus.json) SHA-256
`b43e017db62d42111f16a638bf6a36e9bfbc749b33524426c7034340062afeb7`. Six existing images/truth remain byte-identical;
four numeric/date/amount/column/table controls derive from frozen iteration11
pixels using a vertical145→245 paper gradient, no resampling/cropping/rotation.
No random search, truth from OCR, old53 threshold runs or angle sweep is used.
Unruled column/table intent remains non-identifiable from pixels alone.

`python3 qa-samples/generate_ocr_iteration14.py` freezes inputs; compile
`OcrPsmCandidateProbe.java` against accepted target classes in a separate ignored
QA directory; `python3 qa-samples/run_ocr_iteration14.py` runs only missing variants;
`python3 qa-samples/summarize_ocr_iteration14.py` audits hashes and outcomes.
The runner refuses existing output to prevent accidental repeats/overwrites.
Replay requires a fresh ignored output directory; existing records should be
read first. The actual commands, source hashes and every word/line confidence,
text, bbox, style, transform and physical page bounds are in
[full results](cloud-ocr-iteration14-results.json).

## Actual acceptance and measurement

The diagnostic calls unchanged production `preferEnhanced` and
`OcrPartialRecovery.select`, not a truth/confidence ranking. The full path retains
.35 minimum, .05 gain,90% alphanumeric content, reliable-word order and exact
numeric surfaces/boundaries. The partial path retains every existing deadline,
block/word/product/conflict cap, digit requirement, in-bounds geometry,
single-row/column-gap/height/spacing rules, unique ordered anchors, separated
new-row letters/minimum/gain checks, source block identity and honest mixed
confidence. Original geometry refinement stability remains required for partial
adoption. Raw full/partial decisions and separate measured prerequisites are
reported; no unfired branch is described as having passed all its checks.

Production eligibility is applied: empty/low-confidence originals, or shaded
uncovered ink with confidence≤.95. Only an original retained after PSM3 could
hypothetically consider PSM6. Already accepted PSM3 remains first. Full raw
selector decisions are distinguished from actual eligibility. Candidate bounds
are additionally audited; this is not a new production full-selector gate.
The enhancer is the actual accepted Java implementation, unchanged dimensions;
its temporary PNG has no DPI, matching production. Old no-DPI enhanced pixels
match exactly. Source mapping is300DPI:2400×1500→203.2×127mm, no transform.

Counterfactual remaining120s sums include original/enhanced PSM3/PSM6 native
times and enhancement, with that remainder passed to actual partial selection.
These sums mix cached and new runs and exclude worker/service/parsing overhead;
they **do not establish production deadline acceptance**. A positive candidate
would still require actual production HTTP/worker/artifact validation. This
negative result has no implementation to accept.

## Results against frozen truth

CER ignores whitespace/case, retains punctuation/digits. Recall uses the same
edit-distance alignment, reported alongside exact lines/text and number lists.
Candidate metrics assess the hypothesis after selection; never select from truth.

|Case|Original PSM3 CER|Enhanced PSM3 CER|Enhanced PSM6 CER|Conditional selected CER|Actual conditional decision|
|---|---:|---:|---:|---:|---|
|en-serif-shadow.png|48.846%|0.000%|0.000%|48.846%|original|
|zh-sans-shadow.png|55.422%|0.602%|0.602%|55.422%|original|
|en-mono-shadow.png|100.000%|0.000%|0.385%|0.000%|existing-psm3-full|
|zh-serif-shadow.png|100.000%|0.000%|0.000%|0.000%|existing-psm3-full|
|blank-shadow.png|0.000%|unrun|unrun|0.000%|original|
|noise-shadow.png|0.000%|0.000%|0.000%|0.000%|original|
|en-numeric-upright-mild-shadow.png|48.846%|0.000%|0.000%|48.846%|original|
|zh-numeric-upright-mild-shadow.png|55.422%|0.602%|0.602%|55.422%|original|
|en-columns-aligned-mild-shadow.png|54.386%|47.807%|19.737%|54.386%|original|
|en-ruled-mild-shadow.png|50.000%|59.211%|100.000%|50.000%|original|

EN serif original confidence0.921480785 requires0.971480785. PSM6 gives
0.949247251 (PSM3:0.948468341); both actual full/partial selectors reject.
Four new PSM6 rows average0.920150466/0.919013428/0.945536566/0.965450515:
all fail unchanged row gain. The candidate would recover127 aligned truth
characters if forced, but **adopts zero**; selected CER remains48.846%.

ZH sans original0.926264338 requires0.976264338. PSM6 gives0.906748432
(PSM3:0.920873927); both reject. Four new row averages are
0.927345253/0.926648196/0.790997795/0.862438889, all fail gain;
the third also contains a word below.35. Candidate `6.759%` is wrong against
truth `6.75%`, despite only.602% CER. Candidate would recover92 aligned truth
characters if forced, but adopts zero; selected CER remains55.422%.

The reliable numeric surface audit passes on both shadow candidates for digits
already present in the original. It cannot certify missing/new numbers. The
separate reliable-normalized-word boundary/order audit is false; actual full
selection already exits at gain, so this is an additional measured prerequisite,
not a reached rejection cause. Confidence is not completeness.

The empty-original EN mono case already recovers with PSM3 at0% CER; its PSM6
candidate has one error (.385%), so replacing an accepted retry would regress.
ZH serif recovery remains0%. Blank enhancement returns null: both candidates
unrun. Noise yields empty candidates and stays empty. Mild EN numeric original
confidence0.956919 exceeds the retained.95 shaded-retry ceiling despite missing
half the text. ZH numeric remains rejected by gain. Multi-column PSM6 has
19.737% CER versus47.807% enhanced PSM3, but lacks acceptable gain and is not
production eligible. Ruled-table PSM6 is empty (100% CER) and safely rejected.
Every conditional selected result, including complete block/word coordinates,
equals existing selection exactly across all10 controls. This establishes
diagnostic fallback parity, not new OCR/editability quality.

## Resources, logs and unchanged acceptance

New CLI: **17/18**, no failure/timeout;11 completed original/enhanced PSM3
records reused (six+five). New CLI summed wall **9.856s**,
CPU **8.471 user+1.170 system s**,
peak **107740KiB**, max command **0.775s**;
whole diagnostic **29.334s** including20 Java
prepare/evaluate invocations (10+10; enhancement-null still evaluates).
Java preparation/evaluation CPU and peak RSS are unmeasured; native CLI costs
do not represent the complete service. Timings are one cloud pass, not a benchmark.
New run directories preserve
separate stdout/stderr/TSV/commandJSON, including timeout/failure receipts if any;
all17 TSV and log hashes are audited. Cached older combined logs remain unchanged.
No compile/run failure occurred here; prior failures are preserved elsewhere.

Pinned Temurin17.0.16+8, Maven3.9.11, Node22.17.0; bundled Tesseract5.5.2,
Leptonica1.87.0, libpng1.6.57/zlib1.3.1; tessdata_fast
87416418657359cb625c412a48b6e1d6d41c29bd. Liberation2.1.5,
NotoSansCJK2.004/NotoSerifCJK2.003 font hashes/versions/SIL-OFL-1.1 and
binary/model/source hashes are in the corpus/results. Model eng/chi_sim and
enhancement policy remain fixed; no component/dependency added.

Production inputs,223 application classes and accepted JAR remain unchanged:
`1c3658c86536b0d06a76f583fdc39a6b68bfce77fb05b8f48e3c685e4ab7c112`. Therefore prior413-pass/one optional signed-OFD skip,
97 HTTP and28 Word artifact acceptance are preserved evidence for their exact
production revision, not fresh runs in this batch. No39-focused/53-threshold
or old artifact matrix rerun. No new API/Word/editability acceptance claim.
System OCR CI is separate from bundled CLI evidence. Native macOS/Windows
packages/Microsoft Word remain unrun; stale installers are not updated.

**Finite diagnostic stopped; no PSM6 retry/global switch/threshold loosening.**
High-confidence shadows, unreliable new numbers, known deskew regressions and
true-tilted qualifying risk remain unresolved. No merge/release/Mac/Library403 retry.
