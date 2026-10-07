#!/usr/bin/env python3
"""Independent born-digital PDF fixtures; source text/order frozen before conversion."""
import hashlib,json,pathlib
import fitz
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/word-iteration19'
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
    for lang in ['en','zh']:
        content=('The review is complete. '+ ' '.join(f'For shipment {3100+i:05d}, the approved record dated 2027-04-16 retains debit -417.85 and credit .95 while the coordinator checks every delivery detail before continuing the agreed service'
                  for i in range(18)) + '.' if lang=='en' else
                 '本次复核已经完成。'+''.join(f'对于编号{3100+i:05d}的记录，双方确认日期2027-04-16与金额-417.85保持原样，并逐项检查交付内容后继续执行约定的服务计划，' for i in range(18))+'全部资料应当完整保留。')
        tokens=content.split(' ') if lang=='en' else list(content);lines=[];line=''
        for token in tokens:
            candidate=line+(' ' if line and lang=='en' else '')+token
            if line and fonts[lang].text_length(candidate,fontsize=11)>448-(22 if not lines else 0):lines.append(line);line=token
            else:line=candidate
        if line:lines.append(line)
        d=fitz.open();items=[]
        for start in range(0,len(lines),22):
            p=page(d)
            for i,text in enumerate(lines[start:start+22]):items.append(draw(p,text,46+(22 if start+i==0 else 0),52+i*16,lang))
        save(d,'continuous-'+lang,items,[content],'continuous')
    d=fitz.open();items=[]
    for n in range(2):
        p=page(d)
        items.append(draw(p,'REVIEW RECORD / 复核记录',46,25,'zh',9))
        for i in range(12):
            text=(f'Page {n+1} record {4200+n*12+i:05d} keeps amount -17.40 and date 2027-05-18.' if i%2==0 else
                  f'第{n+1}页编号{4200+n*12+i:05d}保留金额.95以及日期2027-05-18，不可遗漏。')
            items.append(draw(p,text,46,65+i*23,'en' if i%2==0 else 'zh',10))
        items.append(draw(p,f'Confidential / 内部资料 — {n+1}',46,395,'zh',9))
    save(d,'headers-footers',items,[v['text'] for v in items],'header-footer')
    for narrow in [False,True]:
        d=fitz.open();p=page(d,600,520);items=[]
        title='BILINGUAL DELIVERY REVIEW 2027 / 双语交付复核'
        items.append(draw(p,title,40,40,'zh',12))
        left=[];right=[];right_x=260 if narrow else 360
        for i in range(5):
            l=f'Left {i+1}: record 00{643+i} is complete.'
            r=f'右栏{i+1}：编号00{817+i}资料齐全。'
            # Increase Latin line width to leave a genuinely narrow gutter.
            if narrow:l=f'Left {i+1}: record 00{643+i} remains fully checked.'
            left.append(draw(p,l,40,85+i*27,'en',11));right.append(draw(p,r,right_x,85+i*27,'zh',11))
            items.extend([left[-1],right[-1]])
        heading='SECOND SECTION SPANS BOTH COLUMNS / 第二节跨越两栏'
        items.append(draw(p,heading,40,260,'zh',12))
        tail='Final amount -417.85, unit .95, approved 2027-06-21; keep all identifiers.'
        items.append(draw(p,tail,40,294,'en',11))
        save(d,'columns-'+('narrow' if narrow else 'wide'),items,[title]+[v['text'] for v in left]+[v['text'] for v in right]+[heading,tail],'columns',{'gutterPt':right_x-max(v['x']+v['width'] for v in left)})
    d=fitz.open();p=page(d,600,420);items=[]
    items.append(draw(p,'ACCOUNT RECONCILIATION / 账户核对',40,40,'zh',12))
    rows=[['编号 ID','日期 Date','金额 Amount','状态 Status'],['AC-00643','2027-02-16','-417.85','已核对'],
          ['CD-00817','2027-03-09','.95','待复核'],['EF-00424','2027-04-21','1,029.05','已批准'],
          ['GH-00026','2027-05-18','-0.65','保留原值']]
    xs=[40,160,290,410,560];ys=[75+i*45 for i in range(6)]
    for x in xs:p.draw_line((x,ys[0]),(x,ys[-1]),width=.6)
    for y in ys:p.draw_line((xs[0],y),(xs[-1],y),width=.6)
    for i,row in enumerate(rows):
        for j,text in enumerate(row):items.append(draw(p,text,xs[j]+7,ys[i]+26,'zh',10))
    items.append(draw(p,'Keep decimals, dates and leading zeros / 保留小数、日期及前导零',40,335,'zh',11))
    save(d,'numeric-table',items,[v['text'] for v in items],'table',{'cells':rows})
    manifest={'generatorSha256':sha(pathlib.Path(__file__)),'PyMuPDF':fitz.VersionBind,
              'fonts':{k:{'path':str(p),'sha256':sha(p)} for k,p in FONTS.items()},'cases':cases}
    (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'manifestSha256':sha(OUT/'expected.json'),'cases':[(c['file'],c['pages'],c.get('gutterPt')) for c in cases]}))
if __name__=='__main__':main()
