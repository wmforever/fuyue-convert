# Preserve heading order around editable PDF columns

A born-digital PDF with a spanning title and two columns produced a DOCX whose
floating column text preceded the title in document XML order. Source positions
looked plausible, but extracting the document's text sequence moved the heading
after the columns. The six-file independent baseline reproduced this problem;
two new one-/two-page regressions also fail on parent
`98b0ad77144a405f12bd4e5363c3342baa47cea3` before the correction.

The renderer now anchors ordinary horizontal column frames to their preceding
body paragraph. A heading remains normal body text; the shapes follow its direct
text runs. No paragraph/line box is added, and the original frame XML, absolute
coordinates, styles and z-order remain exact. Separate column regions stay with
their own headings and pages. The path is limited to pages without images,
rules, tables, OCR words or transformed text. Existing OCR, masking, transformed
table handling, column gap thresholds and cross-page prose gates remain intact.
Tests that formerly excluded any paragraph containing a textbox now inspect
its direct body text runs: a heading can legitimately anchor floating shapes.

## Independent frozen inputs

[The plan](cloud-word-iteration19-plan.json) bounded this batch to six PDFs,
18 baseline plus22 final HTTP contracts, including two final edit/reopen chains.
PyMuPDF1.26.6 generated actual text PDFs independently of the application's
PDFBox3.0.8. The final manifest SHA-256 is
`00d11cbe3093a3f7915f1076d8e85cbab4eddeeebfa145b91cf987b51876886a`.
Every source character inventory and source position was checked before HTTP.

The initial narrow-column layout was too wide; its measured gutter was corrected
to26.657pt before acceptance. Initial font trials were rejected before conversion:
DroidSansFallback lacks Latin digits, and MuPDF's reverse font cmap selected some
compatibility ideographs/nonbreaking hyphens for shared Noto glyphs. The final
producer writes explicit ToUnicode mappings for the intended source Unicode.
All six PDFs then round-tripped their full source character inventory exactly.
These retained generator failures are not application conversion defects.

Source fonts are LiberationSerif-Regular2.1.5, SHA-256
`9caef765d2e891c10dd73658894f01e660fe0c1c83e0bda7d6edf561d8f623d4`,
and NotoSansCJK-Regular2.004 TTC face0, **Noto Sans CJK JP**, SHA-256
`b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a`.
Both are SIL-OFL-1.1; Chinese text here describes language content, not a claim
that the TTC selected its SC face. Exact source lines, intended order, dimensions,
PDF hashes and font records are retained with the corpus.

## Measured content, layout and editability

CER is case/whitespace-normalized against independent frozen text and intended
reading order. These PDFs contain native text; no injected OCR response or OCR
accuracy conclusion is involved. All six source/Word/Office character inventories
are exact before and after. Nonzero CER below is an ordering defect, not lost text.

|Sample|Source/Office pages|DOCX XML-order CER before → after|Raw Office PDF extraction CER before → after|
|---|---:|---:|---:|
|English continuous paragraph|2/2|0% → 0%|0% → 0%|
|Chinese continuous paragraph|2/2|0% → 0%|0% → 0%|
|Bilingual headers/footers|2/2|0% → 0%|0% → 0%|
|Wide columns and spanning headings|1/1|19.101% → **0%**|65.730% → 65.730%|
|26.657pt narrow gutter|1/1|29.426% → 29.426%|29.426% → 29.426%|
|Bilingual numeric/date/ID table|1/1|0% → 0%|0% → 0%|

The two continuous documents each remain one editable body paragraph. The
header/footer control keeps28 nonempty body paragraphs and two pages; it does
not claim semantic Word header/footer stories or cross-page joining around them.
The table has exactly five rows/four real editable cells per row, including
`AC-00643`, dates, `-417.85`, `.95`, `1,029.05` and `-0.65` in their original cells.
The wide-column file retains ten editable frames. Its original text objects are
reordered in XML without duplication or hidden substitute text. No sample
becomes a page image.

Across all nine source pages, before/after Office output has identical page
sizes, original word text/boxes and rendered72dpi pixels. Every floating frame's
XML is exact. Source/Office preview images were also inspected. Source-to-Office
font substitution and positioning differences already exist (including up to
51.151pt in a matched text bounding-box edge); this equality is **not** a claim
of source-perfect visual reproduction.

Raw Office PDF extraction still paints/returns floating text separately from
normal body text. The application's separate PDF→TXT reader returns the wide
case in correct order before and after (0% CER); that does not prove the raw
Office order is fixed. Narrow columns remain interleaved through all measured
outputs. The existing gutter threshold was not relaxed based on one fixture.
Native Microsoft Word selection/copy behavior remains unrun; the demonstrated
improvement is the DOCX document sequence.

