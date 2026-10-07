# Iteration10: close the Word/PDF/OFD artifact gap

The previously published disjoint-section guard at
`b4bbe706fb2b6a4c2d159005f8efde06fe81d34e` now has fresh actual artifact acceptance.
This finite batch changes tests and QA tools only. Every production build input
and all accepted JAR bytes remain unchanged; it introduces no recognition,
thresholding, reading-order, masking or Word-layout policy.

## Actual production acceptance

The same frozen seed810032026 corpus and manifest
`601446606c22484a02a65e51668796c675049633c66ece508e45b5c725d59b3a` were used without
regeneration or modification. All22 input hashes were checked against truth.
Previous outputs were preserved in separate directories.

Authenticated HTTP upload → independently observed JVM Worker → artifact download
completed for14 image/Word contracts and8 raster PDF/OFD contracts. Every positive
Word artifact was reopened through the actual production Office route and
rendered. Blank/noise images and blank PDF/OFD wrappers retained their explicit
TXT/Word error contracts. Conversion success is not content completeness.

The independent audit compared against both the prior safety artifacts and the
previous accepted iteration8 artifacts: **60 comparisons** across12 positive
images (direct and scan Word) plus6 positive containers. It checks every original
word frame, mask, style/rank, scan media hash, actual Office text and exact glyph
box/order, and complete rendered pixels. All are exact. Repeated numeric tokens
are retained in the comparison; no unique-token or rounded-box shortcut is used.
All TXT text, numeric boundaries and completeness metrics match the previous
accepted batch; the known English column improvement remains4.76% CER,240/252
recognized characters. Scan/Word/Office text remains equal to safety. This is
regression parity, not equality to truth: missing digits and shaded text remain
missing, and scan pixels do not certify editable-text completeness.

An additional accepted bilingual scan DOCX was edited from Warehouse to Depot,
uploaded through authenticated HTTP and reopened by an independent Office JVM.
The actual edited PDF contains Depot and no Warehouse text object. Original scan
bytes, masks and every unedited frame/Office word box/order remain exact, including
numeric lexemes. Render changes occupy only pixels[42,32,89,41], inside the edited
glyph envelope with2pt antialias allowance. This proves one short Latin edit;
general edits, reflow and Microsoft Word remain unrun.

The scan-edit helper initially rejected a trailing space in the source frame
before conversion. Its next check exposed changes in PyMuPDF's inferred line
IDs when a frame gets shorter; actual text, boxes/order and localized pixels were
unchanged. The final helper retains exact physical comparisons and excludes those
extractor IDs. Both attempts were preserved; the corrected end-to-end check passed.
The safety image report had not run bilingual editing; that omission remains
explicit rather than being counted as a pass. Previous-final and fresh image
editing both passed independently.

| Matrix | Safety wall / child CPU user+system / peak RSS KiB | Previous accepted | Fresh b4bbe706 |
| --- | --- | --- | --- |
|14 images/Word|279.735s /595.599+43.729s /382160|396.952s /608.255+43.012s /388068|215.270s /529.167+38.099s /380980|
|8 PDF/OFD|101.676s /220.944+18.442s /412288|208.341s /220.144+18.235s /398924|59.880s /168.116+13.305s /393928|

Wall columns sum the recorded positive route times; they exclude startup,
blank/error calls and extra editing. Fresh matrices observed77+22 independent
worker PIDs; the final scan edit observed2 more. The scanner samples PIDs and is
not an exact completed-call counter. The maximum positive conversion times are
5.945s and5.759s, below unchanged120-second per-conversion and page limits.
Matrices were not isolated benchmarks, so lower counters do not establish a
performance improvement. All route records/resources are retained in
[the comparison JSON](cloud-ocr-iteration10-results.json).

## Review coverage

Two test additions exercise distinct Alpha/Bravo/Cedar column text and the
coverage boundary. Each column spans200; first-to-last offset50 yields common
span150 over combined span250, exactly60%. Offsets49.9 and50 pass;50.1 and125
retain unchanged engine order. Strongly staggered short columns deliberately
remain conservative. Original source objects remain identical.

