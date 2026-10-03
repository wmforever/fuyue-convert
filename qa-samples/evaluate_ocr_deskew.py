#!/usr/bin/env python3
"""Offline deskew experiment, not a production adoption or coordinate acceptance.

Requires Pillow and NumPy. Estimates skew from horizontal ink projections;
keeps the input intact and expands the rotated canvas. Evaluates against truth.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time

import numpy as np
from PIL import Image, ImageDraw, __version__ as pillow_version
from verify_cloud_ocr import metrics

ROOT = Path(__file__).resolve().parents[1]


def estimate(image):
    thumb = image.convert('L')
    thumb.thumbnail((700, 700))
    y, x = np.nonzero(np.asarray(thumb) < 160)
    if len(x) < 100:
        return None
    # Bound work even for dark pages; this exploratory score alone is not a
    # production safeguard against stamps, grids, photographs or sparse ink.
    stride = max(1, math.ceil(len(x) / 100000))
    x, y = x[::stride], y[::stride]
    scores = []
    for degrees in np.arange(-8, 8.01, .25):
        theta = math.radians(float(degrees))
        rows = np.rint(y * math.cos(theta) - x * math.sin(theta)).astype(int)
        counts = np.bincount(rows - rows.min())
        scores.append((float(np.sum(counts.astype(float) ** 2)), float(degrees)))
    score, angle = max(scores)
    zero = next(value for value, degrees in scores if degrees == 0)
    return {'degrees': angle, 'projectionGain': score / zero, 'sampledInkPixels': len(x)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--samples', type=Path, default=ROOT / 'qa-samples/generated/cloud-handoff')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((args.samples / 'expected.json').read_text())
    report = {'pillow': pillow_version, 'numpy': np.__version__, 'runtime':
              json.loads((args.runtime / 'OCR-RUNTIME.json').read_text()), 'cases': []}
    for case in manifest['cases']:
        original = args.samples / case['file']
        image = Image.open(original)
        started = time.monotonic()
        detected = estimate(image)
        detection_time = time.monotonic() - started
        corrected = image.rotate(detected['degrees'], resample=Image.Resampling.BICUBIC, expand=True, fillcolor='white')
        target = args.out / case['file']
        corrected.save(target, dpi=(300, 300))
        record = {'file': case['file'], 'expectedDegrees': case['sourceSkewDegrees'], 'detected': detected,
                  'detectionSeconds': detection_time, 'originalSize': image.size, 'correctedSize': corrected.size,
                  'originalSha256': hashlib.sha256(original.read_bytes()).hexdigest()}
        for label, path in [('before', original), ('deskew', target)]:
            started = time.monotonic()
            result = subprocess.run([str(args.runtime / 'bin/tesseract'), str(path), 'stdout',
                                     '--tessdata-dir', str(args.runtime / 'tessdata'), '-l', 'chi_sim+eng', '--psm', '3'],
                                    capture_output=True, text=True, check=True)
            record[label] = {'seconds': time.monotonic() - started, 'text': result.stdout,
                             **metrics('\n'.join(case['expectedLines']), result.stdout)}
        report['cases'].append(record)
        print(record['file'], detected, record['before']['cer'], record['deskew']['cer'], flush=True)
    # Deliberately probe failure boundaries; these do not prove detector safety.
    controls = {}
    blank = Image.new('RGB', (1400, 1000), 'white')
    controls['blank'] = blank
    sparse = blank.copy()
    ImageDraw.Draw(sparse).rectangle((500, 400, 503, 403), fill='black')
    controls['isolated_mark'] = sparse
    grid = blank.copy()
    draw = ImageDraw.Draw(grid)
    for x in range(100, 1300, 150):
        draw.line((x, 100, x, 900), fill='black', width=3)
    for y in range(100, 901, 100):
        draw.line((100, y, 1300, y), fill='black', width=3)
    controls['grid_without_text'] = grid
    stamp = blank.copy()
    ImageDraw.Draw(stamp).ellipse((400, 300, 750, 650), outline='black', width=12)
    controls['ring_without_text'] = stamp
    upright = Image.open(args.samples / 'english-tilt-+0.png')
    controls['outside_range_15_degrees'] = upright.rotate(-15, expand=True, fillcolor='white')
    controls['vertical_90_degrees'] = upright.rotate(-90, expand=True, fillcolor='white')
    report['detectorBoundaryTrials'] = {name: estimate(image) for name, image in controls.items()}
    (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
