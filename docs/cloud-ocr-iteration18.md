# Reproducible QA teardown and OFD partial-recovery contracts

This batch fixes the Linux QA supervisor dependency and closes the controlled
OFD partial-line/conflict/multipage acceptance gap. Production source and the
accepted JAR from `b14f792b6b4624d8ff7e8b0c154e8becff8ba323` are unchanged.
The fixed [gate plan](cloud-ocr-iteration18-plan.json), one-case
[forced-teardown extension](cloud-ocr-iteration18-harness-extension.json), and
[OFD plan](cloud-ocr-iteration18-ofd-plan.json) preceded their measurements.

## Harness correction and safety gate

The old private `/workspace/fuyue-env/reap-run.py` set Linux subreaper mode but
did not handle SIGTERM. Killing that wrapper before its Java descendants exited
could reparent them to the container's non-reaping PID1. The iteration16 audit
ultimately left six Java and four Python zombies. The earlier count of six
covered Java only. The queued-case observer retained engine/child identities
30587/30589; its mutable marker was later overwritten with 30620/30622, whose
start times were 20.39 seconds later. That is consistent with the first task's
20-second timeout allowing queued work to advance while the backend remained
alive. The receipts establish identities/timing, not every scheduler interleaving.
All ten old identities were preserved and remained under PID1 throughout this
batch. They were not signalled; unrelated older Git zombies are a separate
environment baseline. No product cleanup change is inferred from this audit.

The public `qa-samples/qa_process_guard.py` now owns the subreaper, installs
SIGTERM/SIGINT handlers, registers PID plus `/proc` start time, and uses pidfds
to signal only revalidated descendants. It first sends TERM and after two
seconds may KILL owned resistant descendants, while retaining the reaper.
It reaps until `waitpid` reports ECHILD; the caller then waits for the supervisor
and requires every registered identity to be absent, including zombie states.
If teardown exceeds 15 seconds, it reports `HarnessTeardownBlocked` and leaves
the reaper alive for inspection. Further heavy QA must stop. Runners16/17 now
import this checked-in helper instead of the private path/direct group kill.

Three new bounded cases passed before the OFD matrix:

|Case|Evidence|Result|
|---|---|---|
|Successful authenticated HTTP OCR|Independent Worker and foreground engine/child registered|ECHILD; all new owned identities absent|
|Exception with Worker/engine/child active|Context teardown after injected exception|ECHILD; all new owned identities absent|
|Exception with TERM-resistant root/engine/child|Receipt records root SIGKILL, exit −9, four directly reaped/adopted processes|ECHILD; all new owned identities absent|

All three had zero new global zombie identities, unchanged old ten identities,
and a live unrelated sidecar until its owner explicitly waited it. The first two
took 15.857 seconds together; the resistant case took 8.919 seconds. These are
finite Linux harness checks. External SIGKILL/OOM of the supervisor, unkillable
kernel tasks, arbitrary detached children and all scheduling races remain unrun.
The historical iteration16 matrix was not repeated.

## Frozen controlled OFD acceptance

Four 1200×1200 original PNGs contain known labels, a grey background and an
unrecognized blue graphic. They are placed in one page with four images and in
two pages with two images each. Source labels, original/candidate TSV words,
pixel boxes and injected confidence were frozen before measurement in manifest
SHA-256 `e9684c8acc8daeb3dfc835d77943c0a0e0e9a9cc735b6bad056aafcbadc3e728`.

|Image|Controlled treatment|Selected words|CER control → treatment|Aligned character recall control → treatment|
|---|---|---:|---:|---:|
|Full|Retain FULL03121 and adopt independent line ending 00643|5|75.676% → 0%|24.324% → 100%|
|Partial|Reject candidate 0.95/corrected; retain whole `.95 faint` line and add only disjoint line ending 00817|6|78.947% → 0%|21.053% → 100%|
|Numeric reject|Reject ID80424/corrected; preserve `ID00424 faint`|2|0% → 0%|100% → 100%|
|Low confidence|Reject .60 → .62 candidate under existing gain gate|1|0% → 0%|100% → 100%|

Scores use case/whitespace-normalized per-image frozen source labels and actual
model output. They measure controlled selection/completeness, not native OCR
accuracy or an improvement introduced by this QA-only batch. Document ordering
is separately compared against the control; the quadrant layout is column-first.
Confidence does not establish completeness: the partial image retains conflict
and possible-omission warnings even though its selected mean is .846667.

