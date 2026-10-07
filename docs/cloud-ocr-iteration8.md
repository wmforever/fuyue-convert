# Cloud iteration 8: independent corpus and fragmented column TXT

The restored branch head was `37faa62a0aa536e9088b7067765dc5ebefee7070`.
Shell execution, clean checkout, remote identity `wmforever`, unchanged main,
tool/runtime hashes and eight fresh bundled-runtime/partial-recovery tests were
verified before work. This batch keeps every numeric, partial-recovery, masking,
deskew and timeout guard from that safety head. No rejected Word prototype is
adopted, no dependency/model changes, and no native installer is accepted.

## Frozen new evidence before algorithm edits

The [new corpus manifest](cloud-ocr-iteration8-corpus.json) uses seed `810032026`:
14 new image cases and eight lossless raster PDF/OFD wrappers. SHA-256:
`601446606c22484a02a65e51668796c675049633c66ece508e45b5c725d59b3a`.
Truth contains new synthetic dates, IDs, signed/leading decimals, money,
percentages, leading zeros and separated `12 34`, plus bilingual text, four
fonts, ±4° angles, horizontal/vertical shadows, three columns, ruled tables,
blank shade and isolated noise. Truth was defined before OCR evaluation and
algorithm changes, never copied from recognition. OFD random IDs were replaced
with source-derived deterministic IDs during fixture preparation; image hashes
and truths stayed unchanged. A fresh complete generation reproduces **all22
files and the manifest byte for byte**. Generated inputs stay outside Git.

Code/text are Apache-2.0 contributions. Liberation2.1.5, Noto Sans CJK2.004 and
Noto Serif CJK2.003 fonts use SIL-OFL-1.1; exact files/hashes/source URLs/versions
are in the manifest. PyMuPDF1.26.6, Pillow12.3.0, NumPy2.3.5 and existing
OFDRW2.3.9 produce wrappers at300DPI. No private clinical/business data or public
samples of uncertain license are used. No additional runtime dependency is added.

## Why shadow OCR remains unchanged

Both the current safety and earlier accuracy JARs were run on identical frozen
inputs through authenticated HTTP→independent JVM→download. All14 image/Word
and eight PDF/OFD baseline contracts completed, including actual Office reopen.
New horizontal mono shade has94.89% TXT CER, vertical serif48.54%, vertical
Chinese55.56%. Enhanced diagnostic CLI output contains more lines, but its
confidence does not meet the unchanged five-point gate: mono92.31%→94.21%,
serif92.13%→94.74%. The source numeric fragments and geometry are not replaced.
This is a measured blocked recovery, not permission to weaken protection or
infer completeness from confidence. These new failures also occur on the older
accuracy baseline; they are distinct from prior iteration7 safety regressions.

## Adopted narrow improvement

English three-column input has a different failure: Tesseract reads three short
fragments of each sentence down separate vertical strips. The new TXT-only
fallback reconstructs **whole immutable fragment strings**, inserting whitespace
between them. It does not re-run recognition, split/rewrite numbers, change any
source object/coordinate, or change Word/PDF/OFD. It is invoked after the existing
multiple-gutter detection; the prior two-column algorithm is unchanged.

Exactly three wide separated columns are required. Each column has at least
three rows; each row has three-to-six short fragments, each fragment at most two
engine words and at least three letters. Repeated left/right boundaries, narrow
within-row gaps, consistent heights/centers, generous row separation and at least
twelve letters per reconstructed row are required. Every source block is used
exactly once and character inventory must match. Numeric punctuation, signs,
internal whitespace and word boxes remain intact. Numeric-only table cells,
spanning lines, previously merged multi-column rows, already complete lines,
narrow gutters, overlapping/nearby rows, missing word inventory, four columns,
dense work and expired deadlines retain the prior fallback. Existing rejection
tests are unchanged. The500-block/5000-word bound and shared deadline remain.

This geometry does not establish reading intent for every text-only table:
regularly aligned prose cells can resemble column fragments. Such ambiguity is
a remaining review risk, not a claim that all tables are recognized or rejected.

