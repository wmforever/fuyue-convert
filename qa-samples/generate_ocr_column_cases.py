#!/usr/bin/env python3
"""Independent two-column prose and ambiguous table/header controls, frozen without OCR."""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from generate_ocr_shadow_cases import ROOT, EN, ZH


def main():
    out=ROOT/'qa-samples/generated/cloud-column-independent';out.mkdir(parents=True,exist_ok=True)
    # Font versions/hashes are recorded by the independent shading generator.
    fonts=json.loads((ROOT/'qa-samples/generated/cloud-shadow-independent/expected.json').read_text())['fonts']
    paths={'sans':ROOT/'task-service/src/main/resources/fonts/LiberationSans-Regular.ttf',
           'serif':Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
           'cjk':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')}
    cases=[]
    for name,family,angle in [('en-serif-columns3','serif',3),('en-sans-columns-negative3','sans',-3),
                              ('zh-cjk-columns3','cjk',3),('zh-cjk-columns-upright','cjk',0),
                              ('en-table-control','sans',0),('en-header-control','sans',0)]:
        image=Image.new('RGB',(1800,1200),'white');draw=ImageDraw.Draw(image)
        font=ImageFont.truetype(str(paths[family]),32 if family=='cjk' else 28)
        lines=ZH if family=='cjk' else EN
        expected=list(lines)
        if 'table' in name:
            expected=[f'Item {i+1} Amount 00{i+7}.50' for i in range(3)]
            for i in range(3):
                draw.text((120,230+i*250),f'Item {i+1}',font=font,fill='black',anchor='ls')
                draw.text((1000,230+i*250),f'Amount 00{i+7}.50',font=font,fill='black',anchor='ls')
        else:
            for i,line in enumerate(lines):draw.text((120 if i<3 else 1000,230+(i%3)*250),line,font=font,fill='black',anchor='ls')
            if 'header' in name:
                heading='A spanning heading crosses the gutter between both independent columns'
                draw.text((120,90),heading,font=font,fill='black',anchor='ls')
                expected=[heading,*lines]
        tilted=image.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white')
        path=out/(name+'.png');tilted.save(path,dpi=(300,300));image.close();tilted.close()
        cases.append({'file':path.name,'expectedLines':expected,'font':family,'sourceSkewDegrees':angle,
                      'layout':'table-control' if 'table' in name else 'header-control' if 'header' in name else 'columns',
                      'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (out/'expected.json').write_text(json.dumps({'provenance':'Independent synthetic columns and ambiguity controls; frozen before evaluation',
        'fonts':fonts,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    print('Frozen 6 column cases')


if __name__=='__main__':main()
