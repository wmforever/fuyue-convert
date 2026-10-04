# Fresh-checkout controlled fixture dependencies

The iteration15 report alone is **not self-contained**. Its generator requires
the iteration11 EN-serif shadow PNG/manifest and a native enhanced PSM3 TSV.
The `original.tsv` path is currently assigned but not read; it remains recorded
as baseline evidence. Ignored generated/work directories do not come from Git.

At source `2fe947d156b34339735de3078dd047ec665e8267`, a fresh `git archive`
directory regenerated all sixteen iteration11 samples with the pinned fonts,
Pillow 12.3.0, NumPy 2.3.5 and fontTools. Its manifest was byte-identical:
`cac258df4f6f80046529d25c85cf039605cab55d89f039f670944e07f6dc1ab1`.
After seeding the two exact cached TSVs from the checksummed iteration15 review
packet, the iteration15 generator reproduced the entire controlled manifest,
PNG copies, responses and engine scripts byte-for-byte:
`37f6558bbb42ed4446fca4f26503810978e107ff2cc487a6753f64d9852c77eb`.
No OCR diagnostic was repeated for this reconstruction check.

Packet: `fuyue-cloud-review-2fe947d-linux-x64.zip`, SHA-256
`f38094c210b808bd6ec2724aee27574b03978534821dc69e56609f22702ab255`.
Copy its `cached-native/` entries into the corresponding `qa-samples/work/`
paths in the new checkout before running the iteration15 generator:

|Relative work path|SHA-256|
|---|---|
|iteration11-native/en-serif-shadow.png/original.tsv|8a3ae512873185ac4810a29f2b1dc348d4e24254c3a8e0a6cd99a98621acf71e|
|iteration11-dpi/en-serif-shadow.png/without-dpi.tsv|a31bb61a391c6105978de4ab53c1d2aaa6015189ddfed09f615c3939ea97cf31|

```bash
python3 qa-samples/generate_ocr_iteration11.py
# Seed the checksummed cached TSVs above; generated directories must be fresh.
python3 qa-samples/generate_ocr_iteration15.py
```

Alternative without the packet: with Maven targets/dependencies prepared,
compile `qa-samples/OcrPsmCandidateProbe.java` against `task-service/target/classes`,
`layout-model/target/classes` and the application's dependency JARs. Run its
`prepare` mode on the generated `en-serif-shadow.png` into
`qa-samples/work/iteration11-dpi/en-serif-shadow.png`. It writes `enhanced.png`
without DPI. Run pinned bundled Tesseract 5.5.2, eng+chi_sim, PSM3, TSV output
on that PNG with basename `without-dpi` in the same directory. Compare its TSV
hash above before deriving the controlled corpus; do not silently substitute
different engine/font output. Exact binary/model/font hashes are in
[iteration14 results](cloud-ocr-iteration14-results.json). Native CLI rebuilding
was **not rerun** in this check; cached native inputs are an explicit dependency.

For iteration17, first generate the six handoff PNGs with
`generate_ocr_handoff_samples.py`, compile `OcrMultiRasterOfdFixture.java` with
the application's OFDRW dependency classpath, then run
`generate_ocr_iteration17.py --classes <compiled-directory> --dependencies
<application-classpath>`. The runner consumes the four frozen OFDs and local
engine/TSV files. OFD ZIP metadata may vary on regeneration; decoded source
pixels, geometry and text truth must match. Controlled confidence/version
strings are injected and never constitute bundled-runtime accuracy acceptance.