Four real Java model probes verify every selected word, injected confidence,
original image-coordinate mapping (tolerance 1e−6 mm), and warning scope.
Unequal selected word counts 5/6/2/1 yield one page-weighted `OCR_APPLIED` of
.838571429 for the same-page layout. Multipage means are .907272727 and
.586666667. Partial/conflict/omission wording belongs only to image2 on page1;
the fully enhanced neighbor receives no partial wording, and the two rejected
images retain their low-confidence warnings. Two portable, font-independent
JUnit regressions also pass: **2 tests, zero failure/error/skip**.

All **16 authenticated HTTP contracts /16 observed separate production JVM
Workers** succeeded: each layout in control and treatment, OFD→TXT and OFD→DOCX,
then each downloaded DOCX→actual LibreOffice PDF→TXT. HTTP warnings match the
independent model warnings exactly. Downloaded words match selected model words;
`.95`, ID00424 and all original numeric strings survive, with no 0.95/ID80424
injection. All four server receipts prove ECHILD and zero new zombies.

Both Word layouts grow from 6 to 14 editable frames and masks. Every original
frame's geometry, text, style and run properties, original mask geometry/fill,
and original relative reading order survive. Only generated IDs and absolute
z-index are excluded, as declared before testing. Added masks are disjoint from
protected original frames and contained in added frames within 0.5pt. All four
original scan files and decoded RGB pixels match. Actual Office PDF original
words, boxes and relative order are exact. At 72dpi, each changed first page has
2819 changed pixels, all inside added frame/mask regions with a declared 6pt
font/antialias margin. The second page is pixel-identical. These tolerances are
explicit artifact bounds, not a universal document-layout guarantee.

The single HTTP pass cost 81.617 seconds wall, 218.633 seconds child user CPU,
15.818 seconds child system CPU, and 387056 KiB maximum child RSS. RSS is not
summed concurrent memory; there is no speed claim. OCR concurrency1, page
timeout120s, fixed whole-suite480s guard. No deskew/PSM/threshold sweep occurred.

## Reproduction and provenance

The public helper and HTTP runner were executed from a fresh staged-tree export
`86749b6c1f6e0eb1610587c60eed811ba182636d`. Its ignored generated inputs were
explicitly seeded from the hashed manifest; the accepted JAR and installed
Office binary were explicit external dependencies. The private workspace
helper was absent from the export. This verifies the removed helper dependency,
not a zero-dependency installation. See [reproduction prerequisites and commands](cloud-ocr-controlled-reproduction.md).

The accepted JAR SHA-256 is
`70cb1d5ab5bda5756116a61cfc14480e6aa2c98a9bc33fca744028af05468b0e`;
unchanged build-input fingerprint is
`d7917249f93721db6dfa63af3e2e51589a2114ec2eb25a336dc30114647d7f6a`.
All223 packaged application classes still match targets, aggregate
`aa87a3556737504c606b32f3ebfad5345723ead050fe2b28f42140c2e1494051`.
The previous clean419-test package acceptance is historical at b14f792b;
this batch adds two focused tests and does not claim another local full package
run. Final revision/JAR binding and exact-SHA CI are delivered in the draft PR.

Runtime pins: Temurin17.0.16+8, Maven3.9.11, Node22.17.0, Linux6.18.44, Python3.12.14,
Pillow12.3.0, NumPy2.3.5, PyMuPDF1.26.6, Liberation2.1.5 (SIL-OFL-1.1).
Actual Office is LibreOfficeDev26.8.0.0.alpha0,
commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5. Controlled OCR reports
`tesseract controlled-ofd-iteration18`, language eng, bundled=false. Existing
bundled Tesseract5.5.2/Leptonica1.87.0, model revision
87416418657359cb625c412a48b6e1d6d41c29bd, and four pinned model hashes remain
recorded; this batch adds no bundled-native accuracy run. Full versions, hashes,
model/HTTP/Word checks, teardown identities and timings are in
[machine-readable receipts](cloud-ocr-iteration18-results.json).

The OFD controlled partial/conflict gap is now covered. Arbitrary real signed
or vendor OFDs, native macOS/Windows packages, Microsoft Word, vertical/deskew
coverage gaps and external supervisor termination remain unrun. Existing
installers remain stale; Linux JAR acceptance does not certify them.
