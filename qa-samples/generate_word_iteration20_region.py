#!/usr/bin/env python3
"""Independent born-digital PDF fixtures; source text/order frozen before conversion."""
import hashlib,json,pathlib
import fitz
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/word-iteration20-region'
FONTS={'en':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
       'zh':pathlib.Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    assert not OUT.exists();OUT.mkdir(parents=True)
    fonts={k:fitz.Font(fontfile=str(p)) for k,p in FONTS.items()};cases=[]
    assert all(font.has_glyph(ord(c)) for font in fonts.values() for c in '0123456789.-')
    def page(doc,width=540,height=420):
        p=doc.new_page(width=width,height=height)
        for k,path in FONTS.items():p.insert_font(fontname=k,fontfile=str(path))
        return p
    def draw(p,text,x,y,lang='en',size=11):
        p.insert_text((x,y),text,fontname=lang,fontsize=size)
        return {'page':p.number+1,'text':text,'x':x,'baseline':y,'width':fonts[lang].text_length(text,fontsize=size),'font':lang,'size':size}
    def save(doc,name,items,order,kind,extra=None):
        # MuPDF's reverse font cmap can choose compatibility ideographs or a
        # nonbreaking hyphen for shared glyphs. Emit the intended source Unicode
        # explicitly, as a born-digital producer should, before acceptance.
        used={key:{c for item in items if item['font']==key for c in item['text']} for key in fonts}
        for key,characters in used.items():
            mapping={}
            for c in characters:
                gid=fonts[key].has_glyph(ord(c));assert gid and (gid not in mapping or mapping[gid]==c)
                mapping[gid]=c
            entries=[f'<{gid:04X}> <{c.encode("utf-16-be").hex().upper()}>' for gid,c in sorted(mapping.items())]
            cmap='/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /SourceTruth def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n'
            for start in range(0,len(entries),100):
                chunk=entries[start:start+100];cmap+=str(len(chunk))+' beginbfchar\n'+'\n'.join(chunk)+'\nendbfchar\n'
            cmap+='endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n'
            for xref in {f[0] for p in doc for f in p.get_fonts() if f[4]==key}:
                typ,value=doc.xref_get_key(xref,'ToUnicode');assert typ=='xref'
                doc.update_stream(int(value.split()[0]),cmap.encode())
        doc.set_metadata({'title':name,'author':'FormatConverter synthetic QA'})
        path=OUT/(name+'.pdf');doc.save(path,garbage=4,deflate=True,no_new_id=True)
        record={'file':path.name,'sha256':sha(path),'kind':kind,'pages':len(doc),'items':items,'expectedText':'\n'.join(order)}
        if extra:record.update(extra)
        cases.append(record);doc.close()
    d=fitz.open();p=page(d,210*72/25.4,297*72/25.4);items=[];order=[]
    for region,(lx,rx,width) in enumerate([(15,110,50),(60,145,40)]):
        top=50+region*240;heading=f'INDEPENDENT REGION {region+1} HEADING 2031'
        items.append(draw(p,heading,15*72/25.4,top,'en',12));order.append(heading);left=[];right=[]
        for i in range(4):
            l=f'Left {region+1}-{i+1} record 00731 .64';r=f'Right {region+1}-{i+1} record 00927 .28'
            for text,x,dest in [(l,lx,left),(r,rx,right)]:
                size=width*72/25.4/fonts['en'].text_length(text,fontsize=1)
                dest.append(draw(p,text,x*72/25.4,top+40+i*30,'en',size));items.append(dest[-1])
        order += [v['text'] for v in left]+[v['text'] for v in right]
    save(d,'shifted-exact-regions',items,order,'columns',{'regionBoundsMm':[[15,65,110,160],[60,100,145,185]]})
    manifest={'generatorSha256':sha(pathlib.Path(__file__)),'PyMuPDF':fitz.VersionBind,'fonts':{k:{'path':str(p),'sha256':sha(p)} for k,p in FONTS.items()},'cases':cases}
    (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(sha(OUT/'expected.json'))
if __name__=='__main__':main()
