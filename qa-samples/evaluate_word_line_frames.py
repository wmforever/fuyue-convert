#!/usr/bin/env python3
"""Measure rejected line-frame candidates on frozen synthetic API/Office evidence.

Uses actual Office PDF export, default Poppler extraction, matching visible PDF
glyph centers and raster differences outside unchanged word masks. This is an
offline experiment, not production adoption or a replacement for HTTP acceptance.
"""
import argparse
from collections import Counter
import hashlib
import html
import json
import math
from pathlib import Path
import resource
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

import fitz
import numpy as np

from experiment_word_line_frames import NS, pt, style, transform
from verify_cloud_ocr import metrics

CASES = ['en-mono-shadow.png', 'en-mono-uniform.png', 'en-serif-uniform.png',
         'zh-cjk-uniform.png', 'english-tilt-+0.png', 'english-tilt-+6.png',
         'en-sans-columns4.png', 'zh-droid-columns2.png']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def xml_evidence(path):
    with zipfile.ZipFile(path) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
        media = {name: hashlib.sha256(archive.read(name)).hexdigest()
                 for name in archive.namelist() if name.startswith('word/media/')}
        masks = [ET.tostring(shape) for shape in root.findall('.//v:rect', NS)
                 if shape.get('id', '').startswith('ocr-mask-')]
        text = ''.join(t.text or '' for t in root.findall('.//w:t', NS))
    return root, {'media': media, 'masks': masks, 'text': text}


def geometry(path):
    with fitz.open(path) as document:
        chars = [(char['c'], tuple(char['bbox'])) for page in document
                 for block in page.get_text('rawdict')['blocks'] if 'lines' in block
                 for line in block['lines'] for span in line['spans'] for char in span['chars']
                 if not char['c'].isspace()]
        pixmap = document[0].get_pixmap(matrix=fitz.Matrix(150 / 72, 150 / 72), alpha=False)
        rgb = np.frombuffer(pixmap.samples, dtype=np.uint8).reshape(pixmap.height, pixmap.width, 3).copy()
        pages = len(document)
    return chars, rgb, pages


