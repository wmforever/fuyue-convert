#!/usr/bin/env python3
"""Freeze controlled OFD selection/warning responses, not native OCR accuracy."""
import argparse,hashlib,json,pathlib,subprocess
from PIL import Image,ImageDraw,ImageFont,__version__ as pillow_version
ROOT=pathlib.Path(__file__).resolve().parents[1]
HEADER='level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--classpath',required=True);p.add_argument('--out',type=pathlib.Path,default=ROOT/'qa-samples/generated/cloud-iteration18')
    a=p.parse_args();out=a.out.resolve();assert not out.exists();out.mkdir(parents=True)
    fontpath=pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf')
    assert sha(fontpath)=='5883330d94debd992952cd8f0571b225f478c2d797d3f36c7521b0a5c9bde0f2'
    font=ImageFont.truetype(str(fontpath),42)
    def row(line,word,x,y,width,text,confidence):return [5,1,1,1,line,word,x,y,width,60,confidence,text]
    def prose(text,line,y):
        x=60;rows=[]
        for i,word in enumerate(text.split(),1):
            width=int(ImageDraw.Draw(Image.new('RGB',(1,1))).textlength(word,font=font))+10
            rows.append(row(line,i,x,y,width,word,98));x+=width+12
        assert x<1140;return rows
    original={
      'full':[row(1,1,60,60,270,'FULL03121',60)],
      'partial':[row(1,1,60,60,90,'.95',96),row(1,2,210,60,150,'faint',20)],
      'reject':[row(1,1,60,60,270,'ID00424',96),row(1,2,390,60,150,'faint',20)],
      'low':[row(1,1,60,60,270,'LOW00424',60)]}
    candidates={
      'full':[row(1,1,60,60,270,'FULL03121',98)]+prose('Independent full recovery 00643',2,360),
      'partial':[row(1,1,60,60,120,'0.95',98),row(1,2,210,60,270,'corrected',98)]+prose('Recovered independent prose 00817',2,360),
      'reject':[row(1,1,60,60,270,'ID80424',98),row(1,2,390,60,150,'corrected',98)],
      'low':[row(1,1,60,60,270,'LOW00424',62)]}
    selected={'full':candidates['full'],'partial':original['partial']+candidates['partial'][2:],
              'reject':original['reject'],'low':original['low']}
    for name in original:
        image=Image.new('RGB',(1200,1200),(160,160,160));draw=ImageDraw.Draw(image)
        # Render source truth, including lines intentionally omitted by the original response.
        for r in selected[name]:draw.text((r[6],r[7]),r[11],font=font,fill=(100,100,100) if r[10]>=85 else (130,130,130))
        draw.rectangle((930,960,1050,1080),fill=(20,80,150)) # Unrecognized graphic stays in the original scan.
        image.save(out/(name+'.png'),dpi=(300,300))
        for phase,rows in [('original',original[name]),('candidate',candidates[name])]:
            (out/(name+'-'+phase+'.tsv')).write_text(HEADER+'\n'+'\n'.join('\t'.join(map(str,r)) for r in rows)+'\n')
    for layout in ['same','multi']:
        subprocess.run(['java','-cp',a.classpath,'OcrContractOfdFixture',str(out/(layout+'.ofd')),layout,
            *[str(out/(name+'.png')) for name in original]],check=True)
        for mode in ['control','treatment']:
            engine=out/(layout+'-'+mode+'-engine.py')
            engine.write_text('''#!/usr/bin/env python3
import hashlib,json,os,pathlib,shutil,sys
if '--version' in sys.argv:print('tesseract controlled-ofd-iteration18');sys.exit(0)
if '--list-langs' in sys.argv:print('List of available languages (1):\\neng');sys.exit(0)
root=pathlib.Path(__file__).resolve().parent;image=pathlib.Path(sys.argv[1]);base=pathlib.Path(sys.argv[2]);layout=LAYOUT;mode=MODE
assert base.parent.parent.name.startswith('page-') and base.parent.name.startswith('ocr-')
page=int(base.parent.parent.name.split('-')[-1])
index=int(base.parent.name.split('-')[-1]);number=(page-1)*2+index-1 if layout=='multi' else index-1
name=['full','partial','reject','low'][number];retry='tesseract-enhanced-' in image.name
phase='candidate' if retry and mode=='treatment' else 'original';response=root/(name+'-'+phase+'.tsv')
shutil.copyfile(response,str(base)+'.tsv')
with (pathlib.Path(os.environ['QA_CALL_ROOT'])/'calls.jsonl').open('a') as f:f.write(json.dumps({'page':page,'image':index,'name':name,'mode':mode,'retry':retry,'responseSha256':hashlib.sha256(response.read_bytes()).hexdigest()})+'\\n')
'''.replace('LAYOUT',repr(layout)).replace('MODE',repr(mode)));engine.chmod(0o700)
    manifest={'scope':'Controlled response/output contract; confidences injected, not native quality',
      'Pillow':pillow_version,'font':{'path':str(fontpath),'sha256':sha(fontpath),'version':'Liberation2.1.5','license':'SIL-OFL-1.1'},
      'originalRows':original,'candidateRows':candidates,'selectedRows':selected,
      'expectedPartial':['partial'],'expectedFull':['full'],'expectedRejectedNumeric':['reject'],
      'files':{p.name:sha(p) for p in sorted(out.iterdir())},'generatorSha256':sha(pathlib.Path(__file__))}
    (out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'manifestSha256':sha(out/'expected.json'),'controlledImages':4,'layouts':2,'engines':4}))

if __name__=='__main__':main()
