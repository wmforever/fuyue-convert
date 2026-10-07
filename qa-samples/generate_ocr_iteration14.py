#!/usr/bin/env python3
"""Freeze the finite PSM6 diagnostic before any new OCR measurement.

Original text and six images come from the frozen iteration11 synthetic corpus.
Four mild shaded controls preserve its pixels/geometry and declared truth.
"""
import hashlib
import json
from pathlib import Path
import shutil
import numpy as np
from PIL import Image, __version__ as pillow_version

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'qa-samples/generated/cloud-iteration11'
OUT = ROOT/'qa-samples/generated/cloud-iteration14'
SEED = 140042026

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    assert not OUT.exists(), 'Frozen output already exists; do not overwrite or repeat'
    source = json.loads((SOURCE/'expected.json').read_text())
    OUT.mkdir(parents=True)
    cases = []
    originals = ['en-serif-shadow', 'zh-sans-shadow', 'en-mono-shadow',
                 'zh-serif-shadow', 'blank-shadow', 'noise-shadow']
    controls = ['en-numeric-upright', 'zh-numeric-upright', 'en-columns-aligned', 'en-ruled']
    for name in originals + controls:
        old = next(c for c in source['cases'] if c['file'] == name+'.png')
        assert digest(SOURCE/old['file']) == old['sha256']
        case = dict(old)
        case['sourceFile'] = old['file']
        case['sourceSha256'] = old['sha256']
        case['reusedOriginalPsm3'] = name in originals
        case['reusedEnhancedPsm3'] = name in originals and name != 'blank-shadow'
        if name in originals:
            shutil.copyfile(SOURCE/old['file'], OUT/old['file'])
        else:
            with Image.open(SOURCE/old['file']) as image:
                pixels = np.asarray(image.convert('RGB'), dtype=np.uint16)
                h = pixels.shape[0]
                paper = (145+100*np.arange(h)//(h-1)).astype(np.uint16)
                shaded = (pixels*paper[:,None,None]//255).astype(np.uint8)
            case['file'] = name+'-mild-shadow.png'
            Image.fromarray(shaded).save(OUT/case['file'], dpi=(300,300))
            case['derivation'] = 'RGB channel * vertical paper (145+100*y//1499) //255; no resampling/cropping/rotation'
        case['sha256'] = digest(OUT/case['file'])
        cases.append(case)
    manifest = {'seed':SEED, 'license':source['license'], 'fonts':source['fonts'],
        'sourceManifestSha256':digest(SOURCE/'expected.json'),
        'generatorSha256':digest(Path(__file__)),
        'versions':{'Pillow':pillow_version,'NumPy':np.__version__},
        'provenance':'Frozen synthetic truth, never derived from OCR; four mild-shade derivatives; seed is declared but no new randomness is used',
        'limits':['One declared column reading intent is evaluated; identical unruled table/column pixels cannot establish intent.',
                  'Cached iteration11 commands are reused, not contemporaneous time observations.',
                  'No public real-document fixtures are introduced.'],
        'cases':cases}
    path=OUT/'expected.json'
    path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    shutil.copyfile(path,ROOT/'docs/cloud-ocr-iteration14-corpus.json')
    print(json.dumps({'cases':len(cases),'manifestSha256':digest(path),'seed':SEED}))

if __name__ == '__main__': main()
