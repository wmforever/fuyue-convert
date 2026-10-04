#!/usr/bin/env python3
"""Freeze independent extraction/visible-raster proof controls; no production edits."""
import hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import fitz
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/route34';out.mkdir(exist_ok=False)
    fontpath=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');font=ImageFont.truetype(str(fontpath),56)
    lines=['ROUTE CONTROL 2088','Record 00973','Amount 127.50','Date 2088-09-26'];positions=[280,540,920,1320];sources={};cases=[];actions=[]
    for name in ['native-only','complete-local-mask','complete-full-mask','partial-native','unrecognized-ink','conflicting-id','transparent-mask']:
        rasterLines=list(lines)
        if name=='conflicting-id':rasterLines[1]='Record 00974'
        image=Image.new('RGB',(2550,3300),'white');draw=ImageDraw.Draw(image)
        for text,y in zip(rasterLines,positions):draw.text((300,y),text,font=font,fill='black')
        extra=None
        if name=='unrecognized-ink':
            small=ImageFont.truetype(str(fontpath),14);extra=dict(text='MISSING 0063.10',positionPixels=[300,1980],fontPixels=14)
            draw.text((300,1980),extra['text'],font=small,fill='black')
        png=out/(name+'.png');image.save(png,dpi=(300,300));sources[png.name]=sha(png)
        native=[(i,t.replace('127.50','128.75')) for i,t in enumerate(lines) if name!='partial-native' or i!=3]
        nativeFont=fitz.Font(fontfile=str(fontpath));pdf=fitz.open();page=pdf.new_page(width=612,height=792)
        page.insert_font(fontname='RouteFont',fontfile=str(fontpath))
        if name!='native-only':page.insert_image(page.rect,stream=png.read_bytes())
        amount=draw.textbbox((300,920),rasterLines[2],font=font);mask=[amount[0]*.24-1.5,amount[1]*.24-1.5,amount[2]*.24+1.5,amount[3]*.24+1.5]
        if name=='complete-full-mask':page.draw_rect(page.rect,color=None,fill=(1,1,1),width=0,overlay=True)
        elif name!='native-only':page.draw_rect(fitz.Rect(mask),color=None,fill=(1,1,1),width=0,fill_opacity=.5 if name=='transparent-mask' else 1,overlay=True)
        for i,text in native:page.insert_text((72,(positions[i]+font.getmetrics()[0])*.24),text,fontname='RouteFont',fontsize=13.44)
        path=out/(name+'.pdf');pdf.save(path);pdf.close();sources[path.name]=sha(path)
        expected='\n'.join(t for _,t in native)+'\n'
        cases.append(dict(id=name,rasterLines=rasterLines,nativeLines=[t for _,t in native],visibleExpected=None if name=='conflicting-id' else '\n'.join(t.replace('127.50','128.75') for t in lines)+('\n'+extra['text'] if extra else '')+'\n',
            nativeExpected=expected,extraUnmodeledInk=extra,maskPoints=None if name=='native-only' else [0,0,612,792] if name=='complete-full-mask' else mask,
            maskOpacity=.5 if name=='transparent-mask' else 1,expectedContract={'native-only':'success native','complete-local-mask':'strict no-new-text despite synthetic completeness','complete-full-mask':'success fully hidden-raster no-op','partial-native':'success adds missing native date','unrecognized-ink':'must not silently succeed dropping real unrecognized ink','conflicting-id':'preserve native and OCR differing digits','transparent-mask':'strict visibility uncertainty'}[name]))
        actions.append(dict(id=name,input=path.name,target='txt'))
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,cases=cases,fontSha256=sha(fontpath),fontVersion='LiberationSans2.1.5',generatorSha256=sha(Path(__file__)),renderLibrary=fitz.VersionBind),indent=2)+'\n')
    print('frozen7 independent PDF controls')
if __name__=='__main__':main()
