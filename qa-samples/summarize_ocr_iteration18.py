#!/usr/bin/env python3
"""Read existing controlled OFD artifacts; never start a server or repeat OCR."""
import collections
import copy
import hashlib
import io
import json
import math
import pathlib
import xml.etree.ElementTree as ET
import zipfile

import fitz
import numpy as np
from PIL import Image
from verify_cloud_ocr import metrics
from verify_partial_word_preservation import NS, shapes, signature, rectangle, overlap
from verify_ocr_iteration18_models import verify

ROOT = pathlib.Path(__file__).resolve().parents[1]
HTTP = ROOT / 'qa-samples/report/iteration18-http'
CORPUS = ROOT / 'qa-samples/generated/cloud-iteration18'


def load(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def word_pages(path):
    frames, masks, media = shapes(path)
    with zipfile.ZipFile(path) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
    fi = mi = 0
    pages = []
    for number, paragraph in enumerate(root.find('w:body', NS).findall('w:p', NS), 1):
        if number > 1:
            assert paragraph.find('w:pPr/w:pageBreakBefore', NS) is not None
        page_frames, page_masks = [], []
        for node in paragraph.findall('.//v:rect', NS):
            if node.find('v:textbox', NS) is not None:
                value = copy.deepcopy(frames[fi]); fi += 1; page_frames.append(value)
            else:
                value = copy.deepcopy(masks[mi]); mi += 1; page_masks.append(value)
            # New preceding frames legitimately change absolute stacking indices.
            # All other attributes and original relative order remain asserted.
            value['style'].pop('z-index', None)
        pages.append((page_frames, page_masks))
    assert fi == len(frames) and mi == len(masks)
    return pages, media


def preserved(previous, current):
    old = collections.Counter(map(signature, previous))
    new = collections.Counter(map(signature, current))
    assert all(new[key] == count for key, count in old.items()), 'Lost/changed/duplicated original shape'
    assert [signature(v) for v in current if signature(v) in old] == list(map(signature, previous)), 'Original relative order changed'
    return [v for v in current if signature(v) not in old]


def verify_word(layout):
    before = HTTP / (layout + '-control') / (layout + '.ofd.docx')
    after = HTTP / (layout + '-treatment') / (layout + '.ofd.docx')
    a, media = word_pages(before); b, new_media = word_pages(after)
    assert media == new_media and len(media) == 4
    source_rgb = []
    for name in ['full', 'partial', 'reject', 'low']:
        with Image.open(CORPUS / (name + '.png')) as im:
            source_rgb.append(hashlib.sha256(im.convert('RGB').tobytes()).hexdigest())
    with zipfile.ZipFile(after) as archive:
        actual_rgb = []
        for name in new_media:
            with Image.open(io.BytesIO(archive.read(name))) as im:
                actual_rgb.append(hashlib.sha256(im.convert('RGB').tobytes()).hexdigest())
        assert collections.Counter(source_rgb) == collections.Counter(actual_rgb)
    checks = []
    with fitz.open(str(before) + '.pdf') as old_pdf, fitz.open(str(after) + '.pdf') as new_pdf:
        assert len(a) == len(b) == len(old_pdf) == len(new_pdf)
        for index, ((old_frames, old_masks), (new_frames, new_masks)) in enumerate(zip(a, b)):
            added_frames = preserved(old_frames, new_frames)
            added_masks = preserved(old_masks, new_masks)
            assert len(added_frames) == len(added_masks) == (8 if index == 0 else 0)
            assert all(overlap(rectangle(mask), rectangle(frame)) == 0
                       for mask in added_masks for frame in old_frames)
            for mask in added_masks:
                x, y, w, h = rectangle(mask)
                assert any(x >= fx - .5 and y >= fy - .5 and x+w <= fx+fw+.5 and y+h <= fy+fh+.5
                           for fx, fy, fw, fh in map(rectangle, added_frames))
            one, two = old_pdf[index], new_pdf[index]
            assert one.rect == two.rect
            # PDF block/line indices can shift when new lines are inserted.
            old_words = [list(w[:5]) for w in one.get_text('words')]
            new_words = [list(w[:5]) for w in two.get_text('words')]
            assert [w for w in new_words if w in old_words] == old_words
            assert all(new_words.count(w) == old_words.count(w) for w in old_words)
            assert collections.Counter(w[4] for w in new_words) == collections.Counter(f['text'].strip() for f in new_frames)
            pix_a, pix_b = one.get_pixmap(alpha=False), two.get_pixmap(alpha=False)
            assert (pix_a.width, pix_a.height, pix_a.n) == (pix_b.width, pix_b.height, pix_b.n)
            pixels_a = np.frombuffer(pix_a.samples, dtype=np.uint8).reshape(pix_a.height, pix_a.width, pix_a.n)
            pixels_b = np.frombuffer(pix_b.samples, dtype=np.uint8).reshape(pix_b.height, pix_b.width, pix_b.n)
            different = np.any(pixels_a != pixels_b, axis=2)
            allowed = np.zeros(different.shape, dtype=bool)
            for shape in added_frames + added_masks:
                x, y, w, h = rectangle(shape)
                # Fixed six-point margin covers font ascenders/antialiasing outside VML bounds.
                allowed[max(0, math.floor(y-6)):math.ceil(y+h+6),
                        max(0, math.floor(x-6)):math.ceil(x+w+6)] = True
            outside = int(np.count_nonzero(different & ~allowed))
            assert outside == 0, (layout, index, outside)
            checks.append({'page': index+1, 'originalFrames': len(old_frames), 'newFrames': len(new_frames),
                'originalMasks': len(old_masks), 'addedMasks': len(added_masks),
                'originalFramesMasksRelativeOrderExactExceptAbsoluteZIndex': True,
                'addedMasksDisjointFromOriginalFrames': True, 'maskContainmentTolerancePt': .5,
                'officeOriginalWordsBoxesRelativeOrderExact': True,
                'officeChangedPixels': int(np.count_nonzero(different)),
                'officeChangedPixelsOutsideAddedRegions': outside, 'allowedRenderMarginPt': 6})
    return {'layout': layout, 'originalMediaBytesAndRgbExact': True, 'pages': checks}


def main():
    raw = load(HTTP / 'report.json'); truth = load(CORPUS / 'expected.json')
    assert raw['status'] == 'completed' and not raw['failures']
    assert len(raw['cases']) == len(set(raw['observedWorkerPids'])) == 16
    assert sha(CORPUS / 'expected.json') == raw['fixtureManifestSha256']
    assert sha(ROOT / 'qa-samples/qa_process_guard.py') == raw['qaSupervisorSourceSha256']
    for name, digest in truth['files'].items():
        assert sha(CORPUS / name) == digest
    model_checks = []
    for layout in ['same', 'multi']:
        for mode in ['control', 'treatment']:
            model_dir = ROOT / 'qa-samples/work/iteration18-models' / layout / mode
            model_checks.append(verify(model_dir, layout, mode))
            model = load(model_dir / 'model.json')
            expected_warnings = [w for p in model['pages'] for w in p['warnings']]
            expected_words = collections.Counter(w['text'] for p in model['pages']
                for block in p['blocks'] for w in block['ocrWords'])
            cases = [c for c in raw['cases'] if c['artifact'].startswith(layout+'-'+mode+'/')]
            assert len(cases) == 4
            text_case = next(c for c in cases if c['file'].endswith('.ofd') and c['target'] == 'txt')
            for case in cases:
                assert case['success'] and case['task']['status'] == 'SUCCESS'
                assert sha(HTTP / case['artifact']) == case['artifactSha256']
                assert case['task']['files'][0]['pageCount'] == len(model['pages'])
                if case['file'].endswith('.ofd'):
                    assert case['task']['warnings'] == expected_warnings
                if case['target'] == 'txt':
                    assert case['text'] == (HTTP / case['artifact']).read_text()
                    assert case['text'] == text_case['text']
                    assert collections.Counter(case['text'].split()) == expected_words
                if case['target'] == 'docx':
                    frames, _, _ = shapes(HTTP / case['artifact'])
                    assert collections.Counter(f['text'].strip() for f in frames) == expected_words
                text = case.get('text', case.get('editableText', ''))
                if text:
                    assert '0.95' not in text and 'ID80424' not in text and 'corrected' not in text
                    assert all(token in text for token in ['FULL03121', '.95', 'ID00424', 'LOW00424'])
                    assert ('00643' in text) == ('00817' in text) == (mode == 'treatment')
            receipt = raw['supervision'][layout+'-'+mode]
            assert receipt['status'] == 'reaped' and receipt['waitpidNoChildren']
            assert not raw['newZombieIdentities'][layout+'-'+mode]
    per_image = []
    # Frozen source labels were rendered from selectedRows before any measurement.
    # Per-image scores avoid inventing a document reading order for the quadrant layout.
    actual_models = {mode: load(ROOT / 'qa-samples/work/iteration18-models/same' / mode / 'model.json')
                     for mode in ['control', 'treatment']}
    for index, (name, rows) in enumerate(truth['selectedRows'].items(), 1):
        expected = ' '.join(r[11] for r in rows)
        actual = {mode: ' '.join(w['text'] for block in model['pages'][0]['blocks']
                  if block['id'].endswith('-i'+str(index)) for w in block['ocrWords'])
                  for mode, model in actual_models.items()}
        per_image.append({'image': name, 'sourceTruth': expected,
            'control': metrics(expected, actual['control']),
            'treatment': metrics(expected, actual['treatment'])})
    gates = [load(ROOT / ('qa-samples/work/'+name+'/results.json'))
             for name in ['iteration18-harness-gate', 'iteration18-harness-resistant']]
    assert [len(g['cases']) for g in gates] == [2, 1]
    for gate in gates:
        assert not gate['failures'] and gate['oldTenUnchanged']
        for case in gate['cases']:
            assert case['allRegisteredPidRecordsAbsent'] and not case['newZombieIdentities']
            assert case['unrelatedSidecarAlive'] and case['supervision']['waitpidNoChildren']
    assert gates[1]['cases'][0]['resistantRootForcedAndReaped']
    pinned = load(ROOT / 'docs/cloud-ocr-iteration14-results.json')
    result = {'scope': 'Controlled route/selection/warning acceptance; not native accuracy or new production behavior',
        'productionSourceRevision': raw['productionSourceRevision'], 'jarSha256': raw['jarSha256'],
        'http': raw, 'models': model_checks, 'word': [verify_word(v) for v in ['same', 'multi']],
        'perImageControlledMetrics': per_image, 'fixtureManifest': truth,
        'harnessGates': gates, 'freshExport': load(ROOT / 'qa-samples/work/iteration18-fresh-export.json'),
        'packagedClasspathCompilation': load(ROOT / 'qa-samples/work/iteration18-packaged-classpath/result.json'),
        'runtime': {k: pinned[k] for k in ['versions', 'hashes']},
        'newFocusedTests': {'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0},
        'limits': ['Injected OCR confidence is not native accuracy or completeness',
            'One HTTP pass: child maximum RSS is not summed concurrent memory; no speed claim',
            'No repeated previous matrices or fresh full-package run; production JAR unchanged',
            'External SIGKILL/OOM of supervisor, arbitrary detached races, native installers and Microsoft Word unrun']}
    xml = ROOT / 'task-service/target/surefire-reports/TEST-com.fuyue.formatconverter.task.OfdOcrPartialWarningsTest.xml'
    suite = ET.parse(xml).getroot()
    assert {k: int(suite.attrib[k]) for k in ['tests', 'failures', 'errors', 'skipped']} == result['newFocusedTests']
    result['focusedTestXmlSha256'] = sha(xml)
    (ROOT / 'docs/cloud-ocr-iteration18-results.json').write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':'))+'\n')
    print(json.dumps({'contracts': 16, 'word': result['word'], 'metrics': per_image}, ensure_ascii=False))


if __name__ == '__main__':
    main()
