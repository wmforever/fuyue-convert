# Iteration 9: review fix and thresholding diagnostic

Independent review of iteration8 found a real TXT ordering regression: three
successive sections in disjoint vertical bands can have two wide horizontal
gutters, which do not make them simultaneous columns. The27-block Java fixture
uses right/top Upper, left/middle Middle and center/bottom Bottom sections, three
rows and three single-word fragments per row. The published code actually
outputs Middle→Bottom→Upper instead of retaining Upper→Middle→Bottom engine order.
The new regression failed on that code; its failure log prints the wrong order.

The minimal fix requires the three columns' common vertical span to cover at
least60% of their combined extent, matching the existing two-column guard. All
prior fragment, numeric, inventory, coordinate, width/height and deadline rules
remain. The new fixture falls back unchanged; genuine aligned three-column
tests still pass. Only OcrFragmentedColumns.class differs from the parent JAR's
223 application classes; OCR, model/Word/PDF/OFD/rendering classes are identical.
Text-only prose tables remain an ambiguity risk, not universally rejected.

Focused39 tests pass. Clean full Maven:408 tests,407 passed, one optional real
signed-OFD fixture skipped; no failures/errors. Bundled-runtime tests executed.
New JAR SHA-256:
`057ebfdb3f86e5104d6ab5c47ef5c38484fd70dc178004ab0ce628618ca9c04e`.
Build-input fingerprint:
`b1b092dc2837f1436b79350c964b06b0efc2b2c18f40764c439cbb8841a7025c`.
All223 clean target/application classes match; aggregate
`92cc1720a1ea2bf9b9e555bedf804b15872619b12543c261478da08999af4511`.
Exact committed source binding, production TXT regression results and exact-head
CI are recorded in the PR and cloud review packet. This fix does not add fresh
Word/OFD acceptance; iteration8's14 image/Word and8 container artifact checks
remain evidence for their exact prior revision, not this JAR.

## Read-only thresholding experiment

No thresholding production change is adopted. Diagnostic classes were captured
from the immutable parent `da7dded011afdc2a2daae79815eb35566783c618` JAR
`c4d842a44fbb841f9237c309a922beb957784300cf84648f1a2ff05e9991ff3b` before the
separate review fix. Existing source/enhanced images, PSM3, chi_sim+eng, pinned
Tesseract5.5.2/Leptonica1.87.0, minimum confidence0.35, five-point gain and numeric
and partial geometry selectors remain fixed. No new model or production pass,
PSM change, gate relaxation or timeout increase is proposed.

Official [5.5.2 parameter definitions](https://github.com/tesseract-ocr/tesseract/blob/5.5.2/src/ccmain/tesseractclass.cpp)
define method0 global Otsu, method1 Leptonica adaptive Otsu and method2 Sauvola.
[Threshold implementation](https://github.com/tesseract-ocr/tesseract/blob/5.5.2/src/ccmain/thresholder.cpp)
scales window/tile factors by effective DPI, with source-size clamps. Defaults
remain window/tile0.33, Sauvola factor0.34, smoothing0 and Otsu score fraction0.1.
The [official quality guide](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#binarisation)
explains why uneven paper can challenge global thresholding. No algorithm code
or dependency from those sources is copied into the application.

All14 frozen image truths/hashes from seed810032026 and manifest
`601446606c22484a02a65e51668796c675049633c66ece508e45b5c725d59b3a` remain unchanged.
The experiment performs53 sequential CLI runs:14 original Otsu and three
enhanced methods on13 cases. No enhanced candidate is produced for shaded blank,
so its three alternative runs are explicitly unrun, not counted as passed.
Noise produces empty text under all three methods. There are zero failed/timed
out runs; total CLI wall42.739s, child CPU35.878user+5.285system seconds, maximum
per-child RSS101880KiB. Other tests ran concurrently; these are not isolated
speed benchmarks. Every hypothetical original+enhancement+single candidate
fits the unchanged120-second page budget.

| Incomplete shadow | Original confidence | Otsu candidate | Adaptive Otsu | Sauvola |
| --- | --- | --- | --- | --- |
| English mono horizontal |92.31%|94.21%|94.21%|93.55%|
| English serif vertical |92.13%|94.74%|94.00%|95.10%|
| Chinese sans vertical |93.27%|92.32%|92.17%|91.88%|

None meets the retained five-point gain. Mono additionally fails preservation
of the reliable original numeric surface `5%` versus candidate `7.25%`; truth is
not used to overwrite it. Complete candidate text can have low CER while its
adoption still fails safety gates. **No new incomplete-shadow recovery becomes
adoptable**; previous API CER94.89%/48.54%/55.56% therefore stays unchanged.

One already accepted, empty-original Chinese serif shade candidate has a narrow
diagnostic gain: adaptive Otsu CER1.17% versus current Otsu1.75%, one character.
Both satisfy existing full acceptance; no original numeric surfaces exist to
protect there. Sauvola is worse at2.34%. This single sample is not enough for a
production policy. Adaptive Otsu worsens some font/angle candidate controls and
table candidate CER remains lossy. All three modes are rejected on those
controls; no full/partial safety gate is bypassed. Raw CLI metrics exclude later
TXT deskew/reading-order stages; preserved early accuracy, safety and published
API baselines are separately recorded to avoid false regression attribution.

Enhanced ImageIO PNGs have no explicit DPI metadata. Actual method1/2 debug logs
show70ppi; Sauvola's default window is23pixels before later resolution estimation.
No DPI override was used. This is a parameter-comparison limitation and a bounded
next diagnostic hypothesis: preserve the known input DPI in a temporary enhanced
image, then compare adaptive thresholds on independent empty-original controls
under the unchanged gates. It is not a request to lower confidence requirements.

[Full diagnostic record](cloud-ocr-iteration9-threshold-results.json) contains
complete candidate/selected blocks, words/confidence/coordinates, numeric/gain/
geometry decisions, commands, resources, official sources, pinned runtime/model
hashes, source truths and explicit skipped items. Helpers and raw TSV/PNG/log
evidence remain in the ignored cloud diagnostic folder and review packet.
Production thresholding and Word rotation remain unchanged. Known Library403 is
not retried/bypassed; no merge, release, local Mac access or native acceptance.
