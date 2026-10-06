#!/usr/bin/env python3
"""Compare TXT with the visible runs and original OCR line IDs of a frozen Word file.

For standalone generated OCR documents only. Mixed/native documents need separate
truth; this audit refuses to ignore text outside the recognized frames. It measures
extraction fidelity, not recognition completeness against the original scan.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
V = '{urn:schemas-microsoft-com:vml}'


def verify(docx, text):
    with zipfile.ZipFile(docx) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
    boxes = list(root.iter(W + 'txbxContent'))
    frames = [n for n in root.iter(V + 'rect') if n.find(V + 'textbox/' + W + 'txbxContent') is not None]
    assert frames and len(frames) == len(boxes), 'Unrecognized textboxes require separate truth'
    frame_texts = [n for box in boxes for n in box.iter(W + 't')]
    assert len(frame_texts) == len(list(root.iter(W + 't'))), 'Native/mixed text requires separate truth'
    lines = []
    line_key = None
    words = []
    for frame in frames:
        name = frame.attrib['id']
        assert name.startswith('text-ocr-p') and '-word-' in name, 'Unrecognized OCR line ID'
        key = name.split('-word-')[0]
        if key != line_key:
            if line_key is not None:
                lines.append(''.join(words).rstrip())
            line_key = key
            words = []
        box = frame.find(V + 'textbox/' + W + 'txbxContent')
        value = []
        for node in box.iter():
            if node.tag == W + 't': value.append(node.text or '')
            elif node.tag == W + 'tab': value.append('\t')
            elif node.tag in (W + 'br', W + 'cr'): value.append(' ')
        words.append(''.join(value))
    lines.append(''.join(words).rstrip())
    expected = ''.join(line + '\n' for line in lines if line.strip())
    actual = text.read_text().replace('\r\n', '\n')
    assert actual == expected, 'TXT differs from visible Word runs or original line boundaries'
    return {'wordSha256': hashlib.sha256(docx.read_bytes()).hexdigest(),
            'textSha256': hashlib.sha256(text.read_bytes()).hexdigest(),
            'frames': len(frames), 'lines': len([s for s in lines if s.strip()]),
            'characters': len(actual), 'exactVisibleRunAndLineMatch': True,
            'recognitionCompletenessMeasured': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docx', type=Path, required=True)
    parser.add_argument('--text', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.docx, args.text)
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if not k.endswith('Sha256')}))


if __name__ == '__main__':
    main()
