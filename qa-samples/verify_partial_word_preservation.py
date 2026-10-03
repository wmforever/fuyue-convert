#!/usr/bin/env python3
"""Compare downloaded scan DOCX and actual Office PDF against the safety artifact.

Exact source word frames and masks must survive; new masks must be disjoint from
all prior word frames. This is artifact evidence, not universal layout proof.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET

import fitz

NS = {'v': 'urn:schemas-microsoft-com:vml', 'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}


def shapes(path):
    with zipfile.ZipFile(path) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
        media = {name: hashlib.sha256(archive.read(name)).hexdigest()
                 for name in archive.namelist() if name.startswith('word/media/')}
    text, masks = [], []
    for shape in root.findall('.//v:rect', NS):
        style = dict(part.split(':', 1) for part in shape.attrib['style'].split(';') if ':' in part)
        attrs = {k: v for k, v in shape.attrib.items() if k not in ['id', 'style']}
        words = ''.join(node.text or '' for node in shape.findall('.//w:t', NS))
        properties = [ET.tostring(node).decode() for node in shape.findall('.//w:rPr', NS)]
        value = {'style': style, 'attributes': attrs, 'text': words, 'runProperties': properties}
        (text if shape.find('v:textbox', NS) is not None else masks).append(value)
    return text, masks, media


def signature(shape):
    return json.dumps(shape, sort_keys=True)


def rectangle(shape):
    style = shape['style']
    return [float(style[k].removesuffix('pt')) for k in ['margin-left', 'margin-top', 'width', 'height']]


def overlap(a, b):
    return max(0, min(a[0]+a[2], b[0]+b[2])-max(a[0], b[0])) * max(0, min(a[1]+a[3], b[1]+b[3])-max(a[1], b[1]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--case', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    old_text, old_masks, old_media = shapes(args.before / (args.case + '.scan.docx'))
    new_text, new_masks, new_media = shapes(args.after / (args.case + '.scan.docx'))
    assert old_media == new_media, 'Original source media changed'
    for old, new, label in [(old_text, new_text, 'text frames'), (old_masks, new_masks, 'masks')]:
        previous = collections.Counter(map(signature, old)); current = collections.Counter(map(signature, new))
        assert all(current[key] == count for key, count in previous.items()), 'Lost/changed/duplicated original ' + label
    added_words = collections.Counter(map(signature, new_text)) - collections.Counter(map(signature, old_text))
    added_frames = [json.loads(key) for key, count in added_words.items() for _ in range(count)]
    remaining = collections.Counter(map(signature, new_masks)) - collections.Counter(map(signature, old_masks))
    added_masks = [json.loads(key) for key, count in remaining.items() for _ in range(count)]
    assert all(overlap(rectangle(mask), rectangle(frame)) == 0 for mask in added_masks for frame in old_text), 'New mask overlaps original text region'
    def contained(mask, frame):
        a, b = rectangle(mask), rectangle(frame)
        return a[0] >= b[0]-.5 and a[1] >= b[1]-.5 and a[0]+a[2] <= b[0]+b[2]+.5 and a[1]+a[3] <= b[1]+b[3]+.5
    assert all(any(contained(mask, frame) for frame in added_frames) for mask in added_masks), 'New mask extends outside added word frames'
    numeric = {shape['text'] for shape in old_text if re.search(r'\d', shape['text'])}
    maximum_shift = 0
    with fitz.open(args.before / (args.case + '.scan.pdf')) as old_pdf, fitz.open(args.after / (args.case + '.scan.pdf')) as new_pdf:
        assert len(old_pdf) == len(new_pdf) == 1
        old_words = old_pdf[0].get_text('words'); new_words = new_pdf[0].get_text('words')
        for token in numeric:
            a = [w[:4] for w in old_words if w[4] == token]
            b = [w[:4] for w in new_words if w[4] == token]
            assert len(a) == len(b) == 1, 'Numeric lexeme lost or duplicated: ' + token
            shift = max(abs(x-y) for x, y in zip(a[0], b[0])); maximum_shift = max(maximum_shift, shift)
            assert shift <= .05, 'Office moved original numeric glyphs: ' + token
    result = {'case': args.case, 'originalWordFrames': len(old_text), 'newWordFrames': len(new_text),
              'originalMasks': len(old_masks), 'addedMasks': len(added_masks), 'originalFramesAndMasksExact': True,
              'sourceMediaExact': True, 'newMasksDisjointFromOriginalFrames': True,
              'originalRanksExact': True, 'newMasksContainedInAddedWordFramesWithinPt': .5,
              'numericLexemes': sorted(numeric), 'officeNumericMaxShiftPt': maximum_shift,
              'limitations': 'Generated shape IDs are excluded; geometry/style/z-order are exact. New mask containment allows0.5pt antialias margin; PDF tolerance0.05pt accounts for Office rounding. Other glyphs and private documents are not certified.'}
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
