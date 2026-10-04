#!/usr/bin/env python3
"""Freeze independent scale controls before any OCR; all values are synthetic."""
import hashlib,json,re,random
from pathlib import Path
import fitz
from PIL import Image,ImageDraw,ImageFont,__version__ as pillow_version
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/numeric-scale38';out.mkdir(exist_ok=False)
    latin=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');cjk=ROOT/'task-service/src/main/resources/fonts/DroidSansFallback.ttf'
    cases=[];sources={};actions=[]
    for lang in ['en','zh']:
        for size in [56,38,24]:
            name=f'{lang}-{size}'
            lines=(['NUMERIC SOURCE REVIEW 2089','Record 00946','Date 2089-07-23','Amount +073.26','Balance -0058.40','Separate 12 34','Rate 15.25%'] if lang=='en' else
                   ['独立数字核验 2089','记录编号 00946','日期 2089-07-23','金额 +073.26','余额 -0058.40','分开数字 12 34','利率 15.25%'])
            image=Image.new('RGB',(2000,1700),'white');draw=ImageDraw.Draw(image);boxes=[]
            for text,y in zip(lines,[120,330,540,750,960,1170,1380],strict=True):
                x=150;parts=[]
                for segment in re.findall(r'[^\x00-\x7f]+|[\x00-\x7f]+',text):
                    font=ImageFont.truetype(str(latin if segment.isascii() else cjk),size)
                    draw.text((x,y),segment,font=font,fill='black');parts.append(draw.textbbox((x,y),segment,font=font));x+=draw.textlength(segment,font=font)
                boxes.append([min(b[0] for b in parts),min(b[1] for b in parts),max(b[2] for b in parts),max(b[3] for b in parts)])
            png=out/(name+'.png');image.save(png,dpi=(300,300))
            cases.append(dict(id=name,language=lang,fontPixels=size,expected='\n'.join(lines)+'\n',pixelSize=[2000,1700],sourceLineBoxesPixels=boxes,control=False))
    for name in ['blank','noise']:
        image=Image.new('RGB',(2000,1700),'white')
        if name=='noise':
            draw=ImageDraw.Draw(image);rng=random.Random(380046)
            for _ in range(24):
                x,y=rng.randrange(20,1980),rng.randrange(20,1680);draw.rectangle((x,y,x+1,y+1),fill=(155,155,155))
        image.save(out/(name+'.png'),dpi=(300,300));cases.append(dict(id=name,expected='',pixelSize=[2000,1700],control=True))
    for c in cases:
        png=out/(c['id']+'.png');sources[png.name]=sha(png)
        # Native header deliberately occupies one sparse band, outside the scan.
        # This exercises embedded-image OCR rather than 300DPI whole-page raster OCR.
        doc=fitz.open();page=doc.new_page(width=480,height=438);page.insert_text((20,12),'INDEPENDENT HEADER 0061',fontsize=8)
        page.insert_image(fitz.Rect(0,24,480,432),stream=png.read_bytes());pdf=out/(c['id']+'.pdf');doc.save(pdf);doc.close();sources[pdf.name]=sha(pdf)
        c['nativeExpected']='INDEPENDENT HEADER 0061\n';c['physicalImageBoxMm']=[0,24*25.4/72,480*25.4/72,408*25.4/72]
        for target in (['txt'] if c['control'] else ['txt','docx']):actions.append(dict(id=c['id']+'-'+target,input=pdf.name,target=target))
    manifest=dict(cases=cases,sources=sources,actions=actions,parentRevision='822442bb0df0fa1adda5c246b840ed9d6ab0c9d6',generatorSha256=sha(Path(__file__)),pillowVersion=pillow_version,pymupdfVersion=fitz.VersionBind,fonts=[dict(path=str(p),sha256=sha(p)) for p in [latin,cjk]],freezeBeforeOcr=True)
    (out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('Frozen 6 independent bilingual text scans + blank/noise; 14 HTTP actions; no OCR invoked')
if __name__=='__main__':main()
