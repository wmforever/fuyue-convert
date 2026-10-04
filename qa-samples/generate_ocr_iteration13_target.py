#!/usr/bin/env python3
"""Freeze final targeted group after diagnosed merged-label rejection; no arbitrary sweep."""
import argparse,hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,__version__ as pillow_version
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,default=ROOT/'qa-samples/generated/cloud-iteration13-target');args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True);assert not (args.out/'expected.json').exists(),'Never overwrite frozen truth'
 parent=json.loads((ROOT/'docs/cloud-ocr-iteration11-corpus.json').read_text());font=parent['fonts']['mono'];path=Path(font['path']);assert sha(path)==font['sha256'];face=ImageFont.truetype(str(path),29);source=Image.new('RGB',(2400,1500),'white');draw=ImageDraw.Draw(source);truth=[];baselines=[]
 for c,label in enumerate(['Spruce','Lagoon','Copper']):
  for r in range(4):
   text=f'{label} keeps ticket{r+7:02d}';x=120+c*720;y=250+r*290+c*35;draw.text((x,y),text,font=face,fill='black',anchor='ls');truth.append(text);baselines.append({'text':text,'x':x,'y':y,'column':c})
 cases=[]
 for angle in [0,-.35,.35,-.65,.65]:
  image=source if angle==0 else source.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white');out=args.out/f'spruce-stagger-angle{angle:+.2f}.png';image.save(out,dpi=(300,300));cases.append({'file':out.name,'sha256':sha(out),'expectedLines':truth,'font':'mono','sourceSkewDegrees':angle,'dpi':300,'layout':'three-columns','fontSize':29,'rowStep':290,'columnStagger':[0,35,70],'unrotatedBaselines':baselines})
 manifest={'seed':130142026,'provenance':'One bounded targeted correction of merged Orchid-keeps third-column rejection, using known separated Copper label; fixed four modest true-angle transforms chosen analytically before OCR measurements or production edits','license':'Apache-2.0 generated text/code; separate SIL-OFL-1.1 font','fonts':{'mono':font},'versions':{'Pillow':pillow_version},'generatorSha256':sha(Path(__file__)),'angleSelectionBasis':'870px row span and roughly21-24px ink height give half-height edge budget near0.69-0.79deg; choose±0.35/±0.65, not a sweep','limits':['Native qualification of this distinct upright rendering must be independently verified; shared layout is not sufficient','Detector minimum1degree means these true modest angles can still have a false larger global stagger projection','No claim of broad native angle safety or intent inference'],'cases':cases,'containerCases':[]};out=args.out/'expected.json';out.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('Frozen5 targeted controls',sha(out))
if __name__=='__main__':main()
