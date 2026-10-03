# Cloud OCR batch: recovery, enhancement cost, and deskew evidence

Baseline: `e3ffde164e90e766b7a9d6e3b1fae3f53b94d7d5` in
`wmforever/fuyue-convert`. Linux x86_64, 2026-10-03.

Follow-up inflated-box recovery and broader no-regression holdouts are recorded
in [the next measured iteration](cloud-ocr-iteration2.md).

## Shipped batches

1. `55cce9b`: cache horizontal paper interpolation for two tile rows. Both
   image passes preserve floating-point operation order, output pixels, selection
   thresholds and sparse-ink recovery. Six golden hashes plus a null result match
   the baseline. Additional scratch payload is `28 * imageWidth` bytes: about
   84 KB at 3000 pixels, with no extra image or OCR process. Median of three
   warmups/seven timed runs: 1.4 MP **134.181 → 53.634 ms** (60% less); 12 MP
   **1390.159 → 448.672 ms** (67.7% less).
2. `9a341b8`: use page-anchored DrawingML for OCR scan backgrounds. The baseline
   could hide the source image behind many VML word boxes, making white OCR
   letters invisible on dark paper despite complete extracted PDF text. A
   48-word synthetic regression opens the DOCX with actual Office and checks
   dark paper, visible white editable glyphs and an unrecognized red mark.
   Source image bytes, per-word masks and ordinary-photo layering are retained.
   One existing layering test needed updating from VML-specific syntax to the
   same behind-text contract on DrawingML; that assertion fix follows this commit.
3. Bounded deskew is enabled **only for PNG/JPEG → TXT**. Original recognition,
   existing optional shading recovery and one optional deskew process share the
   same page budget. The expanded canvas is inverse-mapped to original source
   coordinates, capped by existing image limits, and always released. Candidate
   selection conserves reliable spatial content, requires minimum confidence,
   rejects a confidence drop over five percentage points and text quantity below
   90%, and caps matching at two million comparisons plus the shared deadline.
   Reliable conflicting numbers retain their original value with an explicit
   warning. Word and PDF/OFD layout OCR keep their existing geometry.

The native shaded-English test uses repository Liberation Sans instead of
host-dependent logical SansSerif. Its original fixture failed on Linux with
both baseline and optimized enhancers, dropping the final `l` in `total` and
splitting a line. Pinning the fixture makes this check reproducible; it does not
fix that underlying engine defect. The Chinese shading fixture still uses
logical SansSerif for its digit run and is not fully font-independent.

## Recovery and runtime

The initial checkout was clean at the baseline above, with origin pointing only
to `https://github.com/wmforever/fuyue-convert.git`. All 5,189 saved artifact hashes
matched. `source /workspace/fuyue-env/activate.sh` restores the intended toolchain;
the default shell otherwise selects Java 21 and Node 24. The saved installer,
activation/start helpers, model files, dependency caches and build outputs exist.
The environment's saved `start_skill` metadata itself is not exposed in this
session; only its script counterparts were inspected. No new task URL was supplied.

`gh auth status` failed because the shell GH_TOKEN is invalid; `gh api user`
returned `Forbidden`. The explicitly selected wmforever connector independently
returned login `wmforever`, repository admin/push permission, and created
`improve/cloud-ocr-completeness-20261003` in the upstream repository. The other
GitHub account and fork were not used. No credentials were added.

| Component | Verified version |
|---|---|
| Java | Temurin 17.0.16+8 |
| Maven / Node | 3.9.11 / 22.17.0 |
| System OCR (recovery only) | Tesseract 5.5.0, Leptonica 1.84.1 |
| Bundled Linux OCR (acceptance) | Tesseract 5.5.2, Leptonica 1.87.0, libpng 1.6.57, zlib 1.3.1 |
| Native build | CMake 4.4.3, installed into workspace after missing-tool failure |
| Office | LibreOfficeDev 26.8.0.0.alpha0, commit 2c87e51eeaa2b413ff4ae097b2705eea1995d8e5 |
| Poppler | 26.05.0 |
| Sample/experimental tooling | Pillow 12.3.0, NumPy 2.3.5 |

Bundled runtime built with the repository's pinned source/model/license policy
and passed `prepare-ocr-runtime.mjs --verify`, including actual bilingual PNG/TSV
recognition. The API independently reported `bundled: true`, Tesseract 5.5.2,
`chi_sim+eng`, 120-second page timeout, one OCR process, and 25-million-pixel limit.
System OCR is not counted as bundled acceptance.

