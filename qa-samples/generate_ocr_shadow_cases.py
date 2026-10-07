#!/usr/bin/env python3
"""Freeze independent shading truth and abstention controls; no OCR is consulted."""
import hashlib
import json
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'qa-samples/generated/cloud-shadow-independent'
EN = ['Shipment 00462 arrived 2026-08-23', 'Debit -307.16 and credit 008.90',
      'Batch 72105 quantity 012 items', 'Subtotal 418.32 tax 025.10',
      'Keep all lines including this sentence', 'Review reference 69024 before approval']
ZH = ['收货编号00462，日期2026年。', '借方307.16，贷方008.90。',
      '批次72105，数量012件。', '小计418.32，税额025.10。',
      '保留每行文字和原始扫描图。', '参考69024，审批前请复核。']


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fonts = {'sans': ROOT/'task-service/src/main/resources/fonts/LiberationSans-Regular.ttf',
             'serif': Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
             'mono': Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'),
             'cjk': Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')}
    metadata = {}
    for key, path in fonts.items():
        with TTFont(path, fontNumber=0) as font:
            needed = set(map(ord, ''.join(ZH if key == 'cjk' else EN)))
            assert needed <= set(font.getBestCmap()), f'Missing glyphs in {key}'
            metadata[key] = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                             'version': sorted({n.toUnicode() for n in font['name'].names if n.nameID == 5}),
                             'license': 'SIL-OFL-1.1'}
    cases = []
    # Diverse directions/paper ranges; defined before candidate API evaluation.
    variants = [('en-serif-shadow-left', 'serif', 'left', 75, 225),
                ('en-sans-shadow-right', 'sans', 'right', 85, 215),
                ('en-mono-shadow-vertical', 'mono', 'vertical', 90, 220),
                ('zh-cjk-shadow-left', 'cjk', 'left', 90, 220),
                ('zh-cjk-shadow-vertical', 'cjk', 'vertical', 100, 235),
                ('en-sans-uniform-gray', 'sans', 'uniform', 150, 150)]
    for name, family, direction, low, high in variants:
        image = Image.new('RGB', (1600, 1100)); draw = ImageDraw.Draw(image)
        for y in range(image.height):
            if direction == 'vertical':
                gray = low + (high-low)*y//image.height
                draw.line((0,y,image.width,y), fill=(gray,)*3)
            else:
                for x in range(image.width):
                    t = image.width-x if direction == 'right' else x
                    gray = low + (high-low)*t//image.width
                    image.putpixel((x,y), (gray,)*3)
        lines = ZH if family == 'cjk' else EN
        font = ImageFont.truetype(str(fonts[family]), 39 if family == 'cjk' else 36)
        for i, line in enumerate(lines): draw.text((140,160+i*145), line, font=font, fill='black', anchor='ls')
        draw.rectangle((1380,680,1460,740), fill=(200,20,20))
        path = OUT/(name+'.png'); image.save(path, dpi=(300,300)); image.close()
        cases.append({'file': path.name, 'expectedLines': lines, 'font': family, 'layout': direction,
                      'pixelProbes': [{'xFraction':1420/1600, 'yFraction':710/1100}],
                      'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    rng = random.Random(61003)
    for name in ['shaded-blank', 'shaded-noise', 'shaded-one-mark']:
        image = Image.new('RGB', (1600,1100)); draw = ImageDraw.Draw(image)
        for x in range(1600):
            gray=90+x*130//1600;draw.line((x,0,x,1100),fill=(gray,)*3)
        if name=='shaded-noise':
            for _ in range(35):
                x,y=rng.randrange(1600),rng.randrange(1100);draw.rectangle((x,y,x+1,y+1),fill='black')
        if name=='shaded-one-mark': draw.rectangle((650,500,750,540),fill=(200,20,20))
        path=OUT/(name+'.png');image.save(path,dpi=(300,300));image.close()
        cases.append({'file':path.name,'expectedLines':[], 'expectedErrors':['OCR_NO_TEXT','OCR_LOW_CONFIDENCE'],
                      'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (OUT/'expected.json').write_text(json.dumps({'provenance':'Independent shading truth frozen before candidate evaluation; synthetic',
        'pillowVersion':pillow_version,'fonts':metadata,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    print(f'Frozen {len(cases)} independent cases')


if __name__ == '__main__': main()
