# Cloud iteration 4: unresolved coverage warnings and review build

Baseline: `ae7543e1c53e11b3945793a2bbc3869ace81af83`. Same upstream branch and
draft [PR #1](https://github.com/wmforever/fuyue-convert/pull/1), wmforever only.
Warning semantics were proposed in [#4](https://github.com/wmforever/fuyue-convert/issues/4)
before implementation. No merge, release, native installer or local Mac work.

## Bounded diagnosis: completeness can reduce average confidence

Both known vertical-shadow inputs contain six lines; original OCR sees only the
bottom three. Existing same-pixel normalization recovers all six with lower mean
confidence. The original five-point gain requirement rejects both results; an
original over95% cannot meet it with any candidate capped at100%.

| Known input | Original CER / confidence | Enhanced PSM3 CER / confidence | PSM6 CER / confidence | PSM11 CER / confidence |
|---|---|---|---|---|
| English monospaced vertical shadow | 47.98% / 95.82% | 0% / 93.38% | 2.89% / 93.12% | 0% / 93.38% |
| Chinese vertical shadow | 52.58% / 92.81% | 0% / 92.21% | 1.03% / 91.71% | 0% / 92.33% |

These are actual pinned bundled engine runs, not system OCR. PSM alternatives
operate on the existing normalized temporary image and never enter production.
Original vs candidate confidence averages describe different token populations;
adding difficult omitted words can lower the average despite exact synthetic
truth. This does not justify indiscriminately relaxing acceptance: graphics,
halftones and hallucinated words can also increase apparent quantity/coverage.

No rejected result, alternate PSM or new numeric replacement is adopted. A future
coverage-driven additive recovery would need reliable spatial/token conservation,
independent high-confidence non-text/adversarial cases and an explicit contract.
That investigation remains separate from this bounded batch.

## Actual behavior change

`OCR_POSSIBLE_TEXT_OMISSION` alerts when the existing bounded shaded-ink probe
finds dispersed uncovered regions and no enhancement/deskew candidate is adopted.
It describes possible omissions or non-text graphics, not proven missing text.
High-confidence results over95% can now be probed for this warning without an
impossible-gain extra OCR process. Remaining-time guards,700-pixel thumbnail,
two-million word-mask visits, pixel limits and shared deadlines remain unchanged.

Recognition text, numbers, word boxes, source RGB, masks and mean confidence are
unchanged. Existing candidate acceptance, blank/noise rejection, TXT column
ordering and the private task-deadline exhaustion fix remain intact. Accepted
enhancement/deskew results retain their existing review warnings; absence of this
new warning does not certify completeness or correctness. Word/PDF/OFD arbitrary
rotation is still disabled and unresolved.

## Independent evidence and separation of metrics

Six fresh truth/font-controlled synthetic inputs were frozen without consulting
OCR: monospaced/serif English and Noto Sans CJK Chinese, each with vertical shade
and uniform gray counterparts. Existing nine shading inputs, six handoff samples
and three prior recovery/column cases bring acceptance to **24 cases**.

| Fresh input | TXT CER before → after | New warning |
|---|---:|---|
| English mono vertical | 46.59% → 46.59% | Possible omission |
| English serif vertical | 46.59% → 46.59% | Possible omission |
| Chinese vertical | 51.49% → 51.49% | Possible omission |
| English mono uniform | 0% → 0% | None |
| English serif uniform | 0% → 0% | None |
| Chinese uniform | 7.92% → 7.92% | None; content errors remain |

Both original vertical failures also receive the warning. Thus five measured
omission cases are surfaced; **no text CER gain is claimed**. All24 TXT outputs,
editable XML text and scan media hashes match their prior baseline. All positive
scan Word cases preserve every decoded original RGB pixel; synthetic red-ink
probes and unmasked margins remain visible after actual Office reopening.
Three blank/noise/mark cases retain explicit `OCR_NO_TEXT` for TXT/DOCX.
English+6° still retains wrong original80424 with a conflict warning; no truth
number is inserted. Column output remains English0%/Chinese1.35% CER.

The previous two non-text probe false positives (multiple red marks, sparse
halftones) remain documented; this batch does not promote their empty candidates.
The warning can also be caused by graphics. Existing single-mark and noise
controls have no new warning/adoption. Uniform Chinese retains7.92% content CER
without a coverage warning: coverage is not general word-accuracy detection.

TXT CER, editable DOCX/XML completeness, actual Office PDF extraction order and
scan-pixel preservation are separate evidence. Original scan pixels can remain
visually complete while editable OCR misses half the text. Prior recovered
monospaced Office PDF extraction still has5.41% CER due frame reading order.
No Word rotation or general visual-fidelity metric is claimed as solved.

## Validation, identity and cost

Clean full Maven: **387 tests,386 passed,zero failures/errors,one optional real
signed-OFD skip**. All19 contrast tests,45 task lifecycle tests, nine deskew and
four actual Office tests execute. New tests preserve original numbers and image
bytes after candidate rejection, warn above95% without another OCR process,
and distinguish accepted/rejected candidate alerts. Frontend20,desktop69,
desktop checks, Python compilation, launcher syntax, privacy and diff gates pass.
Authenticated HTTP24cases observe **133 separate JVM workers**, with downloaded
artifacts and actual Office reopening. The fresh six-case baseline is rerun
under the verified ae754JAR; older unchanged truths/artifacts are reused precisely.

Candidate JAR SHA-256:
`9173918803d13de7ebde2a8bffea371a6040a2a08517222b25c9339a43e55c5c`.
Source/resource/POM fingerprint:
`86024fdfd15372af021621be9571aec00da1a440bcddcd0fea429cec8ebf788a`.
All **221** packaged application classes match fresh clean target classes;
aggregate SHA-256:
`5750b0369c2276ff1bc869feb1ed1522aad5dff14b20a6781aec52af9d170564`.
`record_cloud_provenance.py` verifies against the final delivered Git commit.
Largest observed child RSS362044KiB; child user/system CPU899.09/60.70s.
These are single-run observations, not summed memory or a speed claim. Extra
high-confidence probes share existing bounds and do not launch impossible retries.

Exact runtime/model/font versions and hashes are unchanged from
[iteration3](cloud-ocr-iteration3.md): Temurin17.0.16+8, Maven3.9.11, Node22.17.0,
bundled Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1,
LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5,
Poppler26.05.0, Pillow12.3.0, fontTools4.61.1. New fonts are existing licensed
Liberation2.1.5 and Noto Sans CJK2.004; manifests include exact SHA-256/glyph checks.
System OCR does not substitute for this bundled acceptance.

## User-reviewable cloud build

`build_cloud_review_bundle.py` packages only a clean committed revision with
verified source/JAR identity: executable cloud JAR, fixed Linuxx86_64 OCR and
models/licenses, exact public Git source archive, selected synthetic inputs and
converted TXT/DOCX/PDF/scan views, raw metrics/provenance and an HTML review table.
Per-file SHA256SUMS and an external ZIP checksum support independent review.
No API token, server log, task storage, private upload, unrelated clinical file,
desktop installer, JDK or Office binary is included.

The launcher requires JDK17 and a user-supplied API token and binds127.0.0.1 by
default. DOCX→PDF requires separately configured compatible Office. Cloud Office
is a development build, not native release Office acceptance. Windows/macOS,
other Linux distributions, Microsoft Word, private corpus and real signed OFD
remain unrun. The inherited health0.1.4 label is not an artifact identifier.
Library delivery and exact final ZIP checksum/path are recorded in the PR and
handoff only after successful upload; inaccessible files are not described as delivered.

```bash
source /workspace/fuyue-env/activate.sh
export FORMAT_CONVERTER_APP_HOME="$PWD/desktop/.runtime"
python3 qa-samples/generate_ocr_omission_holdouts.py --acceptance
# Clean test/package and provenance follow the iteration3 commands.
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration4-acceptance \
  --out qa-samples/report/iteration4-acceptance --provenance qa-samples/work/iteration4-build-provenance.json \
  --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/record_cloud_provenance.py --record qa-samples/work/iteration4-build-provenance.json \
  --verify-revision YOUR_COMMITTED_SHA
python3 qa-samples/build_cloud_review_bundle.py --provenance qa-samples/work/iteration4-build-provenance.json \
  --samples qa-samples/generated/cloud-iteration4-acceptance --report qa-samples/report/iteration4-acceptance \
  --out qa-samples/output/fuyue-convert-cloud-review.zip
```
