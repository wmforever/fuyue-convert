#!/usr/bin/env python3
"""Independent born-digital PDF fixtures; source text/order frozen before conversion."""
import hashlib,json,pathlib
import fitz
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/font-iteration21'
FONTS={
 'sr':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
 'sb':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf'),
 'si':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Italic.ttf'),
 'sbi':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-BoldItalic.ttf'),
 'mr':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'),
 'mb':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf'),
 'mi':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationMono-Italic.ttf'),
 'zh':pathlib.Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
 'dv':pathlib.Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')}

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    assert not OUT.exists();OUT.mkdir(parents=True)
    fonts={k:fitz.Font(fontfile=str(p)) for k,p in FONTS.items()};cases=[]
    assert all(font.has_glyph(ord(c)) for font in fonts.values() for c in '0123456789.-')
    def page(doc,width=540,height=420):
        p=doc.new_page(width=width,height=height)
        pass # Only insert fonts that are actually used by this page.
        return p
    def draw(p,text,x,y,lang='sr',size=11):
        p.insert_font(fontname=lang,fontfile=str(FONTS[lang]))
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
    for name,keys in [('serif-faces',['sr','sb','si','sbi']),('mono-faces',['mr','mb','mi'])]:
        d=fitz.open();p=page(d,680,400);items=[]
        for i,key in enumerate(keys):
            text=f'Font row {i+1}: ID 00{731+i}; date 2032-09-24; debit -528.64; credit .28.'
            items.append(draw(p,text,42,65+i*65,key,12))
        save(d,name,items,[i['text'] for i in items],'font-faces')
    d=fitz.open();p=page(d,680,420);items=[]
    lines=[('Mixed 中英：编号00731，金额-528.64；日期2032-09-24。','zh'),
           ('中文引号“确认”；Latin quotes “approved”, amount €12.50 and ¥38.20.','zh'),
           ('Latin run: invoice 00927 / amount .28; ','sr')]
    for i,(t,key) in enumerate(lines):items.append(draw(p,t,42,65+i*62,key,12))
    last=items[-1];items.append(draw(p,'中文补充：保留全部字符。',last['x']+last['width']+4,last['baseline'],'zh',12))
    save(d,'bilingual-punctuation',items,[i['text'] for i in items],'mixed-font')
    d=fitz.open();p=page(d,700,380);items=[]
    lines=['Symbols: −528.64 ±0.05 €12.50 ≤100 ≥2 Ω α β → ∑ × ÷',
           'Ligatures: ﬁ ﬂ; punctuation — … “quoted”; ID 00731 / 2032-09-24.',
           'Cyrillic: Пример; Greek: Ελληνικά; Latin: café naïve résumé.']
    for i,t in enumerate(lines):items.append(draw(p,t,42,65+i*70,'dv',12))
    save(d,'symbols-dejavu',items,lines,'symbols')
    d=fitz.open();p=page(d,640,480);items=[]
    for i,t in enumerate(['REFERENCE 00731 / 2032-09-24','Debit -528.64; credit .28; balance 2,108.75.',
                          '中文第二段：编号00927，金额-0.05，保持前导零。','Separate final line: A-00530 / 2032-10-06.']):
        items.append(draw(p,t,42,65+i*85,'zh' if i==2 else 'sr',12))
    save(d,'line-boundaries',items,[i['text'] for i in items],'line-boundaries')
    d=fitz.open();p=page(d,640,300);items=[]
    items.append(draw(p,'Explicit unavailable family: ID 00731; -528.64 / 2032-09-24.',42,65,'sr',12))
    for font in p.get_fonts():
        if font[4]=='sr':
            import re
            descendant=int(re.search(r'(\d+) 0 R',d.xref_get_key(font[0],'DescendantFonts')[1]).group(1))
            descriptor=int(d.xref_get_key(descendant,'FontDescriptor')[1].split()[0])
            d.xref_set_key(descriptor,'FontFamily','(QA Missing Family 2032)')
    save(d,'unavailable-family',items,[i['text'] for i in items],'unavailable-family',{'explicitDescriptorFamily':'QA Missing Family 2032'})
    from fontTools.ttLib import TTFont
    metadata={}
    for key,path in FONTS.items():
        font=TTFont(path,fontNumber=0)
        metadata[key]={'path':str(path),'sha256':sha(path),'family':font['name'].getDebugName(1),'subfamily':font['name'].getDebugName(2),'version':font['name'].getDebugName(5),'license':'SIL-OFL-1.1' if key!='dv' else 'Bitstream Vera; DejaVu changes public domain'}
        font.close()
    manifest={'generatorSha256':sha(pathlib.Path(__file__)),'PyMuPDF':fitz.VersionBind,'fonts':metadata,'cases':cases}
    (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'manifestSha256':sha(OUT/'expected.json'),'cases':len(cases)}))
if __name__=='__main__':main()
