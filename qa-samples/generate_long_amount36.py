#!/usr/bin/env python3
"""Freeze six independent bilingual long-edit scans and a finite real HTTP graph."""
import argparse,hashlib,json,re
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--correct-zh',action='store_true')
    parser.add_argument('--legacy-missing-latin',action='store_true',help='Reproduce the rejected first Chinese source-font defect only')
    args=parser.parse_args()
    out=ROOT/('qa-samples/generated/long-amount36-zh-latin' if args.correct_zh else 'qa-samples/generated/long-amount36');out.mkdir(exist_ok=False)
    latin=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');cjk=ROOT/'task-service/src/main/resources/fonts/DroidSansFallback.ttf'
    specs=[('en-positive','en','048.65','123456789012.65',False),('en-negative','en','-054.80','-987654321098.80',False),
           ('en-adjacent-zero','en','00062.35','000000123456789.35',True),('zh-positive','zh','048.65','123456789012.65',False),
           ('zh-negative-zero','zh','-054.80','-000123456789.80',False),('zh-adjacent-zero','zh','00062.35','000000123456789.35',True)]
    sources={};actions=[];cases=[]
    for name,lang,old,new,adjacent in specs:
        if args.correct_zh and lang!='zh':continue
        font=ImageFont.truetype(str(latin if lang=='en' else cjk),56)
        lines=(['LONG EDIT REVIEW 2097','Record 00783','Amount '+old,'Date 2097-11-06','Control 21.40'] if lang=='en' else
               ['长金额编辑核验 2097','记录编号 00783','金额 '+old,'日期 2097-11-06','保留相邻文字 21.40'])
        if adjacent:lines[2]+='  TAIL 2097'
        image=Image.new('RGB',(2000,1700),'white');draw=ImageDraw.Draw(image);lineBoxes=[]
        for text,y in zip(lines,[140,400,660,1000,1320],strict=True):
            if lang=='zh' and not args.legacy_missing_latin:
                x=160;boxes=[]
                for segment in re.findall(r'[^\x00-\x7f]+|[\x00-\x7f]+',text):
                    face=ImageFont.truetype(str(latin if segment.isascii() else cjk),56)
                    draw.text((x,y),segment,font=face,fill='black');boxes.append(draw.textbbox((x,y),segment,font=face));x+=draw.textlength(segment,font=face)
                lineBoxes.append([min(b[0] for b in boxes),min(b[1] for b in boxes),max(b[2] for b in boxes),max(b[3] for b in boxes)])
            else:
                draw.text((160,y),text,font=font,fill='black');lineBoxes.append(list(draw.textbbox((160,y),text,font=font)))
        path=out/(name+'.png');image.save(path,dpi=(300,300));sources[path.name]=sha(path)
        cases.append(dict(id=name,language=lang,old=old,new=new,adjacent=adjacent,expected='\n'.join(lines)+'\n',editedExpected='\n'.join(lines).replace(old,new)+'\n',sourceLineBoxesPixels=lineBoxes,pixelSize=[2000,1700],dpi=300,sourceFontSha256=sha(latin if lang=='en' else cjk)))
        for stage,source,target,edit in [('scan',path.name,'pdf',None),('word','@'+name+'-scan','docx',None),('office','@'+name+'-word','pdf',None),
                                        ('edited-office','@'+name+'-word','pdf',dict(old=old,new=new)),('edited-text','@'+name+'-edited-office','txt',None)]:
            action=dict(id=name+'-'+stage,input=source,target=target)
            if edit:action['edit']=edit
            actions.append(action)
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,cases=cases,actions=actions,parentRevision='7a3ceb25835b0fa2e032f4fe3cf158487aaafa90',generatorSha256=sha(Path(__file__)),fonts=[dict(path=str(p),sha256=sha(p)) for p in [latin,cjk]],ocrParameterTuning=False),indent=2,ensure_ascii=False)+'\n')
    print(f'{len(cases)} frozen scans;{len(actions)} bounded requests;correctedLatinForChinese={args.correct_zh}')
if __name__=='__main__':main()
