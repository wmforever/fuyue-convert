#!/usr/bin/env python3
"""Audit the finite scan-table HTTP artifacts; never invoke OCR, Office or HTTP.

A successful audit records failed quality gates rather than treating SUCCESS,
text frames, or the retained raster grid as recovered editable table cells.
"""
import argparse
import csv
import hashlib
import io
import json
import re
import subprocess
import zipfile
import xml.etree.ElementTree as E
from pathlib import Path

import fitz
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from qa_process_guard import matches
from record_cloud_provenance import inputs, fingerprint
from verify_cloud_ocr import metrics

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'qa-samples/work'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
V = '{urn:schemas-microsoft-com:vml}'


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compact(text):
    return ''.join(text.split())


def word(path):
    with zipfile.ZipFile(path) as z:
        parts = {name: z.read(name) for name in z.namelist()}
    xml = E.fromstring(parts['word/document.xml'])
    section = xml.find('.//' + W + 'pgSz')
    bounds = [int(section.get(W + 'w')) / 20, int(section.get(W + 'h')) / 20]
    frames, masks = [], []
    for shape in xml.iter(V + 'rect'):
        style = dict(p.split(':', 1) for p in shape.get('style', '').split(';') if ':' in p)
        box = [float(style[key].removesuffix('pt')) for key in
               ['margin-left', 'margin-top', 'width', 'height']]
        text = ''.join(n.text or '' for n in shape.iter(W + 't'))
        entry = dict(id=shape.get('id'), boxPt=box, zIndex=int(style['z-index']))
        if shape.find('.//' + W + 'txbxContent') is not None:
            frames.append(dict(**entry, text=text))
        elif shape.get('id', '').startswith('ocr-mask'):
            masks.append(dict(**entry, color=shape.get('fillcolor')))
    media = []
    for name, data in parts.items():
        if name.startswith('word/media/'):
            with Image.open(io.BytesIO(data)) as image:
                media.append(dict(part=name, pixels=list(image.size),
                                  sha256=hashlib.sha256(data).hexdigest()))
    inside = [f for f in frames if f['boxPt'][0] < -.01 or f['boxPt'][1] < -.01
              or f['boxPt'][0] + f['boxPt'][2] > bounds[0] + .01
              or f['boxPt'][1] + f['boxPt'][3] > bounds[1] + .01]
    return parts, xml, dict(tables=len(xml.findall('.//' + W + 'tbl')),
        textFrames=len(frames), frames=frames, masks=masks, media=media,
        pageBoundsPt=bounds, outOfPageFrames=inside,
        text='\n'.join(f['text'] for f in frames))


