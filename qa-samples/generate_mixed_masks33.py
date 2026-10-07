#!/usr/bin/env python3
"""Freeze independent mixed native/scan edits before renderer changes."""
import hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];NS='{http://www.ofdspec.org/2016}'
E.register_namespace('ofd',NS[1:-1])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/mixed-masks33-before';out.mkdir(exist_ok=False)
    template=ROOT/'qa-samples/generated/dedup32/partial-new-digits.ofd'
    with zipfile.ZipFile(template) as z:base={n:z.read(n) for n in z.namelist()}
    fontpath=Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf');font=ImageFont.truetype(str(fontpath),56)
    specs=[('white','AUDIT CONTROL 2080','00062.35','-054.80',255),('gray','BALANCE CHECK 2081','-071.20','+604.70',225),
           ('split','NUMERIC BOUNDARY 2082','12 34','56 34',255),('conflict','LAYER CONFLICT 2083','127.50',None,255),
           ('marked','ANNOTATED AUDIT 2084','86.40',None,255)]
    sources={};actions=[];cases=[];positions=[280,540,920,1320];mm=lambda v:v*25.4/300
    for name,title,value,edited,paper in specs:
        lines=[title,'Record 00793','Amount '+value,'Date 2082-09-26']
        image=Image.new('RGB',(2550,3300),(paper,paper,paper));draw=ImageDraw.Draw(image)
        for line,y in zip(lines,positions):draw.text((300,y),line,font=font,fill='black')
        if name=='marked':
            x=300+draw.textlength('Amount ',font=font);box=draw.textbbox((x,positions[2]),value,font=font)
            draw.rectangle((int(x-3),box[1]-8,int(box[2]+3),box[1]-2),fill=(200,0,0))
        png=out/(name+'.png');image.save(png,dpi=(300,300));sources[png.name]=sha(png)
        parts=dict(base);parts['Doc_0/Res/page-1.png']=png.read_bytes();root=E.fromstring(parts['Doc_0/Pages/Page_0/Content.xml'])
        layer=root.find('.//'+NS+'Layer');layer.clear();layer.set('ID','2');layer.set('Type','Body');native=[]
        for i,(line,y) in enumerate(zip(lines,positions)):
            if i==2 and name!='conflict':continue
            if i==2:line='Amount 127.51'
            native.append(line);box=draw.textbbox((300,y),line,font=font)
            obj=E.SubElement(layer,NS+'TextObject',{'ID':str(40+i),'Font':'7','Size':str(mm(56)),
                'Boundary':f'{mm(box[0])} {mm(box[1])} {mm(box[2]-box[0])} {mm(box[3]-box[1])}'})
            E.SubElement(obj,NS+'TextCode',{'X':'0','Y':str(mm(box[3]-box[1]))}).text=line
        E.SubElement(layer,NS+'ImageObject',{'ID':'16','ResourceID':'15','Boundary':'0 0 215.900 279.400','CTM':'215.9 0 0 279.4 0 0'})
        parts['Doc_0/Pages/Page_0/Content.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True)
        p=out/(name+'.ofd')
        with zipfile.ZipFile(p,'w',zipfile.ZIP_DEFLATED) as z:
            for n,data in parts.items():z.writestr(n,data)
        sources[p.name]=sha(p)
        if name=='conflict':actions.append(dict(id=name+'-native-text',input=p.name,target='txt'))
        actions.append(dict(id=name+'-word',input=p.name,target='docx'))
        if name!='conflict':
            actions.extend([dict(id=name+'-office',input='@'+name+'-word',target='pdf'),dict(id=name+'-text',input='@'+name+'-office',target='txt')])
        if edited is not None:
            old,new=('12','56') if name=='split' else (value,edited)
            actions.extend([dict(id=name+'-edited-office',input='@'+name+'-word',target='pdf',edit=dict(old=old,new=new)),
                            dict(id=name+'-edited-text',input='@'+name+'-edited-office',target='txt')])
        cases.append(dict(id=name,expected='\n'.join(lines)+'\n',editedExpected='\n'.join(lines).replace('Amount '+value,'Amount '+edited)+'\n' if edited else None,
                          nativeLines=native,sourceValue=value,editValue=edited,paper=paper,support='candidate-disjoint-light-paper' if edited else 'must-preserve-source-conflict-or-mark'))
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,cases=cases,templateSha256=sha(template),fontSha256=sha(fontpath),generatorSha256=sha(Path(__file__))),indent=2)+'\n')
    print('frozen',len(cases),'independent cases;',len(actions),'baseline requests')
if __name__=='__main__':main()
