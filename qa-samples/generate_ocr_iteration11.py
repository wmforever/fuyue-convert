#!/usr/bin/env python3
"""Freeze independent bilingual table/column controls and shadow fallback truth.

Paired unruled layouts intentionally share identical pixels but different reading
intent. They demonstrate non-identifiability, not independent statistical trials.
All text/code is Apache-2.0 synthetic; fonts have separate SIL-OFL-1.1 provenance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
SEED = 110042026
EN = ['Dispatch record 03121 dated 2027-02-16',
      'Account 00643 debit -417.85 USD',
      'Unit price .85 quantity 026 credit -0.65',
      'Net 17.40 total 029.05 discount 6.75%',
      'Keep every original word and scan pixel',
      'Reference CD-00817 cleared after checking',
      'Final balance 563.27 on 2027-03-09',
      'Separate numbers 14 28 remain separate']
ZH = ['发运记录03121，日期2027年02月16日。',
      '账户00643，借方金额-417.85元。',
      '单价0.85，数量026，抵扣-0.65元。',
      '净额17.40，总计029.05，折扣6.75%。',
      '保留全部原始文字和扫描像素。',
      '参考编号CD-00817，检查后批准。',
      '最终余额563.27，日期2027年03月09日。',
      '分开的数字14 28必须保持分开。']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT/'qa-samples/generated/cloud-iteration11')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    assert not (args.out/'expected.json').exists(), 'Use a new directory; never overwrite frozen truth'
    fonts = {'mono':Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'),
        'serif':Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
        'cjk':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
        'cjk-serif':Path('/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc')}
    metadata = {}
    for name, path in fonts.items():
        with TTFont(path,fontNumber=0) as font:
            assert set(map(ord,''.join(ZH if name.startswith('cjk') else EN))) <= set(font.getBestCmap())
            metadata[name] = {'path':str(path),'sha256':digest(path),
                'versions':sorted({n.toUnicode() for n in font['name'].names if n.nameID==5}),
                'license':'SIL-OFL-1.1','source':'https://github.com/notofonts/noto-cjk' if name.startswith('cjk')
                    else 'https://github.com/liberationfonts/liberation-fonts'}
    cases = []
    def save(name,image,lines,family,layout,**extra):
        path=args.out/(name+'.png'); image.save(path,dpi=(300,300))
        cases.append({'file':path.name,'sha256':digest(path),'expectedLines':lines,'font':family,
            'layout':layout,'sourceSkewDegrees':0,'dpi':300,**extra})
    for language,family in [('en','mono'),('zh','cjk')]:
        face=ImageFont.truetype(str(fonts[family]),29 if language=='en' else 30)
        labels=['Harbor','Meadow','Copper']
        grid=[[f'{labels[c]} keeps record{r+1:02d}' if language=='en'
               else f'{labels[c]}保留第{r+1:02d}条记录。' for c in range(3)] for r in range(4)]
        row_truth=[cell for row in grid for cell in row]
        column_truth=[grid[r][c] for c in range(3) for r in range(4)]
        for variant in ['unruled','ruled','staggered-columns']:
            image=Image.new('RGB',(2400,1500),'white');draw=ImageDraw.Draw(image)
            if variant=='ruled':
                for x in [75,795,1515,2295]:draw.line((x,100,x,1260),fill='black',width=3)
                for y in [100,390,680,970,1260]:draw.line((75,y,2295,y),fill='black',width=3)
            for r in range(4):
                for c in range(3):
                    x=120+c*720; y=250+r*290+(c*35 if variant=='staggered-columns' else 0)
                    assert draw.textlength(grid[r][c],font=face)<600
                    draw.text((x,y),grid[r][c],font=face,fill='black',anchor='ls')
            if variant=='unruled':
                pair=language+'-identical-unruled'
                save(language+'-prose-unruled',image,row_truth,family,'prose-table',
                     ambiguityPair=pair,readingIntent='row-major; not inferable from pixels alone')
                save(language+'-columns-aligned',image,column_truth,family,'three-columns',
                     ambiguityPair=pair,readingIntent='column-major; intentionally identical ambiguity pixels')
            else:
                save(language+'-'+variant,image,row_truth if variant=='ruled' else column_truth,
                     family,'prose-table' if variant=='ruled' else 'three-columns')
    for language,family in [('en','mono'),('zh','cjk')]:
        image=Image.new('RGB',(2400,1500),'white');draw=ImageDraw.Draw(image)
        face=ImageFont.truetype(str(fonts[family]),38)
        lines=EN if language=='en' else ZH
        for i,line in enumerate(lines):draw.text((160,170+i*160),line,font=face,fill='black',anchor='ls')
        save(language+'-numeric-upright',image,lines,family,'numeric-control')
    for name,family,axis in [('en-mono-shadow','mono','x'),('en-serif-shadow','serif','y'),
                             ('zh-sans-shadow','cjk','y'),('zh-serif-shadow','cjk-serif','x')]:
        w,h=2400,1500;y,x=np.mgrid[:h,:w];shade=(76+158*(x/(w-1) if axis=='x' else y/(h-1))).astype(np.uint8)
        image=Image.fromarray(np.repeat(shade[:,:,None],3,axis=2));draw=ImageDraw.Draw(image)
        face=ImageFont.truetype(str(fonts[family]),38);lines=ZH if family.startswith('cjk') else EN
        for i,line in enumerate(lines):draw.text((160,170+i*160),line,font=face,fill='black',anchor='ls')
        save(name,image,lines,family,'shadow-'+axis)
    rng=random.Random(SEED)
    for name in ['blank-shadow','noise-shadow']:
        image=Image.new('RGB',(2400,1500));draw=ImageDraw.Draw(image)
        for x in range(2400):draw.line((x,0,x,1500),fill=(80+x*150//2400,)*3)
        if name.startswith('noise'):
            for _ in range(31):
                x,y=rng.randrange(2400),rng.randrange(1500);draw.rectangle((x,y,x+1,y+1),fill='black')
        save(name,image,[],None,'blank-noise',expectedErrors=['OCR_NO_TEXT','OCR_LOW_CONFIDENCE'])
    manifest={'seed':SEED,'provenance':'New public synthetic bilingual corpus; frozen before algorithm changes and not derived from OCR',
        'license':'Apache-2.0 generated text/code; separate SIL-OFL-1.1 fonts','fonts':metadata,
        'versions':{'Pillow':pillow_version,'NumPy':np.__version__},'generatorSha256':digest(Path(__file__)),
        'limits':['Unruled pairs share identical pixels with different stated intents; do not count as independent trials or infer their intent.'],
        'cases':cases,'containerCases':[]}
    p=args.out/'expected.json';p.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print('Frozen',len(cases),'cases; manifest',digest(p))


if __name__=='__main__':main()
