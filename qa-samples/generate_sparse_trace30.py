#!/usr/bin/env python3
"""Finite same-pixel OFD/PNG/PDF diagnostics; synthetic inputs, no OCR tuning."""
import hashlib, json, zipfile, xml.etree.ElementTree as E
from pathlib import Path
import fitz
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
NS = '{http://www.ofdspec.org/2016}'
E.register_namespace('ofd', NS[1:-1])
OUT = ROOT / 'qa-samples/generated/sparse-trace30'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    OUT.mkdir(exist_ok=False)
    source = ROOT / 'qa-samples/report/iteration25-smoke/digital-ofd-result.ofd'
    with zipfile.ZipFile(source) as z:
        template = {n: z.read(n) for n in z.namelist()}
    resource = 'Doc_0/Res/page-1.png'
    (OUT/'original.png').write_bytes(template[resource])
    truth = {'original': (ROOT/'qa-samples/generated/cloud-smoke25/record.txt').read_text()}
    actions, sources, ofds = [], {}, []
    def add(p, target='txt'):
        sources[p.name] = sha(p)
        actions.append(dict(id=p.stem+'-'+p.suffix[1:]+'-'+target, input=p.name, target=target))
    def pdf(p):
        with Image.open(p) as im: w,h = im.size
        with fitz.open() as doc:
            page = doc.new_page(width=w*72/300, height=h*72/300)
            page.insert_image(page.rect, filename=str(p))
            dest = p.with_suffix('.pdf'); doc.save(dest)
        add(dest)
    add(OUT/'original.png'); pdf(OUT/'original.png')
    lines = ['SPARSE TRACE 2064', 'Record 00793', 'Amount 048.65', 'Date 2064-09-28']
    truth['independent'] = '\n'.join(lines)+'\n'
    font = ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',56)
    im = Image.new('RGB',(2550,3300),'white'); draw = ImageDraw.Draw(im)
    for line,y in zip(lines,[300,430,560,690],strict=True): draw.text((300,y),line,font=font,fill='black')
    im.save(OUT/'independent.png',dpi=(300,300)); add(OUT/'independent.png'); pdf(OUT/'independent.png')
    for variant in ['image-only','native-backed','missing-amount','alias-missing-amount','blank','noise']:
        parts = dict(template); root = E.fromstring(parts['Doc_0/Pages/Page_0/Content.xml'])
        layer = root.find('.//'+NS+'Layer'); layer.clear(); layer.set('ID','2'); layer.set('Type','Body')
        if variant in ['native-backed','missing-amount','alias-missing-amount']:
            for index,(line,y) in enumerate(zip(lines,[300,430,560,690],strict=True)):
                if variant!='native-backed' and index==2: continue
                box = draw.textbbox((300,y),line,font=font)
                mm=lambda px: px*25.4/300
                obj=E.SubElement(layer,NS+'TextObject',{'ID':str(30+index),'Font':'7','Size':str(mm(56)),
                     'Boundary':f'{mm(box[0])} {mm(box[1])} {mm(box[2]-box[0])} {mm(box[3]-box[1])}'})
                E.SubElement(obj,NS+'TextCode',{'X':'0','Y':str(mm(box[3]-box[1]))}).text=line
        pixels = im.copy()
        if variant in ['blank','noise']:
            pixels = Image.new('RGB',im.size,'white')
            if variant=='noise':
                d=ImageDraw.Draw(pixels)
                for x,y in [(300,400),(900,1000),(1600,1900)]: d.rectangle((x,y,x+1,y+1),fill='black')
        raster=OUT/(variant+'.png'); pixels.save(raster,dpi=(300,300)); sources[raster.name]=sha(raster)
        parts[resource]=raster.read_bytes()
        E.SubElement(layer,NS+'ImageObject',{'ID':'16','ResourceID':'15','Boundary':'0 0 215.900 279.400',
                       'CTM':'215.9 0 0 279.4 0 0'})
        xml=E.tostring(root,encoding='utf-8',xml_declaration=True)
        if variant=='alias-missing-amount': xml=xml.replace(b'ofd:',b'scan:').replace(b'xmlns:ofd=',b'xmlns:scan=')
        parts['Doc_0/Pages/Page_0/Content.xml']=xml
        p=OUT/(variant+'.ofd')
        with zipfile.ZipFile(p,'w',zipfile.ZIP_DEFLATED) as z:
            for n,data in parts.items(): z.writestr(n,data)
        add(p); ofds.append(p.name)
    (OUT/'original.ofd').write_bytes(source.read_bytes()); sources['original.ofd']=sha(source)
    (OUT/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,truth=truth,
        ofds=['original.ofd']+ofds,generatorSha256=sha(Path(__file__)),sourceSha256=sha(source),
        fontSha256=sha(Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'))),ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(actions=len(actions),ofds=len(ofds)+1)))
if __name__=='__main__': main()
