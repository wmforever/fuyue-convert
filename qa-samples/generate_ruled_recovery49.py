#!/usr/bin/env python3
"""Freeze the actual48 source and a separately authored numeric grid; never overwrite evidence."""
import hashlib,json,shutil
from pathlib import Path
import fitz
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 out=ROOT/'qa-samples/generated/ruled-recovery49';out.mkdir(exist_ok=False)
 shutil.copyfile(ROOT/'qa-samples/work/iteration45-http/ruled-wrap-result.pdf',out/'ruled.pdf')
 old=json.loads((ROOT/'qa-samples/generated/scan-tables45/expected.json').read_text())['cases'][0]
 font=Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc');im=Image.new('RGB',(1920,960),'white');draw=ImageDraw.Draw(im)
 title='复核明细 Audit entries 00917';matrix=[['编号 ID','日期 Date','金额 Amount'],['01429','2096-07-18','-031.60'],['02858','2096-07-19','+0063.20'],['05716','2096-07-20','0126.40']]
 xs=[84,642,1260,1840];ys=[190,335,480,625,770]
 draw.text((84,95),title,font=ImageFont.truetype(str(font),42),fill='black',anchor='ls')
 for x in xs:draw.line((x,ys[0],x,ys[-1]),fill='black',width=4)
 for y in ys:draw.line((xs[0],y,xs[-1],y),fill='black',width=4)
 for r,row in enumerate(matrix):
  for c,s in enumerate(row):draw.text((xs[c]+30,ys[r]+88),s,font=ImageFont.truetype(str(font),48),fill='black',anchor='ls')
 im.save(out/'independent.png',dpi=(200,200))
 pdf=fitz.open();page=pdf.new_page(width=691.2,height=345.6);page.insert_image(page.rect,filename=str(out/'independent.png'));pdf.save(out/'independent.pdf');pdf.close()
 cases=[dict(id='ruled',expectedRowMajor=old['expectedRowMajor'],matrix=old['matrix'],edit=old['edit']),dict(id='independent',expectedRowMajor='\n'.join([title,*['\t'.join(r) for r in matrix]]),matrix=matrix,edit=dict(old='0126.40',new='0136.40'),gridX=xs,gridY=ys)]
 actions=[]
 for c in cases:
  n=c['id'];actions.extend([dict(id=n+'-word',input=n+'.pdf',target='docx'),dict(id=n+'-office',input='@'+n+'-word',target='pdf'),dict(id=n+'-edited-office',input='@'+n+'-word',target='pdf',edit=c['edit']),dict(id=n+'-api',input='@'+n+'-edited-office',target='txt')])
 manifest=dict(parentRevision='dba208c52659ff1d941f91f7528a47cf629d9ddd',generatorSha256=sha(Path(__file__)),font=dict(path=str(font),sha256=sha(font),license='SIL-OFL-1.1'),sources={p.name:sha(p) for p in out.iterdir()},cases=cases,actions=actions,bounds=dict(maximumHttpContracts=8,contractSeconds=120,matrixSeconds=480),acceptance='Actual editable fields, original scan bytes/number boxes, visible edit and API values; complete native table structure remains outside this recovery')
 (out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 baseline=ROOT/'qa-samples/generated/ruled-recovery49-before';baseline.mkdir(exist_ok=False);shutil.copyfile(out/'independent.pdf',baseline/'independent.pdf');manifest['actions']=[actions[4]];manifest['sources']={'independent.pdf':sha(baseline/'independent.pdf')};(baseline/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 print('Frozen one existing source and one independent 12-cell source; 1 new baseline + 8 affected after contracts')
if __name__=='__main__':main()
