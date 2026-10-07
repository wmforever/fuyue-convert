#!/usr/bin/env python3
"""Freeze new true-tilt guard risk controls before algorithm edits; never reuse old samples."""
import argparse,hashlib,json,random
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,__version__ as pillow_version
from fontTools.ttLib import TTFont
ROOT=Path(__file__).resolve().parents[1]
SEED=120042026

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,default=ROOT/'qa-samples/generated/cloud-iteration12');args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True)
 assert not (args.out/'expected.json').exists(),'Never overwrite frozen truth'
 fonts={'mono':Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'),'serif':Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),'sans':Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'),'cjk':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')};fm={}
 for name,path in fonts.items():
  with TTFont(path,fontNumber=0) as f:fm[name]={'path':str(path),'sha256':sha(path),'versions':sorted({n.toUnicode() for n in f['name'].names if n.nameID==5}),'license':'SIL-OFL-1.1','source':'https://github.com/notofonts/noto-cjk' if name=='cjk' else 'https://github.com/liberationfonts/liberation-fonts'}
 # Short columns specifically test geometry that may remain aligned after true tilt.
 configs=[('mono-short','mono',35,[3,3,4],95,[0,0,0],'short'),('mono-stagger','mono',33,[4,3,4],110,[0,18,-12],'short'),('mono-numeric','mono',32,[3,4,3],125,[0,10,20],'numeric'),('serif-short','serif',40,[4,3,4],105,[0,0,0],'short'),('sans-unequal','sans',38,[3,4,4],120,[0,15,0],'numeric'),('bilingual-stagger','cjk',36,[4,3,4],110,[0,-15,15],'bilingual')]
 cases=[];rng=random.Random(SEED)
 for name,family,size,counts,step,stagger,kind in configs:
  im=Image.new('RGB',(2800,1500),'white');d=ImageDraw.Draw(im);face=ImageFont.truetype(str(fonts[family]),size);truth=[];lines=[]
  for c,label in enumerate(['Birch','Hazel','Maple']):
   for r in range(counts[c]):
    token=f'lot{61+c*10+r:03d}'
    if kind=='numeric':token=[f'Date2028-05-{17+c:02d}',f'USD-0{43+c}.70',f'qty00{6+c}',f'rate0{7+c}.25%'][r]
    elif kind=='bilingual':token=f'档案{241+c*10+r:05d}号'
    line=f'{label} keeps {token}';x=180+c*850;y=470+r*step+stagger[c]
    assert d.textlength(line,font=face)<720
    d.text((x,y),line,font=face,fill='black',anchor='ls');truth.append(line);lines.append({'text':line,'column':c,'baseline':[x,y]})
  # Each pair has newly rendered text/geometry. Actual signed transform truth is recorded.
  for angle in [0,-1.05,1.05,-1.4,2.2]:
   fn=f'{name}-angle{angle:+.2f}.png';out=args.out/fn
   rendered=im if angle==0 else im.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white')
   rendered.save(out,dpi=(300,300))
   cases.append({'file':fn,'sha256':sha(out),'expectedLines':truth,'layout':'three-columns','font':family,'fontSize':size,'sourceSkewDegrees':angle,'dpi':300,'rowCounts':counts,'rowStep':step,'columnStagger':stagger,'unrotatedLineEvidence':lines,'purpose':'independent genuinely tilted short/unequal multi-column guard-risk control' if angle else 'new upright matched control'})
  im.close()
 for name,angle in [('blank',2.2),('noise',-1.4)]:
  im=Image.new('RGB',(2800,1500),'white');d=ImageDraw.Draw(im)
  if name=='noise':
   for _ in range(41):
    x,y=rng.randrange(2800),rng.randrange(1500);d.rectangle((x,y,x+1,y+1),fill='black')
  im=im.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white');out=args.out/(name+'-tilted.png');im.save(out,dpi=(300,300));cases.append({'file':out.name,'sha256':sha(out),'expectedLines':[],'font':None,'layout':'blank-noise','sourceSkewDegrees':angle,'expectedErrors':['OCR_NO_TEXT','OCR_LOW_CONFIDENCE'],'dpi':300})
 manifest={'seed':SEED,'provenance':'Independent public synthetic true-tilt risk corpus frozen before algorithm edits; new text/layouts, not prior angle corpus','license':'Apache-2.0 generated text/code; separate SIL-OFL-1.1 fonts','fonts':fm,'versions':{'Pillow':pillow_version},'generatorSha256':sha(Path(__file__)),'limits':['Synthetic horizontal-print columns; no private handwriting or native Word acceptance','Short column spans intentionally challenge original fragmentation while truly tilted','Angles are applied transforms, not detector estimates; no inference of table intent'],'cases':cases,'containerCases':[]}
 out=args.out/'expected.json';out.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'contracts':len(cases),'genuinelyTiltedTextCases':24,'uprightControls':6,'negativeControls':2,'seed':SEED,'manifestSha256':sha(out)}))
if __name__=='__main__':main()
