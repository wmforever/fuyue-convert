#!/usr/bin/env python3
"""Independent born-digital PDF fixtures; source text/order frozen before conversion."""
import hashlib,json,pathlib
import fitz
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/cjk-iteration22'
FONTS={
 'sr':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
 'sbi':pathlib.Path('/usr/share/fonts/truetype/liberation/LiberationSerif-BoldItalic.ttf'),
 'zh':pathlib.Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
 'zb':pathlib.Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc')}

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
    from fontTools.ttLib import TTFont
    import re
    cff={}
    metadata={}
    for key,path in FONTS.items():
        font=TTFont(path,fontNumber=0)
        metadata[key]={'path':str(path),'sha256':sha(path),'family':font['name'].getDebugName(1),'subfamily':font['name'].getDebugName(2),'version':font['name'].getDebugName(5),'license':'SIL-OFL-1.1'}
        if 'CFF ' in font:
            assert all(n=='.notdef' or n=='cid%05d'%i for i,n in enumerate(font.getGlyphOrder()))
            cff[key]=font.reader['CFF '];metadata[key]['rawCffSha256']=hashlib.sha256(cff[key]).hexdigest()
            metadata[key]['rawCffFamily']=font['CFF '].cff.topDictIndex[0].FamilyName
            metadata[key]['rawCffWeight']=font['CFF '].cff.topDictIndex[0].Weight
        font.close()
    def raw_cff(doc, key, family=None):
        for ref in {f[0] for p in doc for f in p.get_fonts() if f[4]==key}:
            descendant=int(re.search(r'(\d+) 0 R',doc.xref_get_key(ref,'DescendantFonts')[1]).group(1))
            descriptor=int(doc.xref_get_key(descendant,'FontDescriptor')[1].split()[0])
            stream=int(doc.xref_get_key(descriptor,'FontFile3')[1].split()[0])
            doc.update_stream(stream,cff[key]);doc.xref_set_key(stream,'Subtype','/CIDFontType0C')
            doc.xref_set_key(descendant,'Subtype','/CIDFontType0')
            doc.xref_set_key(ref,'BaseFont','/ABCDEF+QACjkExportFace')
            doc.xref_set_key(descendant,'BaseFont','/ABCDEF+QACjkExportFace')
            doc.xref_set_key(descriptor,'FontName','/ABCDEF+QACjkExportFace')
            doc.xref_set_key(descriptor,'ItalicAngle','0');doc.xref_set_key(descriptor,'FontWeight','null')
            doc.xref_set_key(descriptor,'Flags','4')
            if family:doc.xref_set_key(descriptor,'FontFamily','('+family+')')
    d=fitz.open();p=page(d,700,470);items=[]
    for i,(t,key) in enumerate([('混排验证 Mixed ID00486，金额-639.27 / 2033-10-25。','zh'),
         ('粗体记录 Bold ID00852，余额.36，保留数字与标点。','zb'),
         ('Rare Han：𠮷 㐀；かな カナ 한글；€12.50 ￥38.20 −0.05。','zh')]):
        items.append(draw(p,t,42,65+i*82,key,12))
    save(d,'opentype-cjk-controls',items,[i['text'] for i in items],'cjk-controls')
    for name,key,family in [('cid-cff-regular','zh',None),('cid-cff-bold','zb',None),('cid-cff-explicit-family','zh','QA Missing CJK Family')]:
        d=fitz.open();p=page(d,700,400);items=[]
        lines=['嵌入字体 Mixed receipt ID00486：金额-639.27。',
               'Date 2033-10-25 / 编号00852 / credit .36；原文保留。',
               'Unicode 𠮷 㐀：€12.50，￥38.20，符号−0.05。']
        for i,t in enumerate(lines):items.append(draw(p,t,42,65+i*85,key,12))
        raw_cff(d,key,family)
        extra={'nativeRepresentation':'CIDFontType0C','pdfExportName':'ABCDEF+QACjkExportFace','rawCffSha256':hashlib.sha256(cff[key]).hexdigest()}
        if family:extra['explicitDescriptorFamily']=family
        save(d,name,items,lines,'cff-font',extra)
    d=fitz.open();p=page(d,650,450);items=[]
    rows=[['编号 ID','日期 Date','金额 Amount','状态'],['AB-00486','2033-10-25','-639.27','已确认'],['CD-00852','2033-11-06','.36','待复核'],['EF-00217','2033-12-14','1,208.45','保留']]
    xs=[42,190,350,505,608];ys=[70+i*63 for i in range(5)]
    for x in xs:p.draw_line((x,ys[0]),(x,ys[-1]),width=.6)
    for y in ys:p.draw_line((xs[0],y),(xs[-1],y),width=.6)
    for i,row in enumerate(rows):
        for j,t in enumerate(row):items.append(draw(p,t,xs[j]+6,ys[i]+30,'zb' if i==0 else 'zh',11))
    save(d,'cjk-ruled-table',items,[i['text'] for i in items],'table',{'cells':rows})
    d=fitz.open();p=page(d,680,680);items=[];order=[]
    for section,(lx,rx) in enumerate([(42,390),(105,440)],1):
        top=45+(section-1)*290;heading=f'SECTION {section} / 中英跨栏标题2033'
        items.append(draw(p,heading,42,top,'zb',12));order.append(heading);left=[];right=[]
        for i in range(3):
            left.append(draw(p,f'Left {section}-{i+1} ID00486 -639.27',lx,top+55+i*40,'sbi',12))
            right.append(draw(p,f'右栏{section}-{i+1}：ID00852 .36',rx,top+55+i*40,'zh',12));items.extend([left[-1],right[-1]])
        order += [i['text'] for i in left]+[i['text'] for i in right]
    save(d,'cjk-heading-control',items,order,'columns')
    manifest={'generatorSha256':sha(pathlib.Path(__file__)),'PyMuPDF':fitz.VersionBind,'fonts':metadata,'cases':cases}
    (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'manifestSha256':sha(OUT/'expected.json'),'cases':len(cases)}))
if __name__=='__main__':main()