- Bundled binary SHA-256: `90730922627269352568d54e788c7e42fd2e1783e03795d75560efa385de1489`
- Runtime policy SHA-256: `54c6ae7291fc983cb7b946bc0b90832ec4b589d493fef01b5f974a0ab26d8735`
- LiberationSans-Regular.ttf (version 2.1.5) SHA-256: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`
- DroidSansFallback.ttf (version 2.55b) SHA-256: `21b96a0377f067833a93af3082eb28d4ffab7a8cd46bfd513286f1d64b7b0949`

Model hashes (all verified against `desktop/licenses/ocr-runtime-lock.json`):

| Model | SHA-256 |
|---|---|
| eng | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| chi_sim | `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730` |
| chi_sim_vert | `20590de84725bab69cde93bd6e8ed360a13cc5421a7e7364ddeb93e9af53d6da` |
| osd | `9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff` |

## Bundled API and Word measurements

The six handoff samples were regenerated from the committed generator/truth;
source hashes are in `expected.json`. Before/after use bundled Tesseract 5.5.2,
`chi_sim+eng`, PSM 3. CER removes whitespace and Latin case but preserves digits
and punctuation. Recall counts exact characters on a minimum-edit alignment;
exact-line counts are capped by truth occurrences. Confidence is reported
separately and never treated as completeness.

| Sample | TXT CER before → after | Aligned recall before → after | Exact lines before → after | Scan Word XML CER before → after |
|---|---:|---:|---:|---:|
| English 0° | 0.00% → 0.00% | 100% → 100% | 8 → 8 | 0.00% → 0.00% |
| English −6° | 27.99% → 0.00% | 86.01% → 100% | 5 → 8 | 27.99% → 27.99% |
| English +6° | 79.52% → 0.34% | 20.48% → 99.66% | 1 → 7 | 79.52% → 79.52% |
| Chinese 0° | 3.06% → 3.06% | 96.94% → 96.94% | 6 → 6 | 3.06% → 3.06% |
| Chinese −6° | 32.65% → 32.65% | 74.49% → 74.49% | 1 → 1 | 22.45% → 22.45% |
| Chinese +6° | 27.55% → 1.02% | 75.51% → 98.98% | 1 → 7 | 41.84% → 41.84% |

English +6° truth is `80421`. The original recognizes `80424` at 93.731171%
word confidence; the corrected candidate says `80421`. The production output
**deliberately remains `80424`**, with `OCR_RECOGNITION_CONFLICT` asking for review.
The 0.34% CER reflects that one preserved wrong digit. It is not a claim that
numeric truth was corrected. Chinese −6° is conservatively rejected and retains
its incomplete original result, even though an unconstrained offline experiment
has lower CER. The recovery policy deliberately accepts less than raw deskew.

The two shaded bilingual regressions remain at 0% TXT/Word XML CER with eight
exact lines. Their actual Office PDFs retain the shaded scan background. The
new 48-word test also checks pixels for original red content and readable white
letters, rather than treating extractable text as proof of visual fidelity.
All six scan media hashes are unchanged. PNG → DOCX extracts positioned editable
text without a scan layer; PNG → PDF → scan DOCX contains both image and text.
An edited English Word run (`Invoice` → `Receipt`) survives Office reopening.

### Word deskew acceptance blocker

A full-layout prototype recovered tilted words in XML, but actual LibreOffice
rendering left each word horizontal while its position followed the slope. PDF
text extraction reordered the tilted English words (55.63%/62.80% CER). VML
rotation, DrawingML WPS rotation and grouped-shape experiments failed to fix
this. A rotated ODT exported by Office to DOCX also reopened with horizontal
text in this cloud Office build. Those experiments are **not shipped**. Word
uses its original geometry and masking; arbitrary-angle Word recovery remains
unaccepted. Existing tilted Word omissions remain visible in the table above.

For scan Word after actual Office reopening, CER stays **0.00, 9.90, 79.52,
3.06, 22.45, 41.84%**, in table order, unchanged from baseline. This is an
editability/layout limitation, not evidence that the TXT improvements also fix
Word. The corrected background is a separately measured visual improvement.

### Resource boundaries and validation

Deskew uses a ≤700-pixel-long-edge grayscale thumbnail, two 100,000-element
coordinate arrays (~0.8 MB), and one expanded RGB image. The new image's pixel
payload can reach ~100 MB under the existing 25 MP limit, excluding object
headers, the original image and engine/model memory. Only detected eligible
TXT pages add a subprocess. Upright, blank/sparse, grid, out-of-range, dark/dense,
vertical, anisotropic and nearly expired inputs fall back. Optional process
failure/timeout releases the temporary PNG and preserves a usable original.
Large word sets skip matching rather than exceed the shared deadline.

The full bundled suite initially exposed an undersized projection-row array on
wide, bottom-edge ink; its bound is corrected and a dedicated regression added.
No failed experimental implementation is counted as acceptance.

The enhancement-only full run was 367 tests: 366 passed and one optional real
signed-OFD fixture skipped. Frontend 20/20 and desktop 69/69 were independently
rerun at recovery. The final batch full run is **376 tests: 375 passed, zero failures/errors,
one skip**, including all eight deskew tests and all four actual Office tests.
The remaining skip is `OfdrwParserSignatureTest`'s optional real signed-OFD
fixture. Packaging, Python syntax, `git diff --check` and local-only privacy gate
pass. Rebuilt production JAR SHA-256:
`7c97bcdb152446db75f1920fcd12ee9f432f3642e6e72fca5a68d79f3a702b3f`. Bundled runtime verification includes actual bilingual
PNG/TSV recognition; system OCR does not substitute for these checks.

Not run: native Windows/macOS package acceptance, Microsoft Word, real signed-OFD
fixture, private business corpus and handwriting/complex-photo quality acceptance.
The cloud Office is a development build and does not validate the pinned native
release Office runtime. Existing installers remain stale; no version, release,
merge or main-branch changes are made.

### Final HTTP acceptance and observed cost

All six handoff cases complete upload → separate JVM worker → artifact download,
then actual DOCX → Office PDF reopening. The two shaded cases use the same
pipeline. Baseline/final reports preserve full text, warnings, metrics, media
hashes, raster previews, dimensions and output sizes under ignored local reports.
The six-case final runner observes **37 independent JVM workers**; an additional
12 workers execute the shaded cases. HTTP timings include Java startup, queueing,
OCR and download. These are single observations on a shared cloud host, not
repeatable speed guarantees.

| Sample | TXT HTTP seconds before → after | Word / Office seconds after |
|---|---:|---:|
| english-tilt-+0.png | 2.453 → 2.115 | 3.193 / 2.146 |
| english-tilt--6.png | 1.895 → 2.748 | 3.184 / 2.103 |
| english-tilt-+6.png | 2.328 → 2.926 | 3.147 / 1.669 |
| chinese-tilt-+0.png | 1.661 → 1.881 | 3.148 / 2.503 |
| chinese-tilt--6.png | 2.277 → 2.710 | 3.912 / 2.076 |
| chinese-tilt-+6.png | 2.479 → 2.905 | 4.140 / 2.471 |

Six-case child peak RSS is 333,636 → 334,476 KiB; total observed child user/system
CPU is 251.377/14.200 → 254.781/17.286 seconds. `RUSAGE_CHILDREN.ru_maxrss` is the
largest child peak, **not concurrent aggregate memory**. Original recovery's
worker observer did not yet capture PIDs, so worker isolation was subsequently
proved separately and in the final runs. This avoids presenting an empty PID
list as proof. Word timings fluctuate but the code adds no deskew process there.

## Reproduction

From a clean baseline checkout, activate the toolchain and generate the six
inputs, then build the baseline JAR before changing production source. Preserve
that JAR or run its HTTP matrix before rebuilding. Generated inputs/reports,
binaries and model artifacts remain ignored and are not committed.

```bash
source /workspace/fuyue-env/activate.sh
python3 qa-samples/generate_ocr_handoff_samples.py
# CMake is a prerequisite; this cloud workspace installs it under fuyue-env/tools.
export PYTHONPATH=/workspace/fuyue-env/tools/cmake-python
export CMAKE_BIN=/workspace/fuyue-env/tools/cmake-python/bin/cmake
node desktop/scripts/prepare-ocr-runtime.mjs
node desktop/scripts/prepare-ocr-runtime.mjs --verify desktop/.runtime/ocr
export FORMAT_CONVERTER_APP_HOME="$PWD/desktop/.runtime"
python3 /workspace/fuyue-env/reap-run.py mvn -B -ntp -Dskip.frontend=true test
mvn -B -ntp -DskipTests -Dskip.frontend=true package
python3 qa-samples/run_cloud_ocr.py --out qa-samples/report/cloud-after \
  --reaper /workspace/fuyue-env/reap-run.py