def shifts(before, after):
    # Match each visible character once to its nearest equal baseline character.
    # Reject missing inventories rather than assigning a successful geometry score.
    if Counter(char for char, _ in before) != Counter(char for char, _ in after):
        return {'inventoryPreserved': False, 'maxCenterShiftPt': None, 'medianCenterShiftPt': None}
    remaining = list(after)
    distances = []
    for char, box in before:
        options = [(math.hypot((box[0] + box[2] - candidate[0] - candidate[2]) / 2,
                              (box[1] + box[3] - candidate[1] - candidate[3]) / 2), index)
                   for index, (value, candidate) in enumerate(remaining) if value == char]
        distance, index = min(options)
        remaining.pop(index)
        distances.append(distance)
    return {'inventoryPreserved': True, 'maxCenterShiftPt': max(distances, default=0),
            'medianCenterShiftPt': sorted(distances)[len(distances) // 2] if distances else 0}


def outside_masks(before, after, root):
    if before.shape != after.shape:
        return {'sameRasterSize': False}
    mask = np.zeros(before.shape[:2], dtype=bool)
    scale = 150 / 72
    for shape in root.findall('.//v:rect', NS):
        if not shape.get('id', '').startswith('ocr-mask-'):
            continue
        properties = style(shape)
        x, y = pt(properties, 'margin-left'), pt(properties, 'margin-top')
        right, bottom = x + pt(properties, 'width'), y + pt(properties, 'height')
        # One raster pixel accommodates edge antialiasing; no broad line union mask.
        x0, y0 = max(0, math.floor(x * scale) - 1), max(0, math.floor(y * scale) - 1)
        x1, y1 = min(mask.shape[1], math.ceil(right * scale) + 1), min(mask.shape[0], math.ceil(bottom * scale) + 1)
        mask[y0:y1, x0:x1] = True
    changed = np.max(np.abs(before.astype(np.int16) - after.astype(np.int16)), axis=2) > 8
    return {'sameRasterSize': True, 'changedPixels': int(np.count_nonzero(changed)),
            'changedPixelsOutsideOriginalWordMasks': int(np.count_nonzero(changed & ~mask))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--reaper', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    report = json.loads((args.baseline / 'report.json').read_text())
    assert 'synthetic' in report['manifest']['provenance'].lower()
    cases = {case['file']: case for case in report['cases']}
    truths = {case['file']: '\n'.join(case['expectedLines']) for case in report['manifest']['cases']}
    result = {'baselineArtifact': json.loads((args.baseline / 'artifact-provenance.json').read_text()),
              'office': subprocess.check_output(['soffice', '--version']).decode().strip(),
              'poppler': subprocess.run(['pdftotext', '-v'], capture_output=True, text=True).stderr.splitlines()[0],
              'pymupdf': fitz.VersionBind, 'geometryReference': 'actual baseline Office PDF, not source glyph ground truth',
              'matchingLimit': 'nearest equal character; inventories must match; raster outside-mask differences measured separately',
              'adopted': False, 'cases': []}
    rows = []
    for name in CASES:
        source = args.baseline / (name + '.scan.docx')
        baseline_pdf = args.baseline / (name + '.scan.pdf')
        root, baseline_xml = xml_evidence(source)
        before, baseline_rgb, pages = geometry(baseline_pdf)
        with fitz.open(baseline_pdf) as document:
            document[0].get_pixmap(matrix=fitz.Matrix(150 / 72, 150 / 72)).save(args.out / (name + '.baseline.png'))
        base_text = subprocess.check_output(['pdftotext', str(baseline_pdf), '-']).decode()
        recognized = cases[name]['text']
        base_metrics = metrics(recognized, base_text)
        for mode in ['positioned', 'flow', 'mono']:
            target = args.out / (name + '.' + mode + '.docx')
            transformed = transform(source, target, mode)
            started = time.monotonic()
            process = subprocess.run(['python3', str(args.reaper), 'soffice',
                                      '-env:UserInstallation=' + (args.out / 'office-profile').resolve().as_uri(),
                                      '--headless', '--convert-to', 'pdf', '--outdir', str(args.out), str(target)],
                                     capture_output=True, text=True, timeout=60)
            assert process.returncode == 0, process.stderr
            pdf = target.with_suffix('.pdf')
            assert pdf.is_file(), process.stdout
            seconds = time.monotonic() - started
            _, candidate_xml = xml_evidence(target)
            assert candidate_xml == baseline_xml, 'Text, per-word masks and source image bytes must be conserved'
            candidate_text = subprocess.check_output(['pdftotext', str(pdf), '-']).decode()
            measured = metrics(recognized, candidate_text)
            after, rgb, after_pages = geometry(pdf)
            positions = shifts(before, after)
            raster = outside_masks(baseline_rgb, rgb, root)
            with fitz.open(pdf) as document:
                document[0].get_pixmap(matrix=fitz.Matrix(150 / 72, 150 / 72)).save(target.with_suffix('.png'))
            record = {'file': name, 'mode': mode, 'transform': transformed, 'baselineDocxSha256': digest(source),
                      'docxSha256': digest(target), 'pdfSha256': digest(pdf), 'xmlTextMasksMediaIdentical': True,
                      'baselineDefaultPdfMetrics': base_metrics, 'defaultPdfMetrics': measured,
                      'baselineDefaultPdfTruthMetrics': metrics(truths[name], base_text),
                      'defaultPdfTruthMetrics': metrics(truths[name], candidate_text),
                      'recognizedTextTruthMetrics': metrics(truths[name], recognized),
                      'numericOrderPreserved': measured['expectedNumbers'] == measured['actualNumbers'],
                      'beforePages': pages, 'afterPages': after_pages, 'officeSeconds': seconds,
                      'glyphGeometry': positions, 'raster': raster}
            result['cases'].append(record)
            rows.append('<tr><td><a href="' + html.escape(name + '.baseline.png') + '">' + html.escape(name)
                        + ' baseline</a></td><td>' + mode + '</td><td>'
                        + f'{base_metrics["cer"]:.2%} → {measured["cer"]:.2%}' + '</td><td>'
                        + str(positions['maxCenterShiftPt']) + '</td><td>'
                        + str(raster.get('changedPixelsOutsideOriginalWordMasks')) + '</td><td><a href="'
                        + html.escape(target.with_suffix('.png').name) + '">Office rendering</a></td></tr>')
            print(json.dumps({'case': name, 'mode': mode, 'changedLines': transformed['changed'],
                              'cer': measured['cer'], 'maxCenterShiftPt': positions['maxCenterShiftPt']}), flush=True)
    # Reopen a genuinely edited candidate; extraction-only checks do not prove editability.
    original = args.out / 'en-mono-shadow.png.positioned.docx'
    edited = args.out / 'edited-positioned.docx'
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(edited, 'w', zipfile.ZIP_DEFLATED) as target:
        edited_xml = ET.fromstring(source.read('word/document.xml'))
        token = next(t for t in edited_xml.findall('.//w:t', NS) if t.text and t.text.strip())
        token.text = 'EDIT007'
        for item in source.infolist():
            target.writestr(item, ET.tostring(edited_xml, encoding='utf-8', xml_declaration=True)
                            if item.filename == 'word/document.xml' else source.read(item))
    edit_process = subprocess.run(['python3', str(args.reaper), 'soffice',
        '-env:UserInstallation=' + (args.out / 'office-profile').resolve().as_uri(),
        '--headless', '--convert-to', 'pdf', '--outdir', str(args.out), str(edited)], capture_output=True, text=True, timeout=60)
    assert edit_process.returncode == 0 and edited.with_suffix('.pdf').is_file()
    edited_text = subprocess.check_output(['pdftotext', str(edited.with_suffix('.pdf')), '-']).decode()
    assert 'EDIT007' in edited_text and 'Delivery' not in edited_text
    result['actualOfficeEditVerified'] = {'replacement': 'EDIT007', 'originalRemoved': True, 'duplicateHiddenLine': False}
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    result['childResources'] = {'peakRssKiB': usage.ru_maxrss, 'userSeconds': usage.ru_utime, 'systemSeconds': usage.ru_stime}
    (args.out / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    (args.out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Rejected Word line-frame experiments</title>'
        '<h1>Offline candidates — none adopted</h1><p>Default extraction and visible glyph placement are distinct. '
        'Reference geometry is prior Office PDF; source media/masks/XML are conserved. Flow/mono intentionally relax word positions.</p>'
        '<table border="1"><tr><th>Case</th><th>Mode</th><th>Default PDF CER</th><th>Max glyph center shift pt</th>'
        '<th>Changed pixels outside word masks</th><th>View</th></tr>' + ''.join(rows) + '</table>')


if __name__ == '__main__':
    main()
