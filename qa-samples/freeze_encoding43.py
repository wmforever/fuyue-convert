#!/usr/bin/env python3
"""Freeze encoding pairs from existing immutable41 pixels, without rendering truth anew."""
import hashlib,json,shutil,struct
from pathlib import Path
import numpy as np
from PIL import Image,__version__
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def chunks(p):
    data=p.read_bytes();offset=8;result=[]
    while offset<len(data):
        size=struct.unpack('>I',data[offset:offset+4])[0];kind=data[offset+4:offset+8].decode('ascii');result.append(kind);offset+=size+12
    return result
def main():
    original=ROOT/'qa-samples/generated/shadow-local41';prior=json.loads((original/'expected.json').read_text())
    assert sha(original/'expected.json')=='6349bd7fda3d633f1fcb6966f33d4f84ede943ba603dbd0876d383bedb36e54d'
    out=ROOT/'qa-samples/generated/encoding43';out.mkdir(exist_ok=False);cases=[];pairs=[]
    def register(p,kind,source=None):
        image=Image.open(p);rgb=image.convert('RGB');r=dict(id=p.stem,file=p.name,kind=kind,sha256=sha(p),pixels=list(image.size),mode=image.mode,chunks=chunks(p),pillowRGBHash=hashlib.sha256(rgb.tobytes()).hexdigest())
        assert not set(r['chunks'])&{'iCCP','gAMA','sRGB','cHRM'},r
        if source:r['truthSource']=source
        cases.append(r);return r
    for case in prior['cases']:
        src=original/case['file'];assert sha(src)==case['sourceSha256']
        gray=out/(case['id']+'-gray.png');shutil.copyfile(src,gray)
        rgb=out/(case['id']+'-rgb.png');Image.open(src).convert('RGB').save(rgb,dpi=(300,300))
        a=register(gray,'raw-equivalent-gray',case);b=register(rgb,'raw-equivalent-rgb',case)
        assert a['pillowRGBHash']==b['pillowRGBHash'];pairs.append(dict(gray=a['id'],rgb=b['id'],definition='A:exact Pillow12.3 RGB sample bytes;not universal colorimetric equivalence'))
    for lang in ['en','zh']:
        src=Image.open(original/(lang+'-normal.png')).convert('RGB');array=np.asarray(src,dtype=np.uint16)
        alpha=np.broadcast_to(np.array([0,64,128,192,255],dtype=np.uint16)[np.arange(src.width)*5//src.width],array.shape[:2]).copy()
        rgba=np.concatenate([array,alpha[:,:,None]],axis=2).astype('uint8')
        matte=((array*alpha[:,:,None]+255*(255-alpha[:,:,None])+127)//255).astype('uint8')
        p=out/(lang+'-alpha.png');Image.fromarray(rgba).save(p,dpi=(300,300));a=register(p,'alpha-control')
        q=out/(lang+'-matte.png');Image.fromarray(matte).save(q,dpi=(300,300));b=register(q,'white-composite-control')
        pairs.append(dict(alpha=a['id'],matte=b['id'],definition='C:achromatic encoded-sample white composite:(g*a+255*(255-a)+127)//255;no original-text truth assigned to modified visibility'))
    ramp=np.broadcast_to(np.arange(256,dtype='uint8'),(128,256)).copy()
    p=out/'ramp-gray.png';Image.fromarray(ramp).save(p);register(p,'diagnostic-ramp')
    p=out/'ramp-rgb.png';Image.fromarray(ramp).convert('RGB').save(p);register(p,'diagnostic-ramp')
    plan=dict(parentRevision='49ea8f8f1876d155010c4e1af5e0d06a03fc05dc',pillowVersion=__version__,numpyVersion=np.__version__,priorManifestSha256=sha(original/'expected.json'),cases=cases,pairs=pairs,
        definitionB='Exact Java17 ImageIO getRGB-derived integer white-luminance bytes. Probe roundtrips these into opaqueRGB and tests enhancer equality.',
        bounds=dict(javaHeapMiB=256,pixelProbeSeconds=120,maximumNewNativeOCR=8,perOCRSeconds=25,wholeNativeSeconds=180,maximumCliRssKiB=262144,addressSpaceBytes=1073741824),
        noResampling=True,noRegeneration41=True,noProductionChange=True,noColorimetricEquivalenceClaim=True,
        stoppingRule='Pixel distinction alone is not OCR causality. At most EN/ZH gradient and normal RGB originals/enhancements at bundledPSM3;reuse41 matching originals only;no parameter sweep.')
    (out/'expected.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n');print('Frozen',len(cases),'inputs; original41 untouched')
if __name__=='__main__':main()
