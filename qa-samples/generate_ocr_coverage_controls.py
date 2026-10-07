#!/usr/bin/env python3
"""Non-text coverage controls, including a separately downloaded NASA photograph.

Photo: scikit-image v0.19.3 skimage/data/astronaut.png, public-domain NASA image.
See https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut
The photo and all outputs stay in ignored QA directories; no faces are committed.
"""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw
from generate_ocr_shadow_cases import ROOT


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--photo',type=Path,required=True)
    args=parser.parse_args();out=ROOT/'qa-samples/generated/cloud-coverage-controls';out.mkdir(parents=True,exist_ok=True)
    source=args.photo.read_bytes();photo=Image.open(args.photo).convert('RGB');cases=[]
    for name in ['photo','photo-on-shadow','logo-on-shadow','red-marks','border','halftone-dense','halftone-sparse']:
        image=Image.new('RGB',(1600,1100));draw=ImageDraw.Draw(image)
        for x in range(1600):
            gray=90+x*130//1600;draw.line((x,0,x,1100),fill=(gray,)*3)
        if name=='photo':image.close();image=photo.copy()
        elif name=='photo-on-shadow':image.paste(photo,(500,250))
        elif name=='logo-on-shadow':
            draw.ellipse((560,320,900,660),fill=(10,60,180));draw.polygon([(730,350),(630,600),(850,600)],fill='white')
        elif name=='red-marks':
            for y in [170,500,850]:draw.ellipse((1200,y,1280,y+60),fill=(200,20,20))
        elif name=='border':draw.rectangle((50,50,1550,1050),outline='black',width=8)
        else:
            spacing=8 if name=='halftone-dense' else 20
            for y in range(80,1020,spacing):
                for x in range(80,1520,spacing):draw.ellipse((x,y,x+3,y+3),fill='black')
        path=out/(name+'.png');image.save(path);image.close()
        cases.append({'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'provenance':'NASA photograph' if 'photo' in name else 'synthetic non-text control'})
    photo.close()
    (out/'controls.json').write_text(json.dumps({'photoSource':'https://raw.githubusercontent.com/scikit-image/scikit-image/v0.19.3/skimage/data/astronaut.png',
        'photoSha256':hashlib.sha256(source).hexdigest(),'photoLicense':'Public domain; NASA; documented by scikit-image',
        'cases':cases},indent=2)+'\n')
    print('Frozen 7 coverage controls')


if __name__=='__main__':main()