def inventory(case, actual, replacements=None):
    numbers = [item['text'].split()[-1] for item in case['items']
               if item.get('role') in ['cell', 'column'] and item.get('row', 0) > 0
               and re.fullmatch(r'[+-]?\d+(?:[.-]\d+)*', item['text'].split()[-1])]
    numbers = [(replacements or {}).get(value, value) for value in numbers]
    return [dict(value=value, exactOccurrences=len(re.findall(
        r'(?<![\d.+-])' + re.escape(value) + r'(?![\d.])', actual))) for value in numbers]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render-missing', action='store_true',
                        help='On a fresh reproduction only, render missing PDF pages at 200 DPI')
    parser.add_argument('--baseline-jar', type=Path, default=ROOT / 'web-api/target/web-api-0.1.5.jar')
    parser.add_argument('--historical-source', action='store_true',
                        help='Audit old artifacts after a code change against their frozen Git source revision')
    parser.add_argument('--report', type=Path, default=ROOT / 'docs/cloud-scan-tables45-results.json')
    args = parser.parse_args()
    corpus = ROOT / 'qa-samples/generated/scan-tables45'
    out = WORK / 'iteration45-http'
    rendered = WORK / 'iteration45-render'
    rendered.mkdir(exist_ok=True)
    plan, http = read(corpus / 'expected.json'), read(out / 'report.json')
    assert http['status'] == 'completed' and not http['failures']
    assert len(http['cases']) == len(http['workerIdentities']) == 9
    assert http['supervision']['waitpidNoChildren'] and not http['newZombies']
    assert all(not matches(pid) for pid in http['workerIdentities'])
    assert http['manifestSha256'] == sha(corpus / 'expected.json')
    assert http['jarSha256'] == sha(args.baseline_jar)
    assert http['jarSha256'] == '1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b'
    for c in http['cases']:
        assert c['task']['status'] == 'SUCCESS' and sha(out / c['artifact']) == c['sha256']
    for name, digest in plan['sources'].items():
        assert sha(corpus / name) == digest
    assert sha(Path(plan['font']['path'])) == plan['font']['sha256']
    provenance = read(WORK / 'iteration43-provenance.json')
    if args.historical_source:
        actual_inputs = {path: hashlib.sha256(subprocess.check_output(
            ['git', 'show', plan['parentRevision'] + ':' + path], cwd=ROOT)).hexdigest()
            for path in provenance['buildInputs']}
    else:
        actual_inputs = inputs()
    assert actual_inputs == provenance['buildInputs']
    assert fingerprint(actual_inputs) == provenance['buildInputSha256']
    capture = read(out / 'ocr-capture.json')
    assert not capture['errors'] and capture['observerStopped']
    assert capture['extraOcrInvocations'] == 0 and not capture['engineSettingsChanged']
    # The observer can snapshot a header-only file while Tesseract is writing.
    # Count source/task identities, use the largest complete snapshot, retain all.
    complete = {}
    for record in capture['records']:
        path = out / record['artifact']
        assert path.stat().st_size == record['bytes'] and sha(path) == record['sha256']
        key = (record['taskId'], record['source'])
        if key not in complete or record['bytes'] > complete[key]['bytes']:
            complete[key] = record
    assert len(complete) == 4
    native = []
    for record in complete.values():
        rows = list(csv.DictReader((out / record['artifact']).open(), delimiter='\t'))
        page = next(row for row in rows if row['level'] == '1')
        words = [row for row in rows if row['level'] == '5' and row['text'].strip()]
        assert words
        total = sum(len(compact(row['text'])) for row in words)
        mean = sum(float(row['conf']) * len(compact(row['text'])) for row in words) / total
        contract = next(c for c in http['cases'] if c['task']['taskId'] == record['taskId'])
        truth = plan['cases'][1]['expectedColumnMajor'] if contract['case'].startswith('columns') else plan['cases'][0]['expectedRowMajor']
        if contract['case'] == 'ruled-edited-api':
            edit = plan['cases'][0]['edit']
            truth = truth.replace(edit['old'], edit['new'])
        raw_text = '\n'.join(row['text'] for row in words)
        native.append(dict(**record, pixels=[int(page['width']), int(page['height'])],
            characterWeightedConfidencePercent=mean, words=words,
            contract=contract['case'], rawWordsText=raw_text, readingStreamMetrics=metrics(truth, raw_text)))
    ruled, columns = plan['cases']
    word_results = []
    for name, case, truth in [('ruled-direct-word', ruled, ruled['expectedRowMajor']),
                             ('ruled-scan-word', ruled, ruled['expectedRowMajor']),
                             ('columns-scan-word', columns, columns['expectedColumnMajor'])]:
        _, _, result = word(out / (name + '-result.docx'))
        cells = []
        if case['id'] == 'ruled':
            scale = result['pageBoundsPt'][0] / case['pixels'][0]
            for item in case['items'][1:]:
                left, top, right, bottom = [n * scale for n in item['cellBoxPixels']]
                texts = [f['text'] for f in result['frames']
                         if left <= f['boxPt'][0] < right
                         and top <= f['boxPt'][1] + f['boxPt'][3] / 2 < bottom]
                actual = ''.join(texts)
                cells.append(dict(row=item['row'], column=item['column'], expected=item['text'],
                    assignedFrameText=actual, compactExact=compact(actual) == compact(item['text'])))
        word_results.append(dict(id=name, **result, textMetrics=metrics(truth, result['text']),
            numberInventory=inventory(case, result['text']), geometricCellContent=cells,
            exactGeometricSourceCells=sum(c['compactExact'] for c in cells),
            note='Geometric assignment diagnoses text;it does not create logical editable cells'))
    # Prove the one predeclared edit; every other ZIP part must be byte-identical.
    normal_parts, normal_xml, _ = word(out / 'ruled-scan-word-result.docx')
    edited_parts, edited_xml, _ = word(out / 'ruled-edited-office-edited.docx')
    assert normal_parts.keys() == edited_parts.keys()
    assert all(data == edited_parts[name] for name, data in normal_parts.items()
               if name != 'word/document.xml')
    edit = ruled['edit']
    targets = [n for n in normal_xml.iter(W + 't') if edit['old'] in (n.text or '')]
    assert len(targets) == 1 and targets[0].text.count(edit['old']) == 1
    targets[0].text = targets[0].text.replace(edit['old'], edit['new'])
    assert E.tostring(normal_xml, encoding='utf-8', xml_declaration=True) == edited_parts['word/document.xml']
    pdfs, images, new_renders = [], {}, 0
    for name, case in [('ruled-wrap', ruled), ('ruled-office', ruled), ('ruled-edited-office', ruled),
                       ('columns-wrap', columns), ('columns-office', columns)]:
        path = out / (name + '-result.pdf')
        document = fitz.open(path)
        assert len(document) == 1
        page = document[0]
        png = rendered / (name + '.png')
        # Existing full renders are reused, never silently regenerated.
        if not png.exists() and args.render_missing:
            page.get_pixmap(dpi=200, alpha=False).save(png)
            new_renders += 1
        assert png.exists()
        with Image.open(png) as image:
            images[name] = image.convert('RGB')
        truth = case.get('expectedRowMajor', case.get('expectedColumnMajor'))
        if name == 'ruled-edited-office':
            truth = truth.replace(edit['old'], edit['new'])
        pdfs.append(dict(id=name, sha256=sha(path), pages=1, boundsPt=list(page.rect),
            nativeText=page.get_text(), nativeTextMetrics=metrics(truth, page.get_text()),
            sourceImages=[dict(pixels=[i[2], i[3]], colorSpace=i[5]) for i in page.get_images(full=True)],
            renderedPng=str(png.relative_to(ROOT)), renderedSha256=sha(png), renderDpi=200))
        document.close()
    before, after = np.asarray(images['ruled-office']), np.asarray(images['ruled-edited-office'])
    assert before.shape == after.shape
    changed = np.any(before != after, axis=2)
    ys, xs = np.where(changed)
    assert len(xs)
    edit_box = next(i['cellBoxPixels'] for i in ruled['items']
                    if i.get('row') == edit['row'] and i.get('column') == edit['column'])
    outside = changed.copy()
    left, top, right, bottom = edit_box
    outside[top:bottom, left:right] = False
    assert not outside.any()
    api = (out / 'ruled-edited-api-result.txt').read_text()
    edited_truth = ruled['expectedRowMajor'].replace(edit['old'], edit['new'])
    edit_evidence = dict(**edit, oneWordTextNodeOnly=True, otherWordPartsByteExact=True,
        normalToEditedChangedPixels=int(changed.sum()), outsideEditedSourceCellChangedPixels=int(outside.sum()),
        changedBoxPixels=[int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1)],
        apiRaw=api, apiMetrics=metrics(edited_truth, api), editedValuePresent=edit['new'] in api,
        oldValueAbsentFromNativeAndApi=edit['old'] not in api and edit['old'] not in pdfs[2]['nativeText'],
        apiNumberInventory=inventory(ruled, api, {edit['old']: edit['new']}),
        visibleEditAccepted=False,
        visibleEditReason='Manual full/cell render inspection:original 0170.80 and editable 0180.80 overprint;native/API absence is not visible absence')
    # Independent 3-column control: retain actual frame and Office extraction
    # order; do not infer intent or invent a table from numeric completeness.
    col = word_results[2]
    col['idsInFrameOrder'] = re.findall(r'\b(?:00619|01238|02476)\b', col['text'])
    col['columnMajorCompactExact'] = compact(col['text']) == compact(columns['expectedColumnMajor'])
    col['visualRowMajorMetrics'] = metrics(columns['expectedVisualRowMajor'], col['text'])
    contact = Image.new('RGB', (1920, 1530), 'white')
    draw = ImageDraw.Draw(contact)
    label = ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf', 20)
    for index, name in enumerate(images):
        image = images[name].copy()
        image.thumbnail((950, 475))
        x, y = (index % 2) * 960, (index // 2) * 510
        contact.paste(image, (x, y + 30))
        draw.text((x + 8, y + 3), name, fill='black', font=label)
    contact.save(rendered / 'contact-audit.png')
    result = dict(parentRevision=plan['parentRevision'], status='audited-quality-boundary-unaccepted',
        productionChanged=False, manifestSha256=sha(corpus / 'expected.json'), sourceFont=plan['font'],
        sourceBoundJarSha256=http['jarSha256'], productionFingerprint=provenance['buildInputSha256'],
        counts=dict(sourceScans=2, actualHttpContracts=9, independentWorkers=9, sourceTsvIdentities=4,
                    transientSnapshots=len(capture['records']), extraDiagnosticOCR=0,
                    newOfficeConversions=3, fullRenderedPages=5, auditNewFullRenders=new_renders,
                    editedWordTextNodes=1),
        words=word_results, nativeTsvs=native, pdfs=pdfs, edit=edit_evidence,
        sourceScanResolutionPreserved=False,
        scanWordRetainsFullRenderedBackground=True, rasterGridIsNotEditableCellStructure=True,
        ruledTableRecovered=False, unruledControlNoFalseTable=col['tables'] == 0,
        resources=http['resources'], contractSeconds=[dict(id=c['case'], seconds=c['seconds']) for c in http['cases']],
        warnings=[dict(id=c['case'], warnings=c['task'].get('warnings', [])) for c in http['cases']],
        allWorkersAbsent=True, ECHILD=True, newZombies=[],
        versions=dict(python='3.12.14', pillow='12.3.0', numpy='2.3.5', pymupdf=fitz.VersionBind,
            java='Temurin17.0.16+8', tesseract='5.5.2', leptonica='1.87.0', pdfbox='3.0.8', poi='5.4.1',
            models='tessdata_fast87416418657359cb625c412a48b6e1d6d41c29bd chi_sim+eng PSM3',
            office='26.8.0.0.alpha0+ 2c87e51eeaa2b413ff4ae097b2705eea1995d8e5'),
        unrun=['No production or DPI/PSM/threshold/deskew candidate, no new OCR diagnostics',
               'Direct image Word not reopened with Office;no additional HTTP',
               'No scanned editable-table renderer, row insertion, arbitrary edit or automatic column-intent recovery',
               'Native macOS/Windows packages and Microsoft Word unrun;optional signed-OFD fixture unavailable'],
        localBuild='Production unchanged40:505total/504pass/1optional signedfixture skip;10bundled tests executed;no fresh45fullbuild')
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('Audited9SUCCESS/4completeTSVs/5existingrenders/oneedit;scanned table and visible edit UNACCEPTED;0extraOCR')


if __name__ == '__main__':
    main()
