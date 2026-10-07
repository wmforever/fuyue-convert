#!/usr/bin/env python3
"""Freeze independent longer-number edits; --after reuses completed scan PDFs."""
from pathlib import Path
import argparse,hashlib,json,os
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/wrap-iteration28'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--after',action='store_true');a=p.parse_args()
 out=OUT/('after' if a.after else 'before');out.mkdir(parents=True,exist_ok=False);sources={};actions=[];cases=[]
 def add(name,source):
  data=source.read_bytes();dest=out/name;dest.write_bytes(data);sources[name]=sha(dest);return name
 if not a.after:
  font=ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',56)
  for name,old,new,gray in [('white','4.10','541.80',255),('negative','-6.20','-346.70',226),('zeros','004.90','00034.90',255),('blocked','4.10','541.80',255)]:
   im=Image.new('RGB',(1500,1150),(gray,gray,gray));dr=ImageDraw.Draw(im);lines=['EDIT RESERVE 2053','Record 00621','Amount '+old,'Date 2053-07-16','Control 32.45']
   for text,y in zip(lines,[90,280,470,700,920],strict=True):dr.text((100,y),text,font=font,fill='black')
   if name=='blocked':dr.line((500,480,500,525),fill=(220,0,20),width=3)
   path=out/(name+'.png');im.save(path,dpi=(300,300));sources[path.name]=sha(path)
   cases.append(dict(id=name,old=old,new=new,expected='\n'.join(lines)+'\n',editedExpected='\n'.join(lines).replace(old,new)+'\n',blocked=name=='blocked'))
   for stage,source,target in [('pdf',path.name,'pdf'),('word','@'+name+'-pdf','docx'),('office','@'+name+'-word','pdf')]:actions.append(dict(id=name+'-'+stage,input=source,target=target))
   if name!='blocked':actions.extend([dict(id=name+'-edited-office',input='@'+name+'-word',target='pdf',edit=dict(old=old,new=new)),dict(id=name+'-text',input='@'+name+'-edited-office',target='txt')])
  source=ROOT/'qa-samples/report/iteration26-scan-after/longer-gray-edited-office-result.pdf';name=add('original-edited.pdf',source);actions.append(dict(id='original-text',input=name,target='txt'))
 else:
  before=json.loads((OUT/'before/expected.json').read_text());cases=before['cases'];base=ROOT/'qa-samples/report/iteration28-before'
  for c in [dict(id='original',old='7.50',new='187.50',blocked=False)]+cases:
   name=c['id'];source=ROOT/'qa-samples/report/iteration26-scan-after/longer-gray-pdf-result.pdf' if name=='original' else base/(name+'-pdf-result.pdf');file=add(name+'-scan.pdf',source)
   actions.extend([dict(id=name+'-word',input=file,target='docx'),dict(id=name+'-office',input='@'+name+'-word',target='pdf')])
   if not c['blocked']:actions.extend([dict(id=name+'-edited-office',input='@'+name+'-word',target='pdf',edit=dict(old=c['old'],new=c['new'])),dict(id=name+'-text',input='@'+name+'-edited-office',target='txt')])
  file=add('dark-scan.pdf',ROOT/'qa-samples/report/iteration27-before/dark-pdf-result.pdf');actions.append(dict(id='dark-word',input=file,target='docx'))
 (out/'expected.json').write_text(json.dumps(dict(generatorSha256=sha(Path(__file__)),sources=sources,cases=cases,actions=actions),indent=2)+'\n');print(json.dumps(dict(actions=len(actions),manifestSha256=sha(out/'expected.json'))))
if __name__=='__main__':main()
