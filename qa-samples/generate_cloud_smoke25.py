#!/usr/bin/env python3
"""Two original synthetic inputs, frozen cross-format integration truth and actions."""
from pathlib import Path
import hashlib,json
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'qa-samples/generated/cloud-smoke25'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 assert not OUT.exists();OUT.mkdir(parents=True)
 digital='审核记录 REVIEW RECORD 2036\n编号 Record 00842\n金额 Amount 127.50\n日期 Date 2036-04-19\n'
 scan='REVIEW RECORD 2036\nRecord 00842\nAmount 127.50\nDate 2036-04-19\n'
 (OUT/'record.txt').write_text(digital,encoding='utf-8');font=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');im=Image.new('RGB',(1500,1000),'white');draw=ImageDraw.Draw(im);face=ImageFont.truetype(str(font),58)
 for i,line in enumerate(scan.splitlines()):draw.text((100,95+170*i),line,font=face,fill='black')
 im.save(OUT/'record-scan.png',dpi=(300,300));actions=[]
 def action(name,source,target,truth,edit=False):actions.append({'id':name,'input':source,'target':target,'truth':truth,'edit':{'old':'127.50','new':'128.75'} if edit else None})
 action('digital-word','record.txt','docx','digital');action('digital-word-text','@digital-word','txt','digital');action('digital-office','@digital-word','pdf','digital');action('digital-pdf-text','@digital-office','txt','digital');action('digital-pdf-word','@digital-office','docx','digital');action('digital-edited-office','@digital-pdf-word','pdf','digital-edited',True);action('digital-edited-text','@digital-edited-office','txt','digital-edited');action('digital-ofd','@digital-office','ofd','digital');action('digital-ofd-text','@digital-ofd','txt','digital');action('digital-ofd-word','@digital-ofd','docx','digital');action('digital-ofd-pdf','@digital-ofd','pdf','digital')
 action('scan-text','record-scan.png','txt','scan');action('scan-pdf','record-scan.png','pdf','scan');action('scan-word','@scan-pdf','docx','scan');action('scan-office','@scan-word','pdf','scan');action('scan-ofd','@scan-pdf','ofd','scan');action('scan-ofd-text','@scan-ofd','txt','scan');action('scan-ofd-word','@scan-ofd','docx','scan');action('scan-edited-office','@scan-word','pdf','scan-edited',True);action('scan-edited-text','@scan-edited-office','txt','scan-edited')
 manifest={'parentRevision':'da34d5cbb18e14d8b6c21858c540500908e1431a','provenance':'Original synthetic numeric bilingual text and clean English scan; no private inputs','generatorSha256':sha(Path(__file__)),'font':{'path':str(font),'sha256':sha(font),'license':'SIL-OFL-1.1','version':'LiberationSans2.1.5'},'sources':{n:sha(OUT/n) for n in ['record.txt','record-scan.png']},'truth':{'digital':digital,'scan':scan,'digital-edited':digital.replace('127.50','128.75'),'scan-edited':scan.replace('127.50','128.75')},'actions':actions}
 (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'actions':len(actions),'sources':2,'manifestSha256':sha(OUT/'expected.json')}))
if __name__=='__main__':main()
