#!/usr/bin/env python3
"""Freeze broader synthetic OCR truth before evaluating a candidate.

Uses repository fonts plus explicit licensed system-font paths. No font binaries
or generated inputs are committed. Manifest records exact font hashes/versions.
"""
import argparse
import hashlib
import json
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
ENGLISH = ['Delivery note 00731 dated 2026-09-17', 'Account 59028 balance -804.24 USD',
           'Unit price 12.50 quantity 003 total 37.50', 'Reference 91826 must remain editable',
           'Check every amount and punctuation mark', 'Final subtotal 6701.09 tax 402.07',
           'Independent holdout contains eight lines', 'Approved copy number 021 needs review']
CHINESE = ['交货记录：编号00731，日期2026年。', '账户59028，余额负804.24元。',
           '单价12.50，数量003，总额37.50。', '客户参考91826，逐项核对数字。',
           '请保留标点和原图，不要丢失文字。', '小计6701.09，税额402.07元。',
           '独立测试包含八行内容，仍需复核。', '第021份副本可以编辑并重新打开。']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / 'qa-samples/generated/cloud-holdouts')
    parser.add_argument('--serif', type=Path, required=True)
    parser.add_argument('--mono', type=Path, required=True)
    parser.add_argument('--cjk-sans', type=Path, required=True)
    parser.add_argument('--cjk-serif', type=Path, required=True)
    args = parser.parse_args(); args.out.mkdir(parents=True, exist_ok=True)
    fonts = {'sans': ROOT / 'task-service/src/main/resources/fonts/LiberationSans-Regular.ttf',
             'droid': ROOT / 'task-service/src/main/resources/fonts/DroidSansFallback.ttf',
             'serif': args.serif, 'mono': args.mono, 'cjk-sans': args.cjk_sans, 'cjk-serif': args.cjk_serif}
    metadata, coverage = {}, {}
    for key, path in fonts.items():
        font = TTFont(path, fontNumber=0)
        metadata[key] = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                         'version': sorted({n.toUnicode() for n in font['name'].names if n.nameID == 5}),
                         'license': 'Apache-2.0' if key == 'droid' else 'SIL-OFL-1.1'}
        coverage[key] = set(font.getBestCmap())
        font.close()
    # Defined before candidate changes; no truth or layout changes based on OCR.
    variants = [('en-sans-upright', 'sans', 0, 'plain'), ('en-sans-negative2', 'sans', -2, 'plain'),
                ('en-sans-positive7', 'sans', 7, 'plain'), ('en-serif-negative7', 'serif', -7, 'plain'),
                ('en-serif-positive4', 'serif', 4, 'plain'), ('en-mono-positive2', 'mono', 2, 'plain'),
                ('en-mono-shadow', 'mono', 0, 'shadow'), ('en-sans-columns4', 'sans', 4, 'columns'),
                ('zh-droid-upright', 'droid', 0, 'plain'), ('zh-droid-negative4', 'droid', -4, 'plain'),
                ('zh-droid-positive2', 'droid', 2, 'plain'), ('zh-droid-positive7', 'droid', 7, 'plain'),
                ('zh-sans-negative7', 'cjk-sans', -7, 'plain'), ('zh-serif-positive4', 'cjk-serif', 4, 'plain'),
                ('zh-serif-shadow', 'cjk-serif', 0, 'shadow'), ('zh-droid-columns2', 'droid', -2, 'columns')]
    cases = []
    for name, family, angle, layout in variants:
        english = name.startswith('en-'); lines = ENGLISH if english else CHINESE
        image = Image.new('RGB', (1800, 1200), 'white'); draw = ImageDraw.Draw(image)
        font = ImageFont.truetype(str(fonts[family]), 34 if english else 40)
        if layout == 'shadow':
            for x in range(image.width):
                gray = 90 + x * 130 // image.width; draw.line((x, 0, x, image.height), fill=(gray,)*3)
        positions = [(170, 145 + 120*i) for i in range(8)]
        if layout == 'columns':
            font = ImageFont.truetype(str(fonts[family]), 26 if english else 30)
            positions = [(110 if i < 4 else 1000, 190 + 180*(i%4)) for i in range(8)]
        fallback = ImageFont.truetype(str(fonts['sans']), font.size)
        for line, position in zip(lines, positions):
            if all(ord(char) in coverage[family] for char in line):
                draw.text(position, line, font=font, fill=(0, 0, 0), anchor='ls')
            else:
                # Droid is a CJK fallback and has no ASCII digits. Draw real
                # fallback glyphs rather than attach truth to missing-glyph boxes.
                x, y = position
                for char in line:
                    face = font if ord(char) in coverage[family] else fallback
                    if ord(char) not in coverage[family] and ord(char) not in coverage['sans']:
                        raise ValueError('No licensed glyph for ' + char)
                    draw.text((x, y), char, font=face, fill=(0, 0, 0), anchor='ls')
                    x += face.getlength(char)
        if layout == 'shadow':
            # Deliberately unrecognized colored content outside text masks.
            draw.rectangle((1450, 700, 1530, 760), fill=(200, 20, 20))
        tilted = image.rotate(-angle, resample=Image.Resampling.BICUBIC, expand=False, fillcolor='white')
        path = args.out / (name+'.png'); tilted.save(path, dpi=(300,300)); image.close(); tilted.close()
        cases.append({'file':path.name, 'expectedLines':lines, 'sourceSkewDegrees':angle,
                      'font':family, 'layout':layout, 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    rng = random.Random(20261003)
    for name in ['blank-white', 'blank-gray', 'isolated-noise']:
        image = Image.new('RGB', (1800,1200), (150,)*3 if name=='blank-gray' else 'white')
        if name=='isolated-noise':
            draw=ImageDraw.Draw(image)
            for _ in range(35):
                x,y=rng.randrange(100,1700),rng.randrange(100,1100);draw.rectangle((x,y,x+2,y+2),fill='black')
        path=args.out/(name+'.png');image.save(path,dpi=(300,300));image.close()
        cases.append({'file':path.name,'expectedLines':[], 'expectedErrors':['OCR_NO_TEXT','OCR_LOW_CONFIDENCE'],
                      'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (args.out/'expected.json').write_text(json.dumps({'provenance':'Frozen synthetic holdouts; no real user data',
        'pillowVersion':pillow_version,'fonts':metadata,'cases':cases},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Generated {len(cases)} frozen holdouts')


if __name__ == '__main__':main()
