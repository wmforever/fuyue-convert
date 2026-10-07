#!/usr/bin/env python3
"""Edit a synthetic scan Word frame and reopen it through authenticated HTTP.

Use run_cloud_ocr.py --scan-edit-source for ephemeral auth, worker observation
and resource accounting. This probe expects the frozen bilingual Warehouse case;
it does not certify arbitrary edits, reflow, native Office or other documents.
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
import time
import urllib.request
import zipfile

import fitz
from PIL import Image, ImageChops
from verify_partial_word_preservation import shapes, signature


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    token = os.environ['FORMAT_CONVERTER_API_TOKEN']

    def request(path, data=None, headers=None):
        with urllib.request.urlopen(urllib.request.Request(args.base_url+path, data=data,
                headers={'X-Format-Converter-Token': token, **(headers or {})}), timeout=30) as response:
            return response.read()

    def convert(name, data):
        boundary = 'qa-'+secrets.token_hex(16)
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\npdf'
                f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"'
                '\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
        start = time.monotonic()
        task = json.loads(request('/api/tasks', body, {'Content-Type': 'multipart/form-data; boundary='+boundary}))
        path = '/api/tasks/'+task['taskId']
        while time.monotonic()-start < 120:
            task = json.loads(request(path))
            if task['downloadReady']:
                return request(path+'/download'), round(time.monotonic()-start, 3)
            assert task['status'] not in ('FAILED', 'CANCELLED'), 'Synthetic edit Office task failed'
            time.sleep(.2)
        raise TimeoutError('Synthetic edit Office task exceeded120 seconds')

    original = args.source.read_bytes()
    edited = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(edited, 'w') as target:
        for member in source.infolist():
            data = source.read(member.filename)
            if member.filename == 'word/document.xml':
                assert data.count(b'Warehouse') == 1, 'Expected one frozen Warehouse frame'
                data = data.replace(b'Warehouse', b'Depot', 1)
            target.writestr(copy.copy(member), data)
    edited_path = args.out/'bilingual.edited.scan.docx'
    edited_path.write_bytes(edited.getvalue())
    old_frames, old_masks, old_media = shapes(args.source)
    new_frames, new_masks, new_media = shapes(edited_path)
    assert old_media == new_media, 'Editing changed original scan bytes'
    assert Counter(map(signature, old_masks)) == Counter(map(signature, new_masks))
    expected_frames = copy.deepcopy(old_frames)
    changed = [frame for frame in expected_frames if frame['text'].strip() == 'Warehouse']
    assert len(changed) == 1
    changed[0]['text'] = changed[0]['text'].replace('Warehouse','Depot',1)
    assert Counter(map(signature, expected_frames)) == Counter(map(signature, new_frames)), 'Unrelated frames changed'
    pdfs = []
    seconds = {}
    for name, data in [('original', original), ('edited', edited.getvalue())]:
        pdf, seconds[name] = convert(name+'.docx', data)
        path = args.out/(name+'.pdf')
        path.write_bytes(pdf)
        pdfs.append(path)
    with fitz.open(pdfs[0]) as old, fitz.open(pdfs[1]) as new:
        assert len(old) == len(new) == 1
        a, b = old[0].get_text('words'), new[0].get_text('words')
        old_token = [w for w in a if w[4] == 'Warehouse']
        new_token = [w for w in b if w[4] == 'Depot']
        assert len(old_token) == len(new_token) == 1
        assert not any(w[4] == 'Warehouse' for w in b)
        # Shortening this frame changes the extractor's inferred line IDs.
        # Compare actual text, exact boxes and sequence, excluding those IDs.
        assert [w[:5] for w in a if w[4] != 'Warehouse'] == [w[:5] for w in b if w[4] != 'Depot'], 'Unedited Office words/boxes/order changed'
        numeric = [w[:5] for w in a if re.search(r'\d', w[4])]
        assert numeric == [w[:5] for w in b if re.search(r'\d', w[4])]
        x, y = old[0].get_pixmap(alpha=False), new[0].get_pixmap(alpha=False)
        assert (x.width,x.height) == (y.width,y.height)
        before = Image.frombytes('RGB',(x.width,x.height),x.samples)
        after = Image.frombytes('RGB',(y.width,y.height),y.samples)
        difference = ImageChops.difference(before,after).getbbox()
        assert difference is not None, 'Editing must change the actual Office render'
        envelope = fitz.Rect(old_token[0][:4]) | fitz.Rect(new_token[0][:4])
        assert difference[0] >= envelope.x0-2 and difference[1] >= envelope.y0-2
        assert difference[2] <= envelope.x1+2 and difference[3] <= envelope.y1+2, 'Render changed outside the edited glyph envelope'
        after.save(args.out/'edited.png')
        result = {'scope':'Synthetic scan-frame edit through authenticated HTTP/independent JVM/Office only',
            'sourceSha256':hashlib.sha256(original).hexdigest(), 'edit':'Warehouse→Depot',
            'success':True, 'originalMediaExact':True, 'masksExact':True,
            'allUneditedFramesExact':True, 'uneditedOfficeWordsBoxesAndOrderExact':True,
            'numericLexemesAndBoxesExact':True, 'numericTokens':[w[4] for w in numeric],
            'renderDifferenceBoxPixels':difference, 'editedGlyphEnvelopePt':list(envelope),
            'renderDifferenceTolerancePt':2, 'pages':1, 'seconds':seconds,
            'limits':['One short Latin word edit only; arbitrary edits/reflow and Microsoft Word unrun.',
                'Extractor block/line/word IDs are excluded; actual text, glyph boxes and sequence remain exact.']}
    (args.out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__ == '__main__':
    main()
