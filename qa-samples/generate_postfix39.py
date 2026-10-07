#!/usr/bin/env python3
"""Freeze terminal numeric signs and range/list/field controls before changing code."""
import hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,__version__ as pillow_version
ROOT=Path(__file__).resolve().parents[1];NS='{http://www.ofdspec.org/2016}'
E.register_namespace('ofd',NS[1:-1])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/postfix39-before';out.mkdir(exist_ok=False)
    template=ROOT/'qa-samples/generated/dedup32/duplicate.ofd'
    with zipfile.ZipFile(template) as z:base={n:z.read(n) for n in z.namelist()}
    fontpath=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');font=ImageFont.truetype(str(fontpath),56)
    specs=[('post-minus','Amount 048.65-','Amount 048.65',True),('post-space-minus','Amount 048.65 -','Amount 048.65',True),
           ('post-unicode-minus','Amount 048.65−','Amount 048.65',True),('post-plus','Amount 048.65+','Amount 048.65',True),
           ('post-space-plus','Amount 048.65 +','Amount 048.65',True),('post-unicode-space-minus','Amount 048.65\u00a0−','Amount 048.65',True),
           ('post-accounting','Amount (048.65-)','Amount (048.65)',True),('post-currency','Amount $048.65-','Amount $048.65',True),
           ('range-compact','Range 048.65-049.65','Range 048.65-049.65',False),('range-spaced','Range 048.65 - 049.65','Range 048.65 - 049.65',False),
           ('inline-list','Amount 048.65 - NOTE','Amount 048.65 NOTE',False),('separate-field','Values 048.65 | -049.65','Values 048.65 | -049.65',False)]
    pairs=[dict(id=n,native=a,ocr=b,expectedDuplicate=not conflict,expectedConflict=conflict) for n,a,b,conflict in specs]
    pairs+= [dict(id=n,native=a,ocr=b,expectedDuplicate=duplicate,expectedConflict=not duplicate) for n,a,b,duplicate in [
        ('fullwidth-post-minus','Amount 048.65－','Amount 048.65',False),('fullwidth-post-plus','Amount 048.65＋','Amount 048.65',False),
        ('same-post-space','Amount 048.65 -','Amount 048.65-',True),('same-unicode-post-space','Amount 048.65 −','Amount 048.65−',True),
        ('literal-post-sign','Amount 048.65−','Amount 048.65-',False),('currency-after-post','Amount 048.65$-','Amount 048.65$',False),
        ('currency-after-sign','Amount 048.65-€','Amount 048.65€',False),('same-range-label','Range: 048.65-049.65','Range 048.65-049.65',True),
        ('same-spaced-range-label','Range: 048.65 - 049.65','Range 048.65 - 049.65',True),
        ('same-plus-field-space','Values 048.65 + 049.65','Values 048.65 +049.65',True),
        ('line-list','Amount 048.65\n- NOTE','Amount 048.65 NOTE',True),('line-range','Range 048.65\n- 049.65','Range 048.65 - 049.65',True),
        ('field-negative-different','Values 048.65 | -049.65','Values 048.65 | 049.65',False),
        ('accounting-old','Amount 048.65','Amount (048.65)',False),('percent-old','Rate 10','Rate 10%',False),
        ('id-zero-old','Record 00793','Record 0793',False),('separate-old','Values 12 34','Values 1234',False),
        ('ordinary-old','Invoice No. 2093','Invoice No 2093',True)]]
    tokens={'range-compact':['048.65-049.65'],'range-spaced':['048.65','-049.65'],'inline-list':['048.65'],
            'separate-field':['048.65','-049.65'],'same-plus-field-space':['048.65','+049.65'],'line-list':['048.65'],'line-range':['048.65','-049.65']}
    for p in pairs:
        if p['id'] in tokens:p['expectedNativeTokens']=tokens[p['id']]
    sources={};actions=[];cases=[];positions=[320,620,920,1220,1940];mm=lambda v:v*25.4/300
    for name,nativeValue,rasterValue,conflict in specs:
        native=['POSTFIX AUDIT 2093','Record 00864',nativeValue,'Date 2093-09-17'];raster=list(native);raster[2]=rasterValue;raster.append('INDEPENDENT NOVEL FOOTER 2093')
        image=Image.new('RGB',(2550,3300),'white');draw=ImageDraw.Draw(image)
        for line,y in zip(raster,positions):draw.text((300,y),line,font=font,fill='black')
        png=out/(name+'.png');image.save(png,dpi=(300,300));sources[png.name]=sha(png)
        parts=dict(base);parts['Doc_0/Res/page-1.png']=png.read_bytes();root=E.fromstring(parts['Doc_0/Pages/Page_0/Content.xml']);layer=root.find('.//'+NS+'Layer');layer.clear();layer.set('ID','2');layer.set('Type','Body')
        for i,(line,y) in enumerate(zip(native,positions)):
            box=draw.textbbox((300,y),line,font=font);obj=E.SubElement(layer,NS+'TextObject',dict(ID=str(40+i),Font='7',Size=str(mm(56)),Boundary=f'{mm(box[0])} {mm(box[1])} {mm(box[2]-box[0])} {mm(box[3]-box[1])}'))
            E.SubElement(obj,NS+'TextCode',dict(X='0',Y=str(mm(box[3]-box[1])))).text=line
        E.SubElement(layer,NS+'ImageObject',dict(ID='16',ResourceID='15',Boundary='0 0 215.900 279.400',CTM='215.9 0 0 279.4 0 0'))
        parts['Doc_0/Pages/Page_0/Content.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True);ofd=out/(name+'.ofd')
        with zipfile.ZipFile(ofd,'w',zipfile.ZIP_DEFLATED) as z:
            for n,data in parts.items():z.writestr(n,data)
        sources[ofd.name]=sha(ofd);actions.append(dict(id=name+'-text',input=ofd.name,target='txt'));cases.append(dict(id=name,nativeLines=native,rasterLines=raster,nativeValue=nativeValue,rasterValue=rasterValue,novelFooter=raster[-1],expectedConflict=conflict,sourceLayersHaveCanonicalSingleValue=False))
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,cases=cases,actions=actions,javaPairs=pairs,sourceFontPath=str(fontpath),sourceFontSha256=sha(fontpath),templateSha256=sha(template),generatorSha256=sha(Path(__file__)),pillowVersion=pillow_version,parentRevision='841bc848844458fba909f3d015743cf891ab28b8',freezeBeforeProductionChange=True,terminalFieldScope=True),ensure_ascii=False,indent=2)+'\n');print('Frozen12OFD controls/30Java pairs;independentnovelfooters;0OCR')
if __name__=='__main__':main()