Two actual edits were uploaded through HTTP and reopened by Office:

- Appending35 synthetic review sentences to the single English paragraph grows
  two pages to four naturally. All original/appended text survives, with no
  blank page; final PDF→TXT also matches the edited truth.
- Changing only the table cell `.95` to `.96` keeps one page, a real table cell,
  and every other value unchanged in DOCX, Office PDF and final TXT.

## Tests, process evidence and cost

The new two-regression baseline run fails twice with no error/skip. After the
fix, **53 focused tests pass without skips**, including column headings/tails,
cross-page English/Chinese/editing, table geometry/rotation, paragraph boundaries
and OCR overlays/masks. A new clean package build for this production change
runs **423 tests:422 pass, one optional real signed-OFD fixture skip**, zero
failure/error. `qa-samples/input/ofdrw-invoice.ofd` was not supplied. Bundled OCR
and actual Office tests execute. UI/desktop suites were not rerun locally.
This is a new iteration19 build; iteration18 still did not rerun its historical
419-test local package acceptance, and its controlled CER remains non-native.

All40 conversion contracts succeeded:18 baseline,22 final including edits.
The final harness command exited1 on its observer count assertion after all
downloads completed:23 matching-command PIDs were observed, but PID46150 was a
child of Worker46124, not of backend45347. Immutable supervision receipts identify
exactly22 direct backend Workers, matching22 contracts; baseline has18. A nested
matching command is not proof of an additional backend JVM. The original report,
stderr and measured runner source are retained, along with the explicit
attribution replay. No matrix was repeated to hide this harness failure.

The public runner now filters direct backend children and marks its report
failed before saving a final observer mismatch. That small observer correction
was checked against the retained PID/parent inventory; the corrected live loop
was not rerun. The supervision helper itself is unchanged. Both runs reached
ECHILD, all registered new process identities are absent, and no new global
zombie was observed. The historical ten audit zombies remain untouched.

|Single pass|Baseline18 contracts|Final22 contracts including edits|
|---|---:|---:|
|Wall seconds|53.091|67.069|
|Child CPU user + system seconds|138.972 +10.433|175.497 +13.015|
|Maximum child RSS KiB|518036|490184|

These are unequal workloads including startup, not a performance improvement
claim. Child maximum RSS is not summed concurrent memory. Per-contract120s,
suite480s and teardown15s bounds remain. Runtime: Temurin17.0.16+8,
Maven3.9.11, Python3.12.14, PyMuPDF1.26.6, Linux6.18.44; actual explicit
LibreOfficeDev26.8.0.0.alpha0 commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5.
OCR is disabled for the born-digital HTTP batch; earlier bundled model pins are
unchanged and are not replaced by these results.

Accepted new JAR SHA-256:
`db957453eee857d659f5aef6fe0196385226bd2e3520ea4b0a3520a8ed6db443`.
Build-input fingerprint:
`a0314d0cec38ca537db3732417961cfe82bb571d6caffc3df9d783a33f04b320`.
All223 application classes match clean build targets. Eight class files belonging
to the two renderer sources (including nested/debug-line changes) differ; all
other215 classes match the immutable baseline. Final committed-source binding
and exact-SHA CI are recorded in the draft PR/review packet.

## Reproduction and limits

With pinned fonts/PyMuPDF and Java/Office active, generate into a fresh directory
using `python3 qa-samples/generate_word_iteration19.py`. Confirm source truth and
hashes before measuring. Run `run_word_iteration19.py --jar <immutable-jar>
--out <fresh-output>`; add `--edits` only for the final set. It uses the checked-in
Linux supervisor and an ephemeral authenticated localhost server. Preserve its
raw result even if a final QA assertion fails.

`summarize_word_iteration19.py --folder <output>` reads existing artifacts.
`compare_word_iteration19.py` uses fixed `report/iteration19-before`/`after`
paths and the recorded work receipts to verify pairs and write
[machine-readable evidence](cloud-word-iteration19-results.json). It starts no
server or OCR/Office process. Review artifacts exclude private server logs,
tokens and task storage.

Remaining high-value targets are narrow/mixed-column reading intent, Office PDF
floating-text extraction order, and source font-family fidelity. These need
independent positive/negative evidence before new behavior is adopted. Arbitrary
real/vendor PDFs, native Microsoft Word, general reflow/rotation and native
macOS/Windows installers are unrun. No merge, release or installer update occurs.
