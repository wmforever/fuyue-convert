#!/usr/bin/env python3
"""Freeze bounded PDF visibility controls and reuse the already completed money matrix.

Requires PyMuPDF 1.26.6 and the iteration26-final/iteration27-before evidence.
No customer data, network, OCR or conversion is performed by this helper.
"""
import hashlib
import json
from pathlib import Path
import fitz

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'qa-samples/generated/visibility-iteration27'
BEFORE = ROOT / 'qa-samples/report/iteration27-before'


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=False)
    sources, controls, actions = {}, [], []

    def save(name, data):
        (OUT / name).write_bytes(data)
        sources[name] = sha(data)
        return name

    for case in ['decimal', 'negative', 'leading-zero', 'gray', 'dark']:
        for kind in ['edited-office', 'office']:
            name = save(case + '-' + kind + '.pdf', (BEFORE / (case + '-' + kind + '-result.pdf')).read_bytes())
            actions.append(dict(id=case + '-' + kind, input=name, target='txt'))
            if kind == 'office': controls.append(actions[-1])
        if case in ['decimal', 'gray', 'dark']:
            name = save(case + '-scan.pdf', (BEFORE / (case + '-pdf-result.pdf')).read_bytes())
            actions.append(dict(id=case + '-word', input=name, target='docx'))

    for mode in ['ordinary', 'before-image', 'partial', 'transparent', 'opaque']:
        doc = fitz.open(BEFORE / 'decimal-pdf-result.pdf')
        page = doc[0]
        if mode != 'ordinary':
            rect = fitz.Rect(40, 0, 50, page.rect.height) if mode == 'partial' else page.rect
            page.draw_rect(rect, color=None, fill=(.8, .8, .8),
                           fill_opacity=.5 if mode == 'transparent' else 1,
                           overlay=mode != 'before-image')
        page.insert_text((15, 262), 'Visible replacement 128.75' if mode == 'opaque' else 'Native control 5521', fontsize=10)
        name = save('control-' + mode + '.pdf', doc.tobytes())
        doc.close()
        action = dict(id='control-' + mode, input=name, target='txt')
        controls.append(action); actions.append(action)

    original = ROOT / 'qa-samples/report/iteration26-final/original-edited-office-edited.docx'
    name = save('original-edited.docx', original.read_bytes())
    actions.extend([dict(id='original-office', input=name, target='pdf'),
                    dict(id='original-text', input='@original-office', target='txt')])
    metadata = dict(generatorSha256=sha(Path(__file__).read_bytes()), pymupdfVersion=fitz.VersionBind,
                    sources=sources, parentRevision='6d163a92806253a2a2895c2dee7088d0e4fd3bba')
    (OUT / 'expected.json').write_text(json.dumps(dict(metadata, actions=actions), indent=2) + '\n')
    before = OUT / 'before'; before.mkdir()
    # Hard links preserve the exact frozen input bytes; no second generation.
    import os
    for name in sources: os.link(OUT / name, before / name)
    (before / 'expected.json').write_text(json.dumps(dict(metadata, actions=controls), indent=2) + '\n')
    print(json.dumps(dict(actions=len(actions), baselineControls=len(controls), manifestSha256=sha((OUT / 'expected.json').read_bytes()))))


if __name__ == '__main__': main()
