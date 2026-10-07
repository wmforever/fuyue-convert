#!/usr/bin/env python3
"""Freeze changed-artifact controls, or audit OCR order without new OCR/HTTP/Office."""
import argparse
import copy
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as E
from collections import Counter
from pathlib import Path

import fitz
import numpy as np
from PIL import Image
from qa_process_guard import matches
from verify_cloud_ocr import metrics
from verify_scan_tables45 import word, read, sha, ROOT, WORK, W, V, inventory
from verify_numeric35 import packaged


def freeze():
    base_path = ROOT / 'qa-samples/generated/scan-tables45/expected.json'
    plan = read(base_path)
    out = ROOT / 'qa-samples/generated/scan-order45'
    out.mkdir(exist_ok=False)
    for name, source in [('ruled.pdf', WORK / 'iteration45-http/ruled-wrap-result.pdf'),
                         ('columns.pdf', WORK / 'iteration45-http/columns-wrap-result.pdf'),
                         ('ruled.png', base_path.parent / 'ruled.png')]:
        shutil.copyfile(source, out / name)
    plan['sources'] = {p.name: sha(p) for p in out.iterdir()}
    plan['parentManifestSha256'] = sha(base_path)
    plan['actions'] = [dict(id='ruled-direct-word', input='ruled.png', target='docx'),
        dict(id='ruled-scan-word', input='ruled.pdf', target='docx'),
        dict(id='ruled-office', input='@ruled-scan-word', target='pdf'),
        dict(id='ruled-edited-office', input='@ruled-scan-word', target='pdf', edit=plan['cases'][0]['edit']),
        dict(id='ruled-edited-api', input='@ruled-edited-office', target='txt'),
        dict(id='columns-scan-word', input='columns.pdf', target='docx'),
        dict(id='columns-office', input='@columns-scan-word', target='pdf')]
    plan['bounds']['maximumHttpContracts'] = 7
    plan['candidateScope'] = 'PreserveallOCR recognizedsourceorder;native/mixed heuristics/geometry/masks/OCRinput unchanged'
    (out / 'expected.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2) + '\n')


def frame_signatures(xml):
    signatures = []
    for shape in xml.iter(V + 'rect'):
        if shape.find('.//' + W + 'txbxContent') is None:
            continue
        item = copy.deepcopy(shape)
        item.attrib.pop('id', None)  # Emission counters change with copy order.
        signatures.append(E.tostring(item))
    return Counter(signatures)


def final_tsv(out):
    capture = read(out / 'ocr-capture.json')
    assert not capture['errors'] and capture['observerStopped'] and capture['extraOcrInvocations'] == 0
    assert not capture['engineSettingsChanged']
    http = read(out / 'report.json')
    by_task = {c['task']['taskId']: c['case'] for c in http['cases']}
    complete = {}
    for entry in capture['records']:
        assert sha(out / entry['artifact']) == entry['sha256']
        key = (entry['taskId'], entry['source'])
        if key not in complete or entry['bytes'] > complete[key]['bytes']:
            complete[key] = entry
    return {by_task[e['taskId']]: e for e in complete.values()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--freeze', action='store_true')
    args = parser.parse_args()
    if args.freeze:
        freeze()
        print('Frozen7changed-artifact controls from existing immutable sources;0OCR')
        return
    before = WORK / 'iteration45-http'
    after = WORK / 'iteration45-order-http'
    plan = read(ROOT / 'qa-samples/generated/scan-order45/expected.json')
    baseline = read(WORK / 'iteration45-baseline-results.json')
    http = read(after / 'report.json')
    provenance = read(WORK / 'iteration45-provenance.json')
    assert http['status'] == 'completed' and not http['failures'] and not http.get('unsupportedEdits')
    assert len(http['cases']) == len(http['workerIdentities']) == 7
    assert not http['newZombies'] and http['supervision']['waitpidNoChildren']
    assert all(not matches(p) for p in http['workerIdentities'])
    assert http['manifestSha256'] == sha(ROOT / 'qa-samples/generated/scan-order45/expected.json')
    assert http['jarSha256'] == sha(ROOT / 'web-api/target/web-api-0.1.5.jar') == provenance['jarSha256']
    for contract in http['cases']:
        assert contract['task']['status'] == 'SUCCESS'
        assert sha(after / contract['artifact']) == contract['sha256']
    old = packaged(WORK / 'iteration45-before.jar')
    new = packaged(ROOT / 'web-api/target/web-api-0.1.5.jar')
    assert old.keys() == new.keys()
    changed = [p for p in old if old[p] != new[p]]
    owner = 'BOOT-INF/lib/docx-renderer-0.1.5.jar/com/fuyue/formatconverter/docx/FixedLayoutDocxRenderer'
    assert len(changed) == 6 and all(p.startswith(owner) and p.endswith('.class') for p in changed), changed
    # Recompilation also shifts line-number metadata in five nested classes.
    # Verify their actual method bytecode rather than claiming binary identity.
    parity = WORK / 'iteration45-class-parity'
    parity.mkdir(exist_ok=True)
    inner = []
    for path in changed:
        if '$' not in path:
            continue
        dumps = []
        for label, files in [('before', old), ('after', new)]:
            folder = parity / label
            folder.mkdir(exist_ok=True)
            classfile = folder / Path(path).name
            classfile.write_bytes(files[path])
            dump = subprocess.check_output(['javap', '-c', '-p', str(classfile)])
            (folder / (classfile.name + '.javap.txt')).write_bytes(dump)
            dumps.append(dump)
        assert dumps[0] == dumps[1], 'Nested method instructions changed: ' + path
        inner.append(dict(path=path, methodBytecodeExact=True,
                          javapSha256=hashlib.sha256(dumps[0]).hexdigest()))
    tsv_before, tsv_after = final_tsv(before), final_tsv(after)
    assert tsv_after.keys() == tsv_before.keys()
    assert all(tsv_after[k]['sha256'] == tsv_before[k]['sha256'] for k in tsv_after)
    word_rows = []
    for name in ['ruled-direct-word', 'ruled-scan-word', 'columns-scan-word']:
        parts_a, xml_a, a = word(before / (name + '-result.docx'))
        parts_b, xml_b, b = word(after / (name + '-result.docx'))
        assert Counter(f['text'] for f in a['frames']) == Counter(f['text'] for f in b['frames'])
        assert frame_signatures(xml_a) == frame_signatures(xml_b), 'Geometry/font/z-order/word changes: ' + name
        assert a['masks'] == b['masks'] and a['media'] == b['media']
        assert a['tables'] == b['tables'] == 0
        document_exact = parts_a['word/document.xml'] == parts_b['word/document.xml']
        if name != 'columns-scan-word':
            assert document_exact
        truth = plan['cases'][1]['expectedColumnMajor'] if name.startswith('columns') else plan['cases'][0]['expectedRowMajor']
        word_rows.append(dict(id=name, rawTextMultisetExact=True, sourceImageBytesExact=True,
            masksExact=True, wordGeometryFontTransformAndZOrderExact=True,
            documentXmlByteExact=document_exact, beforeText=a['text'], afterText=b['text'],
            beforeMetrics=metrics(truth, a['text']), afterMetrics=metrics(truth, b['text']),
            afterNumberInventory=inventory(plan['cases'][1 if name.startswith('columns') else 0], b['text']),
            outOfPageFramesBefore=a['outOfPageFrames'], outOfPageFramesAfter=b['outOfPageFrames']))
    column = word_rows[-1]
    # Order matches the actual unchanged Tesseract words, including the known
    # 左栏→A error. It is not asserted to recover the missing Chinese label.
    tsv_column = next(t for t in baseline['nativeTsvs'] if t['contract'] == 'columns-scan-word')
    assert ''.join(column['afterText'].split()) == ''.join(tsv_column['rawWordsText'].split())
    assert column['afterMetrics']['editDistance'] == 2 < column['beforeMetrics']['editDistance']
    api_before = (before / 'ruled-edited-api-result.txt').read_bytes()
    api_after = (after / 'ruled-edited-api-result.txt').read_bytes()
    assert api_before == api_after
    rendering = WORK / 'iteration45-order-render'
    rendering.mkdir(exist_ok=True)
    previous_report = read(ROOT / 'docs/cloud-scan-tables45-results.json')
    new_renders = 0
    pdf_rows = []
    for name in ['ruled-office', 'ruled-edited-office', 'columns-office']:
        path = after / (name + '-result.pdf')
        doc = fitz.open(path)
        assert len(doc) == 1
        png = rendering / (name + '.png')
        if not png.exists():
            doc[0].get_pixmap(dpi=200, alpha=False).save(png)
            new_renders += 1
        else:
            assert previous_report['status'] == 'accepted-recognized-OCR-copy-order-only'
            prior = next(p for p in previous_report['pdfs'] if p['id'] == name)
            assert prior['sha256'] == sha(path) and prior['renderSha256'] == sha(png)
        with Image.open(WORK / 'iteration45-render' / (name + '.png')) as old_image, Image.open(png) as new_image:
            a, b = np.asarray(old_image.convert('RGB')), np.asarray(new_image.convert('RGB'))
        assert a.shape == b.shape
        pixel_changes = int(np.any(a != b, axis=2).sum())
        assert pixel_changes == 0, (name, pixel_changes)
        truth = plan['cases'][1]['expectedColumnMajor'] if name.startswith('columns') else plan['cases'][0]['expectedRowMajor']
        if name == 'ruled-edited-office':
            truth = truth.replace(plan['cases'][0]['edit']['old'], plan['cases'][0]['edit']['new'])
        pdf_rows.append(dict(id=name, pages=1, sha256=sha(path), nativeText=doc[0].get_text(),
            metrics=metrics(truth, doc[0].get_text()), beforeAfterRenderedPixelsExact=True,
            changedPixels=0, renderDpi=200, renderSha256=sha(png), fonts=[f[3] for f in doc[0].get_fonts()]))
        doc.close()
    full_tests = read(WORK / 'iteration45-test-summary.json')['counts']
    assert full_tests == dict(tests=506, failures=0, errors=0, skipped=1)
    report = dict(status='accepted-recognized-OCR-copy-order-only', productionChanged=True,
        baseline=baseline, changedArtifactManifestSha256=http['manifestSha256'],
        sourceBoundJarSha256=http['jarSha256'], productionFingerprint=provenance['buildInputSha256'],
        sourceBoundClasses=provenance['applicationClassCount'], changedPackagedFiles=changed,
        unchangedApplicationClasses=226, changedOwnerAndNestedClasses=6,
        fiveNestedMethodBytecodesExact=inner,
        allOtherPackagedClassesAndResourcesByteExact=True, words=word_rows, pdfs=pdf_rows,
        apiRawByteExact=True, actualBaselineHttp=9, actualChangedArtifactHttp=7,
        totalActualWorkers=16, unchangedNativeTsvs=4, extraDiagnosticOCR=0,
        totalFullRenderedPages=8, changedArtifactFullRenders=3, newRendersThisAudit=new_renders,
        changedArtifactResources=http['resources'], unchangedRawNumericValues=True,
        tableStructureRecovered=False, visibleEditedAmountAccepted=False,
        columnChineseLabelErrorUnchanged=True, allWorkersAbsent=True, ECHILD=True, newZombies=[],
        scope='OnlyallOCRregion sourcezOrder;not a new inference of correct reading intent',
        tests=dict(beforeFocusedFailures=1, afterFocusedPassed=36, fullBuild=full_tests,
                   fullBuildPassed=505, bundledConditionalExecuted=10),
        remaining=['Ruledscan completeness/headerlargeframes/2out-of-pageframes/visibleoldscan overprint',
                   'Actualeditable scannedtable structure and generalcolumnintentinference',
                   'Leftcolumn左栏→A;editedAPI missing07744 andduplication',
                   'Office24.2 darkpapercontrol/nativeMacWindowspackages/MicrosoftWord unrun'])
    (ROOT / 'docs/cloud-scan-tables45-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Verified7changedHTTP;1sourceclass/5bytecode-identicalnestedclasses;4TSVsidentical;columnCER74/199→2/199;3Office renderspixelExact;table/edit stillunaccepted')


if __name__ == '__main__':
    main()
