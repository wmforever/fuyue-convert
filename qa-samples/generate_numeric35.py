#!/usr/bin/env python3
"""Freeze lexical-money controls with a truly novel footer so false dedup can silently succeed."""
import hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];NS='{http://www.ofdspec.org/2016}'
E.register_namespace('ofd',NS[1:-1])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/numeric35-before';out.mkdir(exist_ok=False)
    template=ROOT/'qa-samples/generated/dedup32/duplicate.ofd'
    with zipfile.ZipFile(template) as z:base={n:z.read(n) for n in z.namelist()}
    fontpath=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');font=ImageFont.truetype(str(fontpath),56)
    specs=[('accounting','Amount 048.65','Amount (048.65)'),('percent','Rate 10','Rate 10%'),('per-mille','Rate 10','Rate 10‰'),
        ('dollar','Amount 048.65','Amount $048.65'),('euro','Amount 048.65','Amount 048.65€'),
        ('currency-accounting','Amount (048.65)','Amount $(048.65)'),('space-after-sign','Amount 048.65','Amount - 048.65'),
        ('ordinary-punctuation','Invoice No 2094','Invoice No. 2094'),('id-extension','Record 00793','Record 007930')]
    sources={};actions=[];cases=[];positions=[320,620,920,1220,1940];mm=lambda v:v*25.4/300
    for name,nativeValue,rasterValue in specs:
        native=['LEXICAL AUDIT 2094','Record 00973',nativeValue,'Date 2094-12-08'];raster=list(native);raster[2]=rasterValue;raster.append('NOVEL FOOTER 2094')
        image=Image.new('RGB',(2550,3300),'white');draw=ImageDraw.Draw(image)
        for line,y in zip(raster,positions):draw.text((300,y),line,font=font,fill='black')
        png=out/(name+'.png');image.save(png,dpi=(300,300));sources[png.name]=sha(png)
        parts=dict(base);parts['Doc_0/Res/page-1.png']=png.read_bytes();root=E.fromstring(parts['Doc_0/Pages/Page_0/Content.xml']);layer=root.find('.//'+NS+'Layer')
        layer.clear();layer.set('ID','2');layer.set('Type','Body')
        for i,(line,y) in enumerate(zip(native,positions)):
            box=draw.textbbox((300,y),line,font=font)
            obj=E.SubElement(layer,NS+'TextObject',dict(ID=str(40+i),Font='7',Size=str(mm(56)),Boundary=f'{mm(box[0])} {mm(box[1])} {mm(box[2]-box[0])} {mm(box[3]-box[1])}'))
            E.SubElement(obj,NS+'TextCode',dict(X='0',Y=str(mm(box[3]-box[1])))).text=line
        E.SubElement(layer,NS+'ImageObject',dict(ID='16',ResourceID='15',Boundary='0 0 215.900 279.400',CTM='215.9 0 0 279.4 0 0'))
        parts['Doc_0/Pages/Page_0/Content.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True)
        ofd=out/(name+'.ofd')
        with zipfile.ZipFile(ofd,'w',zipfile.ZIP_DEFLATED) as z:
            for n,data in parts.items():z.writestr(n,data)
        sources[ofd.name]=sha(ofd);actions.append(dict(id=name+'-text',input=ofd.name,target='txt'))
        cases.append(dict(id=name,nativeLines=native,rasterLines=raster,nativeValue=nativeValue,rasterValue=rasterValue,novelFooter='NOVEL FOOTER 2094',sameNumericSurface=name=='ordinary-punctuation',sourceLayersHaveCanonicalSingleValue=False))
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,cases=cases,fontSha256=sha(fontpath),templateSha256=sha(template),generatorSha256=sha(Path(__file__)),ocrParameterTuning=False),indent=2,ensure_ascii=False)+'\n')
    print('frozen9 controls;each has independent novel footer')
if __name__=='__main__':main()
