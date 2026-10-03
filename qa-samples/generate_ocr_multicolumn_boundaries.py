#!/usr/bin/env python3
"""Freeze independent public synthetic three/four-column prose before inference QA."""
import hashlib
import json
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont, __version__

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'qa-samples/generated/cloud-multicolumn-boundaries'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    fonts = {}
    for family in ['Sans', 'Serif', 'Mono']:
        path = Path('/usr/share/fonts/truetype/liberation/Liberation' + family + '-Regular.ttf')
        with TTFont(path) as metadata:
            assert set(map(ord, 'Independent column sentenceABCD0123456789')) <= set(metadata.getBestCmap())
            fonts[family] = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'version': sorted({n.toUnicode() for n in metadata['name'].names if n.nameID == 5}), 'license': 'SIL-OFL-1.1'}
        font = ImageFont.truetype(str(path), 32)
        for columns, spacing in [(3, 350), (4, 350), (3, 70)]:
            width = 3000 if columns == 3 else 4400
            starts = [150, 1140, 2130] if columns == 3 else [120, 1220, 2320, 3420]
            image = Image.new('RGB', (width, 1800), 'white')
            draw = ImageDraw.Draw(image)
            expected = []
            for side, x in enumerate(starts):
                for row in range(3):
                    line = 'Independent column sentence' + chr(65 + side) + f'{row + 1:02d}'
                    draw.text((x, 300 + row * spacing + (side * 8 if spacing == 70 else 0)),
                              line, font=font, fill='black', anchor='ls')
                    expected.append(line)
            name = family.lower() + f'-{columns}-columns' + ('-compact' if spacing == 70 else '') + '.png'
            target = OUT / name
            image.save(target, dpi=(300, 300))
            image.close()
            cases.append({'file': name, 'expectedLines': expected, 'columns': columns, 'font': family, 'spacing': spacing,
                          'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    (OUT / 'expected.json').write_text(json.dumps({'provenance': 'Public synthetic prose; independent font/column truth frozen without OCR',
        'pillow': __version__, 'fonts': fonts, 'cases': cases}, ensure_ascii=False, indent=2) + '\n')
    print('Frozen nine independent three/four-column prose samples')


if __name__ == '__main__':
    main()
