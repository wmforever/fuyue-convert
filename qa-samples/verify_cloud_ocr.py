#!/usr/bin/env python3
"""Measure synthetic OCR truth through authenticated HTTP, JVM workers and Office.

Run the handoff generator first. Start the production JAR under a process reaper
in environments whose PID 1 does not reap children. No real documents or tokens
are written into the report. Outputs belong in ignored qa-samples/report/.
"""
import argparse
import copy
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def normalized(text):
    return ''.join(text.lower().split())


def metrics(expected, actual):
    a, b = normalized(expected), normalized(actual)
    # Minimum edit distance with a tie-break toward the most exact character matches.
    previous = [(j, 0) for j in range(len(b) + 1)]
    for i, x in enumerate(a, 1):
        current = [(i, 0)]
        for j, y in enumerate(b, 1):
            options = [(previous[j][0] + 1, previous[j][1]),
                       (current[j - 1][0] + 1, current[j - 1][1]),
                       (previous[j - 1][0] + (x != y), previous[j - 1][1] + (x == y))]
            current.append(min(options, key=lambda item: (item[0], -item[1])))
        previous = current
    distance, matches = previous[-1]
    return {'expectedCharacters': len(a), 'actualCharacters': len(b),
            'editDistance': distance, 'cer': distance / max(1, len(a)),
            'alignedCharacterRecall': matches / max(1, len(a)),
            'exactLines': sum(min(count, b.count(line)) for line, count in
                              Counter(normalized(line) for line in expected.splitlines() if normalized(line)).items()),
            'expectedNumbers': re.findall(r'\d+', expected), 'actualNumbers': re.findall(r'\d+', actual)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--samples', type=Path, default=ROOT / 'qa-samples/generated/cloud-handoff')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    token = os.environ['FORMAT_CONVERTER_API_TOKEN']
    args.out.mkdir(parents=True, exist_ok=True)

    def request(path, data=None, headers=None):
        combined = {'X-Format-Converter-Token': token, **(headers or {})}
        with urllib.request.urlopen(urllib.request.Request(args.base_url + path, data=data,
                                                          headers=combined), timeout=30) as response:
            return response.read()

    def convert(name, data, target):
        boundary = 'qa-' + secrets.token_hex(16)
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\n{target}'
                f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"'
                '\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
        started = time.monotonic()
        task = json.loads(request('/api/tasks', body, {'Content-Type': 'multipart/form-data; boundary=' + boundary}))
        task_path = '/api/tasks/' + task['taskId']
        while time.monotonic() - started < 120:
            task = json.loads(request(task_path))
            if task['downloadReady']:
                return request(task_path + '/download'), round(time.monotonic() - started, 3), task
            if task['status'] in ('FAILED', 'CANCELLED'):
                raise RuntimeError(json.dumps(task, ensure_ascii=False))
            time.sleep(.2)
        raise TimeoutError(task_path)

    manifest = json.loads((args.samples / 'expected.json').read_text())
    report = {'health': json.loads(request('/api/health')), 'manifest': manifest, 'cases': []}
    fonts = ROOT / 'task-service/src/main/resources/fonts'
    report['fontSha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in fonts.glob('*.ttf')}
    for case in manifest['cases']:
        name = case['file']
        data = (args.samples / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == case['sha256'], name
        record = {'file': name}
        try:
            txt, txt_time, txt_task = convert(name, data, 'txt')
            actual = txt.decode('utf-8')
            docx, docx_time, docx_task = convert(name, data, 'docx')
            (args.out / (name + '.docx')).write_bytes(docx)
            with zipfile.ZipFile(io.BytesIO(docx)) as archive:
                document = ET.fromstring(archive.read('word/document.xml'))
                # Only w:t nodes are text, not positioning/VML/style metadata.
                editable = ''.join(n.text or '' for n in document.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'))
                media = [archive.read(n) for n in archive.namelist() if n.startswith('word/media/')]
            edit_result = None
            if name == 'english-tilt-+0.png':
                edited_bytes = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(docx)) as source_zip, zipfile.ZipFile(edited_bytes, 'w') as edited_zip:
                    for member in source_zip.infolist():
                        contents = source_zip.read(member.filename)
                        if member.filename == 'word/document.xml':
                            assert b'Invoice' in contents
                            contents = contents.replace(b'Invoice', b'Receipt', 1)
                        edited_zip.writestr(copy.copy(member), contents)
                edited_pdf, _, _ = convert('edited.docx', edited_bytes.getvalue(), 'pdf')
                edited_path = args.out / 'edited.pdf'
                edited_path.write_bytes(edited_pdf)
                edited_text = subprocess.run(['pdftotext', str(edited_path), '-'], check=True, capture_output=True, text=True).stdout
                edit_result = 'Receipt' in edited_text
                assert edit_result, 'Editing a Word run must survive Office reopening'
            pdf, office_time, _ = convert(name + '.docx', docx, 'pdf')
            pdf_path = args.out / (name + '.pdf')
            pdf_path.write_bytes(pdf)
            reopened = subprocess.run(['pdftotext', str(pdf_path), '-'], capture_output=True, check=True, text=True).stdout
            subprocess.run(['pdftoppm', '-f', '1', '-singlefile', '-scale-to', '1000', '-png', str(pdf_path),
                            str(args.out / name)], check=True, capture_output=True)
            info = subprocess.run(['pdfinfo', str(pdf_path)], check=True, capture_output=True, text=True).stdout
            # PDF scan route retains the scan layer and editable OCR overlays.
            scan, scan_time, _ = convert(name, data, 'pdf')
            scan_docx, scan_docx_time, _ = convert(name + '.pdf', scan, 'docx')
            (args.out / (name + '.scan.docx')).write_bytes(scan_docx)
            with zipfile.ZipFile(io.BytesIO(scan_docx)) as archive:
                scan_xml = ET.fromstring(archive.read('word/document.xml'))
                scan_text = ''.join(n.text or '' for n in scan_xml.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'))
                scan_media = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if n.startswith('word/media/')}
            scan_pdf, scan_office_time, _ = convert(name + '.scan.docx', scan_docx, 'pdf')
            scan_pdf_path = args.out / (name + '.scan.pdf')
            scan_pdf_path.write_bytes(scan_pdf)
            scan_reopened = subprocess.run(['pdftotext', str(scan_pdf_path), '-'], check=True, capture_output=True, text=True).stdout
            subprocess.run(['pdftoppm', '-f', '1', '-singlefile', '-scale-to', '1000', '-png', str(scan_pdf_path),
                            str(args.out / (name + '.scan'))], check=True, capture_output=True)
            with Image.open(io.BytesIO(data)) as source_image, Image.open(args.out / (name + '.scan.png')) as rendered_image:
                source_rgb = source_image.convert('RGB')
                rendered_rgb = rendered_image.convert('RGB')
                # Synthetic fixtures leave this margin free of text and masks.
                source_probe = source_rgb.getpixel((int(source_rgb.width * .02), int(source_rgb.height * .02)))
                rendered_probe = rendered_rgb.getpixel((int(rendered_rgb.width * .02), int(rendered_rgb.height * .02)))
                probe_delta = max(abs(a - b) for a, b in zip(source_probe, rendered_probe))
            assert scan_media and scan_text, 'Scanned Word must contain both scan media and editable text'
            assert probe_delta <= 12, 'Office lost or altered the unmasked synthetic scan margin'

            record.update(scan={'editableText': scan_text, 'mediaSha256': scan_media,
                                'sourceMarginRgb': source_probe, 'renderedMarginRgb': rendered_probe,
                                'marginMaxChannelDelta': probe_delta,
                                'metrics': metrics('\n'.join(case['expectedLines']), scan_text),
                                'officeMetrics': metrics('\n'.join(case['expectedLines']), scan_reopened),
                                'seconds': {'pdf': scan_time, 'docx': scan_docx_time, 'office': scan_office_time}})
            record.update(success=True, text=actual, metrics=metrics('\n'.join(case['expectedLines']), actual),
                          seconds={'txt': txt_time, 'docx': docx_time, 'office': office_time},
                          warnings=txt_task.get('warnings', []), editableText=editable,
                          editableMatchesTxt=normalized(editable) == normalized(actual), officeEditVerified=edit_result,
                          imageExtractionMediaCount=len(media), officeText=reopened,
                          officeMetrics=metrics('\n'.join(case['expectedLines']), reopened),
                          pages=int(re.search(r'^Pages:\s+(\d+)', info, re.M).group(1)),
                          docxBytes=len(docx), pdfBytes=len(pdf))
        except Exception as error:
            record.update(success=False, error=str(error))
        report['cases'].append(record)
        (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(name, record.get('success'), record.get('metrics'), flush=True)
    if not all(c['success'] for c in report['cases']):
        raise SystemExit('One or more conversions failed; inspect report.json')


if __name__ == '__main__':
    main()