Both adjusted-order and complex-order warnings remain; the latter now accurately
describes reconstruction versus engine-order fallback (issue#7). No route ID,
field, quality promotion or new warning code is introduced.

| New input TXT CER | Earlier accuracy | Safety | Final |
| --- | --- | --- | --- |
| English fragmented three columns |50.79%|50.79%|**4.76%**|
| Chinese complete three columns |6.94%|0%|**0%**|
| Mono horizontal shade |94.89%|94.89%|94.89%|
| Serif vertical shade |48.54%|48.54%|48.54%|
| Chinese vertical shade |55.56%|55.56%|55.56%|

English aligned character recall55.16%→95.24% reflects **ordering**, not new OCR
content. Recognized characters stay240/252; twelve missing row-ending digits
remain missing and zero exact truth lines are recovered. Every recognized
non-whitespace character is retained. This is not general three-column accuracy.
All other thirteen new image outcomes/TXT metrics are unchanged from safety.

## Final production and preservation evidence

The final packaged JAR passes all14 image contracts, eight PDF/OFD contracts,
twenty earlier TXT contracts and nine earlier column regressions: **51/51
operational contracts,129 observed independent workers**. All new positive Word
inputs were actually reopened. Negative inputs retained expected no-text errors.
Older twenty/nine TXT metrics and recognized inventories are exactly unchanged.
The previous severe shade remains68.34% (earlier accuracy0%), and earlier
English−6° remains27.99% (earlier accuracy0%); those regressions are not hidden.

An independent artifact checker confirms all twelve positive image scan-Word
outputs retain the safety artifact's exact word frames, masks, style/ranks,
embedded scan bytes, every Office PDF word bounding box and rendered page pixels.
All eight PDF/OFD outcomes, TXT/Word/Office metrics and positive original RGB
pixels remain unchanged. Bilingual table image TXT CER remains10.34%; raster
PDF/OFD TXT/XML/Office CER remains34.48%. Successful conversion does not imply
reliable table layout or complete text. Bilingual Word was edited Warehouse→Depot
and reopened via the production Office worker; edited text survives.

[Machine comparison and layout audit](cloud-ocr-iteration8-results.json) includes
both baselines, final case metrics, warnings, source hashes, runtimes, resources
and exact preservation checks. Generated shape IDs are excluded from shape
comparison; actual rendered pixels/glyph boxes are compared independently.

## Tests, traceability and cost

New target tests failed twice on the safety code, then passed. Focused suite:
52 tests passed, including unchanged partial, deskew, enhancement and reading-order
guards. Clean full `mvn -B -ntp -Dskip.frontend=true clean package`: **407 tests,
406 passed, one optional real signed-OFD fixture skipped**, no failures/errors.
Actual bundled-runtime tests ran; system OCR CI is not their substitute.

Final JAR SHA-256:
`c4d842a44fbb841f9237c309a922beb957784300cf84648f1a2ff05e9991ff3b`.
Build-input fingerprint:
`9c36afad43d5b7792fd56f800acfb02c38d58535710991b71b69334db9e2e692`.
All223 application classes match clean targets; aggregate:
`6e9ec30d3938001880a33732a4893ddaca4077b33c1ad946b6edbf7d396bcf7f`.
After commit, build provenance verifies the same inputs/JAR against the exact
commit; the cloud ZIP source revision and PR head identify it without relying
on the inherited health version0.1.4. Exact-head CI is reported in the PR.

Runtime: Temurin17.0.16+8/Maven3.9.11/Node22.17.0; bundled Tesseract5.5.2,
Leptonica1.87.0/libpng1.6.57/zlib1.3.1, pinned tessdata_fast
`87416418657359cb625c412a48b6e1d6d41c29bd`. Full binary/model/source/license/policy
hashes are in the machine record and packaged runtime. OCR remains120 seconds,
25million pixels, concurrency1, minimum confidence0.35. LibreOfficeDev26.8.0.0.alpha0
`2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`; pdftotext25.03.0/fontTools4.61.1.

Final image matrix child peak388068KiB, CPU608.255user+43.012system seconds;
containers398924KiB,220.144+18.235s; nine columns300372KiB,78.621+5.481s;
twenty TXT330772KiB,157.520+11.474s. Baseline counters and route times are also
recorded. Runs overlapped and shared OCR admission; these are whole-run child
counters, not process-tree peaks or isolated speed benchmarks. No extra OCR pass
or raised timeout is introduced. Frontend/desktop tests were not locally rerun
for this backend/QA batch; exact-head CI frontend build is separate.

## Reproduce and remaining limits

```bash
# Extract existing application dependency JARs into a local directory first.
python3 qa-samples/generate_ocr_iteration8.py --classpath '/path/to/dependencies/*'
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration8 \
  --out qa-samples/report/iteration8-final-word --reaper /path/to/reap-run.py \
  --provenance qa-samples/work/iteration8-final-provenance.json
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration8 \
  --out qa-samples/report/iteration8-final-containers --containers \
  --reaper /path/to/reap-run.py --provenance qa-samples/work/iteration8-final-provenance.json
python3 qa-samples/summarize_ocr_iteration8.py
```

Activate the intended JDK and set app-home to the reviewed bundled OCR runtime;
no secrets are shipped. Earlier baselines use saved verified JARs on identical
inputs, and the audit also needs the two final TXT regression reports. Review
the ZIP's HTML source/Office views and downloaded editable artifacts at full size.
An independent maintainer review of the final diff remains required before any
merge. Linux cloud validation does not accept macOS/Windows packages, Microsoft
Word, private corpus/handwriting or general rotated Word. Other sample edits are
unrun. Library delivery's known tunnel403 is not retried or bypassed; the ZIP is
cloud workspace evidence only, not published delivery, release or installer.