python3 qa-samples/benchmark_ocr_enhancement.py
python3 qa-samples/evaluate_ocr_deskew.py --runtime desktop/.runtime/ocr \
  --out qa-samples/report/deskew-trial
```

The HTTP runner creates an ephemeral auth token, uses the production JAR and
independent JVM workers, downloads artifacts, reopens Word through Office,
extracts PDF text and renders preview PNGs. It also edits an English Word run and
checks the changed text after Office reopening. Tokens are neither logged nor
stored. The optional Linux subreaper is needed here because PID 1 does not reap
orphaned children. Without that container constraint, omit `--reaper`.

The two shaded regression inputs are exported by the native test; the helper
writes frozen source truth and current image hashes, without deriving truth from
recognition:

```bash
mvn -B -ntp -pl task-service -am -Dskip.frontend=true \
  -Dtest=OcrContrastEnhancementTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dformat.converter.ocr-enhancement.qa-directory="$PWD/qa-samples/generated/cloud-shaded" test
python3 qa-samples/generate_ocr_regression_manifest.py \
  --samples qa-samples/generated/cloud-shaded
python3 qa-samples/run_cloud_ocr.py --samples qa-samples/generated/cloud-shaded \
  --out qa-samples/report/cloud-shaded --reaper /workspace/fuyue-env/reap-run.py
```

Truth is the fixed eight-line text in that test. NumPy and
Pillow are optional experiment dependencies only, not application dependencies.
