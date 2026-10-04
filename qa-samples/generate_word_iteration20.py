#!/usr/bin/env python3
"""Independent born-digital PDF fixtures; source text/order frozen before conversion."""
import hashlib,json,pathlib
import fitz
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/word-iteration20'
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
    for name,left_count,right_count,same in [('narrow-balanced',4,4,False),('narrow-left-tail',6,4,False),('narrow-right-tail',4,6,False),('narrow-same-font',4,4,True)]:
        d=fitz.open();p=page(d,600,520);items=[]
        title='INDEPENDENT REVIEW 2031 / 独立复核';items.append(draw(p,title,42,40,'zh',12))
        left=[];right=[]
        lt=[f'Left report {i+1} retains record 00{731+i} in full.' for i in range(left_count)]
        rt=[(f'Right report {i+1} retains record 00{927+i} in full.' if same else f'右侧记录{i+1}保留编号00{927+i}全部内容。') for i in range(right_count)]
        rx=42+max(fonts['en'].text_length(t,fontsize=11) for t in lt)+24
        for i in range(max(left_count,right_count)):
            if i<left_count:left.append(draw(p,lt[i],42,86+i*25));items.append(left[-1])
            if i<right_count:right.append(draw(p,rt[i],rx,86+i*25,'en' if same else 'zh'));items.append(right[-1])
        end='FINAL AUDIT -528.64 / 最终审核 2031-08-23';items.append(draw(p,end,42,285,'zh',12))
        save(d,name,items,[title]+[v['text'] for v in left]+[v['text'] for v in right]+[end],'columns',{'gutterPt':24,'unequalRows':[left_count,right_count]})
    d=fitz.open();p=page(d,595.276,841.89);items=[];order=[]
    for region,(lx,rx) in enumerate([(43,312),(170,410)]):
        top=50+region*240;heading=f'SECTION {region+1} / 横向位置不同的跨栏标题 2031'
        items.append(draw(p,heading,43,top,'zh',12));order.append(heading);left=[];right=[]
        for i in range(4):
            l=f'Left {region+1}-{i+1} ID00731 .64';r=f'Right {region+1}-{i+1} ID00927 .28'
            left.append(draw(p,l,lx,top+40+i*27));right.append(draw(p,r,rx,top+40+i*27));items.extend([left[-1],right[-1]])
        order += [v['text'] for v in left]+[v['text'] for v in right]
    save(d,'shifted-wide-regions',items,order,'columns')
    d=fitz.open();p=page(d,600,520);items=[]
    for i in range(7):
        a=f'Single line {i+1} keeps long delivery record 00{530+i} and ';b='中文补充说明与金额-528.64，完整保留。'
        item=draw(p,a,42,60+i*36);items.append(item);items.append(draw(p,b,42+item['width']+2,60+i*36,'zh',11))
    save(d,'single-mixed-longlines',items,[v['text'] for v in items],'single')
    for ruled in [True,False]:
        d=fitz.open();p=page(d,600,420);items=[]
        rows=[['ID 编号','Date 日期','Amount 金额'],['JK-00731','2031-08-23','-528.64'],['LM-00927','2031-09-14','.28'],['NP-00530','2031-10-06','2,108.75']]
        xs=[42,190,365,555];ys=[65+i*50 for i in range(5)]
        if ruled:
            for x in xs:p.draw_line((x,ys[0]),(x,ys[-1]),width=.6)
            for y in ys:p.draw_line((xs[0],y),(xs[-1],y),width=.6)
        for i,row in enumerate(rows):
            for j,t in enumerate(row):items.append(draw(p,t,xs[j]+7,ys[i]+27,'zh',11))
        save(d,'numeric-'+('ruled' if ruled else 'ledger'),items,[v['text'] for v in items],'table' if ruled else 'ledger',{'cells':rows})
    manifest={'generatorSha256':sha(pathlib.Path(__file__)),'PyMuPDF':fitz.VersionBind,'fonts':{k:{'path':str(p),'sha256':sha(p)} for k,p in FONTS.items()},'cases':cases}
    (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'manifestSha256':sha(OUT/'expected.json'),'cases':len(cases)}))
if __name__=='__main__':main()
