#!/usr/bin/env python3
"""Select twelve frozen public cases from existing generators; do not edit truth."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1] / 'qa-samples/generated'
SELECTION = {
    'cloud-shadow-independent': ['en-mono-shadow-vertical.png', 'zh-cjk-shadow-vertical.png', 'shaded-blank.png'],
    'cloud-handoff': ['english-tilt-+0.png', 'english-tilt--6.png', 'english-tilt-+6.png',
                      'chinese-tilt-+0.png', 'chinese-tilt--6.png', 'chinese-tilt-+6.png'],
    'cloud-holdouts': ['en-mono-shadow.png', 'en-sans-columns4.png', 'zh-droid-columns2.png'],
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / 'cloud-iteration6-contracts')
    out = parser.parse_args().out
    out.mkdir(parents=True, exist_ok=True)
    cases, fonts = [], {}
    for directory, names in SELECTION.items():
        manifest = json.loads((ROOT / directory / 'expected.json').read_text())
        fonts[directory] = manifest.get('fonts', {})
        by_name = {c['file']: c for c in manifest['cases']}
        for name in names:
            case = by_name[name]
            source = ROOT / directory / name
            assert hashlib.sha256(source.read_bytes()).hexdigest() == case['sha256']
            shutil.copyfile(source, out / name)
            cases.append(case)
    (out / 'expected.json').write_text(json.dumps({
        'provenance': 'Public synthetic frozen handoff, shading and holdout cases; no OCR-derived truth',
        'fonts': fonts, 'cases': cases}, ensure_ascii=False, indent=2) + '\n')
    print('Selected twelve frozen public cases')


if __name__ == '__main__':
    main()
