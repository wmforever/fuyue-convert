#!/usr/bin/env python3
"""Freeze a bounded sparse true-tilt follow-on after dense controls failed qualification."""
import argparse,hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,__version__ as pillow_version
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,default=ROOT/'qa-samples/generated/cloud-iteration12-sparse');args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True);assert not (args.out/'expected.json').exists(),'Never overwrite frozen corpus'
 parent=ROOT/'docs/cloud-ocr-iteration12-corpus.json';assert sha(parent)=='d3bc5e7ac0beb0777ea19332b218f84498ecc22f183ead6f841de15c8b645195';meta=json.loads(parent.read_text())['fonts']['mono'];font=Path(meta['path']);assert sha(font)==meta['sha256'];cases=[]
 for size,rows in [(32,3),(44,3),(52,4)]:
  image=Image.new('RGB',(3200,1700),'white');d=ImageDraw.Draw(image);face=ImageFont.truetype(str(font),size);truth=[]
  for c,label in enumerate(['Larch','Alder','Aspen']):
   for r in range(rows):
    text=f'{label} keeps lot{351+c*10+r:03d}';x=140+c*960;y=270+r*280;assert d.textlength(text,font=face)<780;d.text((x,y),text,font=face,fill='black',anchor='ls');truth.append(text)
  for angle in [0,-1.1,1.1,-1.6]:
   im=image if angle==0 else image.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white');out=args.out/f'mono{size}-sparse-angle{angle:+.2f}.png';im.save(out,dpi=(300,300));cases.append({'file':out.name,'sha256':sha(out),'expectedLines':truth,'font':'mono','fontSize':size,'layout':'three-columns','sourceSkewDegrees':angle,'dpi':300,'rowCounts':[rows]*3,'rowStep':280,'columnStagger':[0,0,0]})
 manifest={'seed':120142026,'provenance':'Independent public synthetic sparse true-tilt supplement frozen before production edits, after dense32 native controls failed strict qualification','license':'Apache-2.0 generated text/code; separate SIL-OFL-1.1 fonts','fonts':{'mono':meta},'versions':{'Pillow':pillow_version},'parentManifestSha256':sha(parent),'generatorSha256':sha(Path(__file__)),'limits':['Bounded follow-on selected for geometry coverage; not a random population or deskew guarantee'],'cases':cases,'containerCases':[]};out=args.out/'expected.json';out.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('Frozen12 sparse controls',sha(out))
if __name__=='__main__':main()
