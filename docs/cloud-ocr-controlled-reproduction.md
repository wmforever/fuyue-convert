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

## Checked-in Linux supervision and iteration18

Runners16/17/18 now import `qa-samples/qa_process_guard.py`. Python must expose
`os.pidfd_open` and `signal.pidfd_send_signal`; the Linux kernel must support
pidfds and `prctl(PR_SET_CHILD_SUBREAPER)`. The measured host is Linux6.18.44.
Keep the supervisor alive until ECHILD; never replace it with direct Java or
kill its process group to satisfy a teardown deadline. A blocked receipt means
stop process-heavy work and inspect the retained owning reaper.

Iteration18 uses Python3.12.14 with pinned Pillow12.3.0, NumPy2.3.5, PyMuPDF1.26.6,
LiberationMono-Regular2.1.5 and Java17. The generator asserts the exact font hash.
Compile `qa-samples/OcrContractOfdFixture.java` and
`qa-samples/OcrOfdContractProbe.java` against built application classes and their
dependencies. A prepared Maven classpath or unpacked accepted Spring Boot JAR
provides those dependencies: `BOOT-INF/classes` plus `BOOT-INF/lib/*` contains
the packaged application modules and OFDRW. Put compiled probe classes first.
For example, with `QA_CP` set to that absolute classpath:

```bash
# Activate Java17 first; both java and javac must resolve on PATH.
# This cloud snapshot uses: source /workspace/fuyue-env/activate.sh
javac -cp "$QA_CP" -d qa-samples/work/iteration18-classes \
  qa-samples/OcrContractOfdFixture.java qa-samples/OcrOfdContractProbe.java
python3 qa-samples/generate_ocr_iteration18.py \
  --classpath "qa-samples/work/iteration18-classes:$QA_CP"
```

This packaged-JAR-only classpath was independently compiled successfully after
activating the pinned JDK. The first attempt on the unactivated PATH exited127
because javac was absent; both supervision receipts reached ECHILD and remain
in the review evidence. No OCR/HTTP matrix was rerun for this check.

Alternatively seed `qa-samples/generated/cloud-iteration18/` from the reviewed
packet, verify all `expected.json` hashes and restore executable permissions on
its four engine scripts. Generated OFD ZIP timestamps can vary when rebuilding;
do not silently replace the recorded manifest. Source pixels, selected surfaces
and original-coordinate geometry must match. The actual fresh staged-tree
export used exact cached generated files, manifest
`e9684c8acc8daeb3dfc835d77943c0a0e0e9a9cc735b6bad056aafcbadc3e728`,
and no private supervisor. It used an external accepted JAR and explicit Office
binary; runtime/tool installation was not repeated.

```bash
export FORMAT_CONVERTER_OFFICE_BINARY=/absolute/path/to/soffice
python3 qa-samples/run_ocr_iteration18_http.py \
  --jar /absolute/path/to/accepted-web-api.jar \
  --out /absolute/path/to/fresh-http-output --scope ofd18
```

The original full receipt verifier uses the fixed evidence layout documented
in `summarize_ocr_iteration18.py`: `report/iteration18-http`, the four
`work/iteration18-models/{same,multi}/{control,treatment}/model.json` files,
the focused JUnit XML, fresh-export receipt, and both harness-gate receipts.
Running that verifier only reads artifacts; it never launches OCR or Office.
The historical harness-gate script also requires the iteration15 cached corpus
and the old ten-PID audit receipt in the original cloud environment. It is a
bounded audit reproduction, not a portable assumption that every fresh host
contains those ten PIDs. Do not recreate or signal historical zombies.
