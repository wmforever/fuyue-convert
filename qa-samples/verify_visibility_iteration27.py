#!/usr/bin/env python3
"""Assert actual HTTP money integrity, conservative failures and unchanged Word pixels.

Reads frozen evidence only; does not rerun OCR, HTTP or Office conversions.
CER ignores whitespace/case; numeric lexemes and unchanged controls are checked
separately, so wrapped amounts cannot pass merely through normalization.
"""
import hashlib
import json
from pathlib import Path
import fitz
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
from verify_edit_iteration26 import parts
from qa_process_guard import matches

ROOT = Path(__file__).resolve().parents[1]


def load(path): return json.loads(path.read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    before = ROOT / 'qa-samples/report/iteration27-before'
    controls = ROOT / 'qa-samples/report/iteration27-controls-before'
    after = ROOT / 'qa-samples/report/iteration27-after'
    manifest = ROOT / 'qa-samples/generated/visibility-iteration27/expected.json'
    truth = load(ROOT / 'qa-samples/generated/edit-iteration27/expected.json')
    report = load(after / 'report.json'); rows = []
    actions = load(manifest)['actions']
    assert [c['case'] for c in report['cases']] == [c['id'] for c in actions]
    assert report['status'] == 'completed-with-failures'
    expected_failures = {'control-partial', 'control-transparent'}
    failed = {c['case'] for c in report['cases'] if c['task']['status'] != 'SUCCESS'}
    assert failed == expected_failures, failed
    for case in report['cases']:
        if case['case'] in expected_failures:
            assert case['task']['errorCode'] == 'OCR_VISIBILITY_UNCERTAIN', case
            assert not case['task']['downloadReady'] and 'artifact' not in case
        else:
            assert case['task']['downloadReady'] and sha(after / case['artifact']) == case['sha256']
    for source, digest in load(manifest)['sources'].items(): assert sha(manifest.parent / source) == digest
    for c in truth['cases']:
        for edited in [True, False]:
            action = c['id'] + ('-edited-office' if edited else '-office')
            oldpath = (before / (c['id'] + '-edited-text-result.txt') if edited else controls / (action + '-result.txt'))
            old, new = oldpath.read_text(), (after / (action + '-result.txt')).read_text()
            expected = c['editedExpected'] if edited else c['expected']
            assert metrics(expected, new)['cer'] == 0, (action, new)
            assert numeric_tokens(new) == numeric_tokens(expected), (action, new)
            if edited: assert c['old'] not in new and new.count(c['new']) == 1
            if c['id'] == 'dark': assert old == new
            rows.append(dict(case=action, before=metrics(expected, old), after=metrics(expected, new),
                             beforeText=old, afterText=new, numericLexemesExact=True))
    for mode in ['ordinary', 'before-image']:
        name = 'control-' + mode + '-result.txt'
        assert (controls / name).read_bytes() == (after / name).read_bytes()
    opaque = (after / 'control-opaque-result.txt').read_text()
    assert opaque == 'Visible replacement 128.75\n', opaque
    original = (after / 'original-text-result.txt').read_text()
    expected = 'REVIEW RECORD 2036\nRecord 00842\nAmount 128.75\nDate 2036-04-19\n'
    old = (ROOT / 'qa-samples/report/iteration26-final/original-edited-text-result.txt').read_text()
    assert metrics(expected, original)['cer'] == 0 and numeric_tokens(expected) == numeric_tokens(original)
    assert '127.50' not in original and original.count('128.75') == 1
    rows.append(dict(case='original-127.50-to-128.75', before=metrics(expected, old), after=metrics(expected, original),
                     beforeText=old, afterText=original, numericLexemesExact=True))
    word_proofs = []
    for name in ['decimal', 'gray', 'dark']:
        old, new = parts(before / (name + '-word-result.docx')), parts(after / (name + '-word-result.docx'))
        # Core creation timestamps are the only allowed difference.
        assert {k: v for k, v in old.items() if k != 'docProps/core.xml'} == {k: v for k, v in new.items() if k != 'docProps/core.xml'}, name
        word_proofs.append(dict(case=name, allPartsExceptCoreTimestampsExact=True, originalImageAndEditableTextExact=True))
    oldpdf = ROOT / 'qa-samples/report/iteration26-final/original-edited-office-result.pdf'
    newpdf = after / 'original-office-result.pdf'
    with fitz.open(oldpdf) as a, fitz.open(newpdf) as b:
        assert len(a) == len(b) == 1
        assert a[0].get_text('words') == b[0].get_text('words')
        assert a[0].get_pixmap(dpi=300, alpha=False).samples == b[0].get_pixmap(dpi=300, alpha=False).samples
    assert report['supervision']['waitpidNoChildren'] and not report['newZombies']
    assert len(report['workerIdentities']) == len(report['cases']) and all(not matches(r) for r in report['workerIdentities'])
    paired_times = []
    for c in truth['cases']:
        old = next(v for v in load(before/'report.json')['cases'] if v['case'] == c['id'] + '-edited-text')
        new = next(v for v in report['cases'] if v['case'] == c['id'] + '-edited-office')
        paired_times.append(dict(case=c['id'], beforeSeconds=old['seconds'], afterSeconds=new['seconds']))
    result = dict(parentRevision=load(manifest)['parentRevision'], jarSha256=report['jarSha256'],
                  manifestSha256=sha(manifest), rows=rows, expectedFailedNoDownload=sorted(failed),
                  unrelatedScanControlsByteExact=True, fullyHiddenScanReplacedByVisibleNativeOnly=True,
                  wordProofs=word_proofs, originalOffice300DpiPixelsAndNativeWordBoxesExact=True,
                  processProof=dict(workers=len(report['workerIdentities']), allExited=True, ECHILD=True, newZombies=[]),
                  resources=dict(beforeMoney=load(before/'report.json')['resources'], beforeControls=load(controls/'report.json')['resources'], after=report['resources']),
                  pairedEditedPdfToTxtTimings=paired_times,
                  timings=[dict(case=c['case'], seconds=c['seconds'], status=c['task']['status'],
                                warningCodes=sorted({w['code'] for w in c['task'].get('warnings', [])})) for c in report['cases']],
                  helperSha256={p.name: sha(p) for p in [Path(__file__), ROOT/'qa-samples/generate_visibility_iteration27.py', ROOT/'qa-samples/generate_edit_iteration27.py', ROOT/'qa-samples/run_edit_iteration26.py']})
    (ROOT / 'docs/cloud-visibility-iteration27-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(verified=True, requests=len(report['cases']), moneyCases=len(rows), expectedFailures=sorted(failed))))


if __name__ == '__main__': main()