The focused OcrFragmentedColumnsTest suite now has **7 passing tests**, zero
failures/errors/skips. The previously completed39-focused and408-total/407-pass/
one signed-OFD-skip runs remain evidence at b4bbe706; neither they nor the53 CLI
threshold experiments were repeated. Exact final-head CI is recorded in the PR.
Python syntax, public probe compilation/execution, privacy gate and diff checks
also passed. There is no new native installer acceptance.

## Next bounded improvement candidate

`OcrProseTableOrderProbe.java` independently constructs36 legal OCR blocks with
row-major truth: four rows, three prose cells per row, three short fragments per
cell. Actual Java arrangement on the accepted production classes imposes column
order, changing CER0%→18.63% while preserving every source object and character.
Shared vertical coverage alone cannot distinguish prose cells from columns.
The truth/output, geometry and helper hash are recorded in the comparison JSON.
This is **model-level synthetic evidence, not an OCR/API image result**.

A narrow next candidate is to retain engine order when source blocks repeatedly
interleave the inferred column bands, and reconstruct only coherent column runs.
That should protect this already correct row-major input; it cannot solve prose
tables that the engine itself already emits in coherent columns. Independently
rendered tables and positive narrative columns must be frozen and measured before
adoption. No such guard is adopted in this acceptance batch, and no confidence,
numeric, geometry or timeout safeguard is weakened. The previous threshold/DPI
hypothesis is also unimplemented. Rejected Word prototypes remain rejected.

## Reproduction and traceability

Use the reviewed JDK17/bundled OCR/Office environment from cloud-handoff.md.
Each matrix ran under a subreaper with a fresh private token; no token is stored.
Image batch limit1200s, container600s, edit300s; each conversion120s. In the cloud:

```bash
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration8 \
  --out qa-samples/report/iteration10-b4-word \
  --provenance qa-samples/work/iteration9-overlap-provenance.json \
  --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-iteration8 \
  --containers --out qa-samples/report/iteration10-b4-containers \
  --provenance qa-samples/work/iteration9-overlap-provenance.json \
  --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/run_cloud_ocr.py \
  --scan-edit-source qa-samples/report/iteration10-b4-word/bilingual-upright.png.scan.docx \
  --out qa-samples/report/iteration10-b4-scan-edit-final \
  --provenance qa-samples/work/iteration9-overlap-provenance.json \
  --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/verify_cloud_artifact_regression.py \
  --edit-report qa-samples/report/iteration10-b4-scan-edit-final --out qa-samples/work/reproduced-artifact-audit.json
```

These commands are evidence recipes, not instructions to overwrite preserved
outputs: choose new output directories for later runs and pass --after-prefix to
the audit. Compile the package-private model probe with javac into an ignored
class directory and run it with the accepted production class/dependency path;
Java source-launch uses a different classloader and cannot access that class.

Accepted JAR SHA-256:
`057ebfdb3f86e5104d6ab5c47ef5c38484fd70dc178004ab0ce628618ca9c04e`.
Production build-input fingerprint:
`b1b092dc2837f1436b79350c964b06b0efc2b2c18f40764c439cbb8841a7025c`.
All223 application classes aggregate:
`92cc1720a1ea2bf9b9e555bedf804b15872619b12543c261478da08999af4511`.
Final source binding/packet hashes and exact-head CI are recorded in the PR and
ignored cloud checkpoint; the executed acceptance revision remains b4bbe706.

Runtime: Temurin17.0.16+8, Maven3.9.11, Node22.17.0; bundled Tesseract5.5.2,
Leptonica1.87.0/libpng1.6.57/zlib1.3.1; tessdata_fast87416418657359cb625c412a48b6e1d6d41c29bd;
LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5;
pdftotext25.03.0; PyMuPDF1.26.6/Pillow12.3.0/NumPy2.3.5/fontTools4.61.1.
Exact model/binary/policy/font hashes and font versions are in the JSON evidence.
Cloud validation is distinct from native macOS/Windows package acceptance.
Library403 is not retried or bypassed; no merge/release/Mac access occurred.
