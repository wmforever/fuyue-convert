#!/usr/bin/env python3
"""Independent numeric edits on white, gray, gradient and uncertain scan paper."""
from pathlib import Path
import hashlib,json
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'qa-samples/generated/edit-iteration27';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 assert not OUT.exists();OUT.mkdir(parents=True);font=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');face=ImageFont.truetype(str(font),56);cases=[];actions=[];sources={}
 for name,old,new,paper in [('decimal','842.60','849.35','white'),('negative','-73.40','-78.90','white'),('leading-zero','00085.20','00089.70','white'),('gray','93.00','94.50','gray'),('dark','64.20','69.70','dark')]:
  im=Image.new('RGB',(1500,1150),'white')
  if paper=='gray':im.paste((226,226,226),(0,0,1500,1150))
  if paper=='dark':im.paste((60,60,60),(0,0,1500,1150))
  if paper=='shadow':
   dr=ImageDraw.Draw(im)
   for y in range(1150):v=round(195+45*y/1149);dr.line((0,y,1499,y),fill=(v,v,v))
  dr=ImageDraw.Draw(im);lines=['API REVIEW 2047','Record 00963','Amount '+old,'Date 2047-03-18','Control 26.85'];ys=[90,280,470,700,920]
  for text,y in zip(lines,ys,strict=True):dr.text((100,y),text,font=face,fill='white' if paper=='dark' else 'black')
  if paper=='uncertain':
   # A colored annotation crosses the amount's sampling ring; never erase it by guessing paper.
   x=100+round(dr.textlength('Amount ',font=face));dr.line((x,479,x+round(dr.textlength(old,font=face)),479),fill=(210,20,50),width=3)
  file=name+'.png';im.save(OUT/file,dpi=(300,300));sources[file]=sha(OUT/file);cases.append({'id':name,'file':file,'paper':paper,'old':old,'new':new,'expected':'\n'.join(lines)+'\n','editedExpected':'\n'.join(lines).replace(old,new)+'\n','editingNotPromised':paper=='dark'})
  for stage,source,target,edit in [('pdf',file,'pdf',None),('word','@'+name+'-pdf','docx',None),('office','@'+name+'-word','pdf',None),('edited-office','@'+name+'-word','pdf',{'old':old,'new':new}),('edited-text','@'+name+'-edited-office','txt',None)]:actions.append({'id':name+'-'+stage,'input':source,'target':target,'edit':edit})
 m={'generatorSha256':sha(Path(__file__)),'parentRevision':'6d163a92806253a2a2895c2dee7088d0e4fd3bba','sources':sources,'cases':cases,'actions':actions,'font':{'path':str(font),'sha256':sha(font),'version':'LiberationSans2.1.5','license':'SIL-OFL-1.1'}};(OUT/'expected.json').write_text(json.dumps(m,indent=2)+'\n');print(json.dumps({'cases':len(cases),'httpBudget':len(actions),'manifestSha256':sha(OUT/'expected.json')}))
if __name__=='__main__':main()
