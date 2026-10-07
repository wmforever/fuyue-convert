#!/usr/bin/env python3
"""Independent numeric edits on white, gray, gradient and uncertain scan paper."""
from pathlib import Path
import argparse,hashlib,json,shutil
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'qa-samples/generated/edit-iteration26';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--via-pdf',action='store_true');args=parser.parse_args()
 if args.via_pdf:
  # Preserve the initial direct PNG->Word control, which has no scan overlay.
  # Freeze a separate dependency graph for the actual failing PDF scan route.
  target=OUT.with_name('edit-iteration26-pdf');assert not target.exists();target.mkdir(parents=True)
  m=json.loads((OUT/'expected.json').read_text());m['parentManifestSha256']=sha(OUT/'expected.json');m['route']='PNG->PDF->scan DOCX->edited Office PDF->TXT';m['routeGeneratorSha256']=sha(Path(__file__));actions=[]
  for file,digest in m['sources'].items():assert sha(OUT/file)==digest;shutil.copyfile(OUT/file,target/file)
  for action in m['actions']:
   if action['id'].endswith('-word'):
    name=action['id'].removesuffix('-word');actions.append({'id':name+'-pdf','input':action['input'],'target':'pdf','edit':None});action['input']='@'+name+'-pdf'
   actions.append(action)
  m['actions']=actions;(target/'expected.json').write_text(json.dumps(m,indent=2)+'\n');print(json.dumps({'cases':len(m['cases']),'httpBudget':len(actions),'manifestSha256':sha(target/'expected.json')}));return
 assert not OUT.exists();OUT.mkdir(parents=True);font=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');face=ImageFont.truetype(str(font),56);cases=[];actions=[];sources={}
 for name,old,new,paper in [('decimal','312.40','319.65','white'),('negative','-48.20','-49.70','white'),('leading-zero','00412.30','00419.80','white'),('shorter-gray','9827.50','27.50','gray'),('longer-gray','7.50','187.50','gray'),('shadow','-062.40','-068.95','shadow'),('uncertain','542.10','542.70','uncertain')]:
  im=Image.new('RGB',(1500,1150),'white')
  if paper=='gray':im.paste((226,226,226),(0,0,1500,1150))
  if paper=='shadow':
   dr=ImageDraw.Draw(im)
   for y in range(1150):v=round(195+45*y/1149);dr.line((0,y,1499,y),fill=(v,v,v))
  dr=ImageDraw.Draw(im);lines=['EDIT REVIEW 2041','Record 00571','Amount '+old,'Date 2041-08-23','Control 38.25'];ys=[90,280,470,700,920]
  for text,y in zip(lines,ys,strict=True):dr.text((100,y),text,font=face,fill='black')
  if paper=='uncertain':
   # A colored annotation crosses the amount's sampling ring; never erase it by guessing paper.
   x=100+round(dr.textlength('Amount ',font=face));dr.line((x,479,x+round(dr.textlength(old,font=face)),479),fill=(210,20,50),width=3)
  file=name+'.png';im.save(OUT/file,dpi=(300,300));sources[file]=sha(OUT/file);cases.append({'id':name,'file':file,'paper':paper,'old':old,'new':new,'expected':'\n'.join(lines)+'\n','editedExpected':'\n'.join(lines).replace(old,new)+'\n','editingNotPromised':paper=='uncertain'})
  for stage,source,target,edit in [('word',file,'docx',None),('office','@'+name+'-word','pdf',None),('edited-office','@'+name+'-word','pdf',{'old':old,'new':new}),('edited-text','@'+name+'-edited-office','txt',None)]:actions.append({'id':name+'-'+stage,'input':source,'target':target,'edit':edit})
 m={'generatorSha256':sha(Path(__file__)),'parentRevision':'ea5467d5f11748c6a4246abaeba5fa04ed62dbab','sources':sources,'cases':cases,'actions':actions,'font':{'path':str(font),'sha256':sha(font),'version':'LiberationSans2.1.5','license':'SIL-OFL-1.1'}};(OUT/'expected.json').write_text(json.dumps(m,indent=2)+'\n');print(json.dumps({'cases':len(cases),'httpBudget':len(actions),'manifestSha256':sha(OUT/'expected.json')}))
if __name__=='__main__':main()
