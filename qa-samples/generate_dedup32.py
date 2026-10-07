#!/usr/bin/env python3
"""Frozen independent mixed-native/raster OFD controls; no OCR parameter tuning."""
import hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];NS='{http://www.ofdspec.org/2016}'
E.register_namespace('ofd',NS[1:-1])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/dedup32';out.mkdir(exist_ok=False)
    template=ROOT/'qa-samples/generated/sparse-trace30/native-backed.ofd'
    with zipfile.ZipFile(template) as z:base={n:z.read(n) for n in z.namelist()}
    fontpath=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');font=ImageFont.truetype(str(fontpath),56)
    original=['DEDUP AUDIT 2076','Record 00793','Amount 048.65','Date 2076-10-14']
    names=['duplicate','partial-new-digits','same-lexeme-other-position','digit-extension','negative-sign','decimal-conflict','one-digit-conflict','one-letter-extension','blank','noise','native-noop']
    sources={};actions=[];cases=[]
    for name in names:
        raster=list(original);native=list(original);omit=set();shift=False;expectedFailure=None
        if name=='duplicate':expectedFailure='OCR_NO_NEW_TEXT'
        if name=='partial-new-digits':omit={2}
        if name=='same-lexeme-other-position':shift=True
        if name=='digit-extension':raster[1]='Record 007930'
        if name=='negative-sign':raster[2]='Amount -048.65'
        if name=='decimal-conflict':native[2]='Amount 04865'
        if name=='one-digit-conflict':native[2]='Amount 048.66'
        if name=='one-letter-extension':raster[0]='DEDUP AUDITX 2076'
        if name in ['blank','noise']:native=[];raster=[];expectedFailure='OCR_NO_TEXT'
        if name=='native-noop':
            native=['NATIVE COMPLETE RECORD LINE '+str(i)+' ID00793 Amount 048.65' for i in range(4)]
            raster=list(native)
        image=Image.new('RGB',(2550,3300),'white');draw=ImageDraw.Draw(image);positions=[320,620,920,1220]
        if name=='native-noop':positions=[240,1100,1960,2820]
        for line,y in zip(raster,positions):draw.text((300,y),line,font=font,fill='black')
        if name=='noise':
            for x,y in [(300,400),(900,1000),(1600,1900)]:draw.rectangle((x,y,x+1,y+1),fill='black')
        png=out/(name+'.png');image.save(png,dpi=(300,300));sources[png.name]=sha(png)
        parts=dict(base);parts['Doc_0/Res/page-1.png']=png.read_bytes();root=E.fromstring(parts['Doc_0/Pages/Page_0/Content.xml'])
        layer=root.find('.//'+NS+'Layer');layer.clear();layer.set('ID','2');layer.set('Type','Body')
        mm=lambda x:x*25.4/300
        for i,(line,y) in enumerate(zip(native,positions)):
            if i in omit:continue
            if shift and i==1:y=2400
            box=draw.textbbox((300,y),line,font=font)
            obj=E.SubElement(layer,NS+'TextObject',{'ID':str(40+i),'Font':'7','Size':str(mm(56)),
                'Boundary':f'{mm(box[0])} {mm(box[1])} {mm(box[2]-box[0])} {mm(box[3]-box[1])}'})
            E.SubElement(obj,NS+'TextCode',{'X':'0','Y':str(mm(box[3]-box[1]))}).text=line
        E.SubElement(layer,NS+'ImageObject',{'ID':'16','ResourceID':'15','Boundary':'0 0 215.900 279.400','CTM':'215.9 0 0 279.4 0 0'})
        parts['Doc_0/Pages/Page_0/Content.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True)
        ofd=out/(name+'.ofd')
        with zipfile.ZipFile(ofd,'w',zipfile.ZIP_DEFLATED) as z:
            for n,data in parts.items():z.writestr(n,data)
        sources[ofd.name]=sha(ofd);actions.append(dict(id=name+'-text',input=ofd.name,target='txt'))
        cases.append(dict(id=name,rasterLines=raster,nativeLines=[t for i,t in enumerate(native) if i not in omit],
                          nativeShifted=shift,expectedStrictFailure=expectedFailure))
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,cases=cases,templateSha256=sha(template),fontSha256=sha(fontpath),generatorSha256=sha(Path(__file__)),scope='Synthetic source evidence; fixed actual bundled OCR, native/raster differences intentional'),indent=2)+'\n')
    print('frozen',len(cases),'independent controls')
if __name__=='__main__':main()
