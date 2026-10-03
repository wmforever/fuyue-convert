#!/usr/bin/env python3
"""Freeze new independent synthetic image/PDF/OFD truth before OCR experiments.

Generated text and code are Apache-2.0 contributions; fonts remain separately
licensed. No private documents, OCR-derived truth or runtime downloads are used.
OFD wrappers use the already pinned OFDRW dependency through the companion Java
source. All rendered PNGs are lossless and retain their 300 DPI physical size.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess
import zipfile

import fitz
import numpy as np
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
SEED = 810032026
EN = ['Warehouse record 00953 dated 2026-11-28',
      'Account ID 08271 credit -615.42 USD',
      'Unit cost .95 quantity 014 rebate -0.75',
      'Decimal 18.09 total 013.30 percent 7.25%',
      'Preserve every word and the original scan',
      'Reference AB-00562 approved after review',
      'Closing balance 724.18 on 2026-12-03',
      'Keep separate tokens 12 34 and all eight lines']
ZH = ['仓库记录00953，日期2026年11月28日。',
      '账户编号08271，贷方金额-615.42元。',
      '单价0.95，数量014，折扣-0.75元。',
      '金额18.09，总计013.30，比例7.25%。',
      '保留每个文字以及原始扫描图像。',
      '参考编号AB-00562，审核后批准。',
      '期末余额724.18，日期2026年12月03日。',
      '保留分开的数字12 34以及全部八行。']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT/'qa-samples/generated/cloud-iteration8')
    parser.add_argument('--classpath', required=True, help='Existing pinned application dependency directory/*')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if (args.out/'expected.json').exists():
        raise SystemExit('Frozen corpus already exists; use a new output directory')
    fonts = {'mono':Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'),
             'serif':Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
             'cjk':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
             'cjk-serif':Path('/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc')}
    metadata = {}
    for key, path in fonts.items():
        with TTFont(path, fontNumber=0) as font:
            truth = ZH if key.startswith('cjk') else EN
            assert set(map(ord, ''.join(truth))) <= set(font.getBestCmap())
            metadata[key] = {'path':str(path), 'sha256':digest(path),
                'version':sorted({n.toUnicode() for n in font['name'].names if n.nameID==5}),
                'license':'SIL-OFL-1.1',
                'source':'https://github.com/notofonts/noto-cjk' if key.startswith('cjk') else 'https://github.com/liberationfonts/liberation-fonts'}
    variants = [('en-mono-xshade','mono',0,'xshade'), ('en-serif-yshade','serif',0,'yshade'),
                ('zh-sans-yshade','cjk',0,'yshade'), ('zh-serif-xshade','cjk-serif',0,'xshade'),
                ('en-serif-minus4','serif',-4,'plain'), ('en-mono-plus4','mono',4,'plain'),
                ('zh-sans-minus4','cjk',-4,'plain'), ('zh-serif-plus4','cjk-serif',4,'plain'),
                ('bilingual-upright','cjk',0,'bilingual'),
                ('en-three-columns','mono',0,'columns'), ('zh-three-columns','cjk',0,'columns'),
                ('bilingual-table','cjk',0,'table')]
    cases=[]
    for name,family,angle,layout in variants:
        width,height=2100,1450
        y,x=np.mgrid[:height,:width]
        shade = np.full((height,width),255,dtype=np.uint8)
        if layout=='xshade': shade=(78+155*x/(width-1)).astype(np.uint8)
        if layout=='yshade': shade=(72+158*y/(height-1)).astype(np.uint8)
        image=Image.fromarray(np.repeat(shade[:,:,None],3,axis=2)); draw=ImageDraw.Draw(image)
        lines=ZH if family.startswith('cjk') else EN
        size=39 if family.startswith('cjk') else 37
        face=ImageFont.truetype(str(fonts[family]),size)
        positions=[(175,165+i*160) for i in range(8)]
        if layout=='bilingual':
            lines=[EN[i] if i%2==0 else ZH[i] for i in range(8)]
        if layout=='columns':
            size=25 if not family.startswith('cjk') else 27
            face=ImageFont.truetype(str(fonts[family]),size)
            lines=[];positions=[]
            for column in range(3):
                for row in range(4):
                    text=(f'Column {column+1} keeps sentence {row+1}' if not family.startswith('cjk')
                          else f'第{column+1}栏完整保留第{row+1}句话。')
                    lines.append(text);positions.append((80+column*690,210+row*235))
        if layout=='table':
            lines=['项目 Item 金额 Amount 日期 Date', '货物 A -0.95 2026-11-28',
                   '费用 B 013.30 2026-12-03', '数量 C 12 34 AB-00562', '比例 D 7.25% 编号08271']
            for row in range(6): draw.line((120,100+row*215,1970,100+row*215),fill='black',width=3)
            for col in (120,740,1310,1970): draw.line((col,100,col,1175),fill='black',width=3)
            for row,line in enumerate(lines):
                fields= [line] if row==0 else line.split(' ',2)
                for col,text in enumerate(fields):draw.text((155+col*620,205+row*215),text,font=face,fill='black',anchor='ls')
        else:
            for line,pos in zip(lines,positions):
                assert draw.textlength(line,font=face)+pos[0]<width-50, (name,line)
                draw.text(pos,line,font=face,fill='black',anchor='ls')
        tilted=image.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white')
        path=args.out/(name+'.png');tilted.save(path,dpi=(300,300))
        cases.append({'file':path.name,'expectedLines':lines,'sourceSkewDegrees':angle,'font':family,'layout':layout,'sha256':digest(path)})
    rng=random.Random(SEED)
    for name in ['shaded-blank-new','shaded-noise-new']:
        image=Image.new('RGB',(2100,1450));draw=ImageDraw.Draw(image)
        for x in range(2100):draw.line((x,0,x,1450),fill=(88+x*140//2100,)*3)
        if 'noise' in name:
            for _ in range(28):
                x,y=rng.randrange(2100),rng.randrange(1450);draw.rectangle((x,y,x+1,y+1),fill='black')
        path=args.out/(name+'.png');image.save(path,dpi=(300,300))
        cases.append({'file':path.name,'expectedLines':[],'expectedErrors':['OCR_NO_TEXT','OCR_LOW_CONFIDENCE'],'sha256':digest(path)})
    wrappers=[]
    for stem in ['en-mono-xshade','bilingual-upright','bilingual-table','shaded-blank-new']:
        png=args.out/(stem+'.png');case=next(c for c in cases if c['file']==png.name)
        pdf=args.out/(stem+'.pdf')
        document=fitz.open();page=document.new_page(width=2100*72/300,height=1450*72/300)
        page.insert_image(page.rect,filename=str(png));document.set_metadata({'title':'Public synthetic OCR iteration8','producer':'PyMuPDF '+fitz.VersionBind})
        document.save(pdf,no_new_id=True);document.close()
        ofd=args.out/(stem+'.ofd')
        subprocess.run(['java','-cp',args.classpath,str(ROOT/'qa-samples/OcrRasterOfdFixture.java'),str(png),str(ofd),'2100','1450'],check=True)
        # OFDRW embeds a generation timestamp. Freeze reproducible ZIP timestamps
        # and metadata without changing content/resource identifiers.
        with zipfile.ZipFile(ofd) as archive:members={n:archive.read(n) for n in archive.namelist()}
        for name,contents in members.items():
            if name=='OFD.xml':
                import re
                members[name]=re.sub(rb'<ofd:CreationDate>[^<]*</ofd:CreationDate>',b'<ofd:CreationDate>2026-10-03</ofd:CreationDate>',contents)
                members[name]=re.sub(rb'<ofd:DocID>[^<]*</ofd:DocID>',
                    ('<ofd:DocID>'+digest(png)[:32]+'</ofd:DocID>').encode(),members[name])
        with zipfile.ZipFile(ofd,'w',compression=zipfile.ZIP_DEFLATED) as archive:
            for name,contents in sorted(members.items()):
                info=zipfile.ZipInfo(name,(2026,10,3,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,contents)
        for path in [pdf,ofd]:wrappers.append({**case,'file':path.name,'sha256':digest(path),'rasterSource':png.name})
    manifest={'seed':SEED,'provenance':'New independent public synthetic corpus; truth frozen before algorithm changes and never obtained from OCR',
              'license':'Apache-2.0 generated text/code; separate SIL-OFL-1.1 font sources above',
              'versions':{'Pillow':pillow_version,'PyMuPDF':fitz.VersionBind,'NumPy':np.__version__},
              'fonts':metadata,'cases':cases,'containerCases':wrappers,
              'generatorSha256':digest(Path(__file__)),'ofdGeneratorSha256':digest(ROOT/'qa-samples/OcrRasterOfdFixture.java')}
    (args.out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print('Frozen',len(cases),'images and',len(wrappers),'PDF/OFD wrappers; manifest',digest(args.out/'expected.json'))


if __name__=='__main__':main()
