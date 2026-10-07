#!/usr/bin/env python3
"""Independent synthetic omission-warning holdouts; truth is fixed without OCR."""
import hashlib
import json
import argparse
import shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

ROOT=Path(__file__).resolve().parents[1]
EN=['Dispatch 00684 recorded 2026-10-02','Credit 903.17 debit -209.14 USD',
    'Parcel 45019 holds 007 units','Subtotal 627.30 tax 043.91 USD',
    'Keep the original scan and every sentence','Review reference 30862 before signing']
ZH=['发货编号00684，记录2026年。','贷方903.17，借方209.14元。',
    '包裹45019，数量007件。','小计627.30，税额043.91元。',
    '保留原始扫描图和每一行文字。','参考30862，签字以前请复核。']


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--acceptance',action='store_true')
    args=parser.parse_args()
    out=ROOT/'qa-samples/generated/cloud-omission-holdouts';out.mkdir(parents=True,exist_ok=True)
    paths={'mono':Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'),
           'serif':Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
           'cjk':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')}
    metadata={};cases=[]
    for family,path in paths.items():
        lines=ZH if family=='cjk' else EN
        with TTFont(path,fontNumber=0) as font:
            assert set(map(ord,''.join(lines)))<=set(font.getBestCmap())
            metadata[family]={'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                              'version':sorted({n.toUnicode() for n in font['name'].names if n.nameID==5}),
                              'license':'SIL-OFL-1.1'}
        for shade in [True,False]:
            name=('zh-' if family=='cjk' else 'en-')+family+('-vertical' if shade else '-uniform')+'.png'
            image=Image.new('RGB',(1800,1400));draw=ImageDraw.Draw(image)
            for y in range(1400):
                gray=65+y*155//1400 if shade else 145
                draw.line((0,y,1800,y),fill=(gray,)*3)
            face=ImageFont.truetype(str(path),40 if family=='cjk' else 36)
            for i,line in enumerate(lines):draw.text((180,220+170*i),line,font=face,fill='black',anchor='ls')
            draw.rectangle((1560,600,1640,660),fill=(200,20,20))
            target=out/name;image.save(target,dpi=(300,300));image.close()
            cases.append({'file':name,'expectedLines':lines,'font':family,'layout':'vertical' if shade else 'uniform',
                          'pixelProbes':[{'xFraction':1600/1800,'yFraction':630/1400}],
                          'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
    (out/'expected.json').write_text(json.dumps({'provenance':'Independent public synthetic omission truth frozen before candidate API evaluation',
        'fonts':metadata,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    print('Frozen six independent omission holdouts')
    if args.acceptance:
        target=ROOT/'qa-samples/generated/cloud-iteration4-acceptance';target.mkdir(parents=True,exist_ok=True)
        pool=[];font_metadata={}
        for folder,names in [('cloud-shadow-independent',None),('cloud-omission-holdouts',None),('cloud-handoff',None),
                             ('cloud-holdouts',['en-mono-shadow.png','en-sans-columns4.png','zh-droid-columns2.png'])]:
            source=ROOT/'qa-samples/generated'/folder
            if not (source/'expected.json').is_file():raise RuntimeError('Generate the existing fixture set first: '+folder)
            manifest=json.loads((source/'expected.json').read_text());font_metadata.update(manifest.get('fonts',{}))
            for case in manifest['cases']:
                if names is None or case['file'] in names:shutil.copy2(source/case['file'],target/case['file']);pool.append(case)
        assert len(pool)==24
        (target/'expected.json').write_text(json.dumps({'provenance':'Public synthetic acceptance cases; existing frozen truths reused; six fresh independent holdouts',
            'fonts':font_metadata,'cases':pool},ensure_ascii=False,indent=2)+'\n')
        print('Assembled24 existing and independent synthetic acceptance cases')


if __name__=='__main__':main()
