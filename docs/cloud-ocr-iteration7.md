# Cloud iteration 7: bounded partial OCR recovery

The safety head `2866c6126e6f74788233219c56aba5d4044a8b4f` correctly rejects numeric
reinterpretation, but can discard recoverable text elsewhere on the page. This
batch adds a narrow fallback after complete-candidate rejection. It preserves
**every original TextBlock object, exact line text/separators, word geometry,
style and rank**, and inserts only clearly separated candidate lines. It never
turns source `20`/`26` into `2026`, or source `-17` into a date using fixture truth.
Original recognition errors remain errors. Routes remain experimental.

## Adoption boundary

Candidate lines must form a vertically separated single-column sequence. Both
global and per-line gutters reject ambiguous tables/columns, including a table
gutter hidden by spanning prose. Original lines need unique, monotone candidate
anchors; many-to-one/spanning/inflated boxes, overlap, close boundaries, missing
originals and nonmonotone ranks reject recovery. New lines have at least six
letters and satisfy word confidence floors. Blank/empty input is not admitted.
Bounds remain500 lines/5000 words per input and output, two-million matching
comparisons, original pixels/concurrency limits, and the shared page deadline.
No extra recognition pass or dependency is introduced.

The enhancement candidate and **each added line** retain the five-point confidence
gate; deskew retains its existing candidate tolerance. Mixed confidence includes
all preserved original words and may improve by less than five points. It must
remain above the minimum and cannot decrease for enhancement. This is a mixed
output policy, not a claim that the old whole-output gain threshold is met.
Warnings describe that distinction and unresolved omissions explicitly (issue #6).

An explicit internal `partialRecovery` state prevents subsequent deskew or word
refinement from mutating preserved regions. If normal fallback would refine an
original Chinese word box, partial adoption is rejected, preserving its previous
API/Word geometry. Already accepted complete candidates are unchanged.

New blocks use unique IDs and ordering ranks that fit between unchanged originals.
The first Word run exposed a real ordering defect: inherited candidate ranks put
one new line after an original line in XML, giving70.27% XML CER although TXT/PDF
were68.34%. A failing regression reproduced it; assigning only new ranks fixed
the final Word order. Original objects/ranks were not renumbered.

## Exact final artifact and measured results

Final JAR SHA-256:
`0488a94672cafdc94780dd1a2970b146ff1d555e2591bf061e933d72824f4c92`.
Build-input fingerprint:
`e786c212642003ee034bc31c4cf12cd015040efd05966c8df5e29cde23cfb404`.
All222 packaged application classes match fresh targets; aggregate class hash
`033b8c9805e3d72fcfe61faeb880df27e18c4816f3e5a4775cf24f70bf0ee149`.
The clean build began with modified inputs at parent2866c61; final commit binding
independently verifies those exact inputs/JAR bytes. The PR head and packaged
`verifiedCodeRevision` identify that revision. Interim `7350aebc…` reports are
diagnostic only and are **not** attributed to the final source.

Fresh final production HTTP→independent JVM→download matrix: **20/20 operational
contracts,109 workers**;17 positive inputs reopened through actual LibreOffice,
three blank/noise/isolated-mark inputs returned their expected errors. The exact
same frozen source hashes/truths are compared with prior accuracy head11f6ce7 and
safety head2866c61. Six independent mono/serif/CJK holdouts and negative controls
were freshly rerun on the saved safety JAR. No truth is derived from OCR output.

| Measure: severe monospaced shade | Accuracy head | Safety head | Final |
| --- | --- | --- | --- |
| TXT truth CER | 0% | 90.73% | **68.34%** |
| TXT characters /259 | 259 | 24 | **82** |
| Editable scan DOCX XML CER | 0% | 90.73% | **68.34%** |
| Actual Office scan PDF extraction CER | 5.41% | 90.73% | **68.34%** |

Exactly six original lines remain; only `Account …` and `Final subtotal …` lines
are added. Original `-17`, `L 37.50` and the other fragments are retained, not
reconstructed. Aligned character recall improves9.27%→31.66%; **most editable
content remains missing**. Mixed confidence88.93%→92.62% does not prove completeness.
Source scans retain their pixels independently of editable recognition.

All other19 case outcomes/metrics are unchanged from the safety head. English−6°
remains27.99% CER, still regressed from the earlier0%; English+6° remains0.34% with
the intentional80424/80421 conflict warning; Chinese−6°/+6° remain1.02% and upright
Chinese3.06%. Ordinary English/Chinese two-column TXT remains0%/1.35%. Independent
vertical mono/serif/CJK holdouts remain46.59%/46.59%/51.49%; uniform controls remain
0%/0%/7.92%. They establish no additional recovery gain.

Fresh final nine-case TXT-only three/four-column matrix is unchanged from safety
head and still worse than the old partial reorder in all nine. Each returns the
uncertainty warning. This fallback remains a scope/safety safeguard, not a general
reading-order improvement or Word acceptance claim.

[Machine comparisons](cloud-ocr-iteration7-results-20261003.json) retain accuracy,
safety and final metrics, warnings, numbers, hashes, identities, times and costs.

## Original-region and editability evidence

Downloaded final scan Word preserves all **eight original editable word frames
and ten original masks exactly**, including geometry/style/z-order, with no loss
or duplication. Generated shape IDs are excluded from this comparison. Eighteen
new masks are disjoint from every old word frame and stay within added word frames
plus0.5pt antialias margin. Source media bytes are identical. Actual Office PDF
words `-17` and `37.50` retain exact glyph bounding boxes: measured shift **0pt**
(the verifier allows0.05pt rounding). This is evidence for this sample, not all
glyphs or documents; see [geometry record](cloud-ocr-iteration7-word-geometry.json).

The newly recovered `Account` word was edited to `Ledger` in that downloaded scan
DOCX and actually reopened by LibreOffice. PDF extraction contains Ledger and no
Account; original frames/masks/media/ranks and numeric glyph positions remain
unchanged. [Edit record](cloud-ocr-iteration7-word-edit.json) and
[edited geometry](cloud-ocr-iteration7-word-edit-geometry.json) document the check;
the edited DOCX/PDF are included in the cloud review bundle. The existing English
control edit also passed through the authenticated production Worker pipeline.
Other edit cases are unrun. Scan images still contain original pixels, even when
an editable overlay is changed.

## Tests, runtime and cost

Clean `mvn -B -ntp -Dskip.frontend=true clean package`: **403 tests,402 passed,
one optional real signed-OFD fixture skipped**, no failures/errors. Targeted
coverage includes decimals/signs/dates/IDs/currency/grouping, fullwidth separators,
neighbor changes, overlapping tables/columns, spanning/inflated/many-to-one boxes,
blank/minimum-confidence/timeout, hidden gutters and Word ordering. Fullwidth
line text is preserved rather than regenerated through the numeric-fusing join.
Preservation does not repair mistakes already present in original OCR/segmentation.

Actual bundled tests ran. Runtime remains Temurin17.0.16+8/Maven3.9.11/Node22.17.0,
Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1, pinned tessdata_fast
`87416418657359cb625c412a48b6e1d6d41c29bd`. Exact binary/model/policy hashes remain
those in [iteration6](cloud-ocr-iteration6.md) and the packaged runtime manifest.
LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5;
pdftotext25.03.0, PyMuPDF1.26.6, Pillow12.3.0, NumPy2.3.5, fontTools4.61.1.
Liberation2.1.5/Noto Sans CJK2.004/repository Droid font hashes and versions are
recorded in manifests. No runtime font/model changed. Frontend20/desktop69 were
previously checked; they were not rerun for this backend/QA batch. Exact-head CI
is reported separately and system OCR CI does not replace bundled acceptance.

Twenty-case child peak RSS365336KiB, CPU904.358 user+63.378 system seconds; summed
positive-route times362.881s. Nine TXT cases peak285548KiB, CPU81.784+6.414s.
These are whole-run child counters, not simultaneous process-tree peaks. Runs
overlapped, and historical matrices differ in size; this is not a speed benchmark.
The extra direct Office edit is outside those Worker resource counters.

## Reproduce and review

Generate existing licensed handoff/shading/holdout fixtures, then:

```bash
python3 qa-samples/prepare_ocr_review_cases.py --independent \
  --out qa-samples/generated/cloud-iteration7-contracts
source /workspace/fuyue-env/activate.sh
export FORMAT_CONVERTER_APP_HOME=/workspace/fuyue-convert/desktop/.runtime
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration7-contracts \
  --out qa-samples/report/iteration7-final-acceptance --reaper /workspace/fuyue-env/reap-run.py \
  --provenance qa-samples/work/iteration7-final-provenance.json
python3 qa-samples/verify_partial_word_preservation.py \
  --before qa-samples/report/iteration6-reviewed-contracts \
  --after qa-samples/report/iteration7-final-acceptance --case en-mono-shadow.png \
  --out docs/cloud-ocr-iteration7-word-geometry.json
```

The final ZIP contains exact committed source/JAR/runtime/licenses and final
twenty-case evidence plus separately labelled nine-case TXT evidence. A local
cloud ZIP is not Library delivery, a release or a native installer. Prior bundles
remain unchanged and represent prior behavior. Library network403 is not retried.
Windows/macOS packages, Microsoft Word, released native Office, private document
corpora/handwriting and arbitrary Word rotation remain unrun/unresolved. Rejected
Word line-frame prototypes are not adopted. No merge or release is performed.
