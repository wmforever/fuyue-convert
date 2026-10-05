#!/usr/bin/env python3
"""Freeze two original native bilingual continuation tables; never use scan/OCR truth."""
import hashlib,json
from pathlib import Path
import fitz
from fontTools.ttLib import TTFont,TTCollection
ROOT=Path(__file__).resolve().parents[1]
FONTS={'en':Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'),'zh':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/native-tables44-fixed';out.mkdir(exist_ok=False);cases=[];sources={};actions=[]
    xs=[48,142,242,372,548];ys=[110,150,190,234,278,322]
    for lang,path in FONTS.items():
        font=fitz.Font(fontfile=str(path));doc=fitz.open();pages=[]
        for pno in [1,2]:
            page=doc.new_page(width=596,height=792);page.insert_font(fontname='Table44',fontfile=str(path));cells=[]
            before=f'Continuation audit 00731 page {pno}' if lang=='en' else f'续表核验 00731 第{pno}页'
            after=f'End of source page {pno}' if lang=='en' else f'原始第{pno}页结束'
            def put(value,x,y,size=10):
                assert all(font.has_glyph(ord(c)) for c in value),value
                page.insert_text((x,y),value,fontname='Table44',fontsize=size)
            put(before,48,60,12)
            # Source rules explicitly describe rectangular spans, not inferred semantics.
            for x in xs:
                page.draw_line((x,ys[0] if x in [xs[0],xs[-1]] else ys[1]),(x,ys[-1]),width=.6)
            for r,y in enumerate(ys):page.draw_line((xs[1] if r==3 else xs[0],y),(xs[-1],y),width=.6)
            caption=(f'PAYMENTS 00957'+(' CONTINUED' if pno==2 else '')) if lang=='en' else ('付款记录 00957'+(' 续表' if pno==2 else ''))
            headers=['Group','Record','Date','Amount'] if lang=='en' else ['组别','编号','日期','金额']
            group='SET A' if lang=='en' else '甲组';last='SET B' if lang=='en' else '乙组'
            if pno==1:data=[['00064','2092-11-06','-0037.40'],['00128','2092-11-07','+0081.25'],['00256','2092-11-08','0093.70']]
            else:data=[['00512','2092-11-09','-0074.80'],['01024','2092-11-10','+0162.50'],['02048','2092-11-11','0187.40']]
            def cell(row,col,value,rowspan=1,colspan=1):
                box=[xs[col],ys[row],xs[col+colspan],ys[row+rowspan]]
                baseline=(box[1]+box[3])/2+3
                assert font.text_length(value,fontsize=10)<=box[2]-box[0]-12
                put(value,box[0]+6,baseline)
                cells.append(dict(row=row,column=col,rowSpan=rowspan,columnSpan=colspan,text=value,boxPt=box,baselinePt=baseline))
            cell(0,0,caption,colspan=4)
            for col,value in enumerate(headers):cell(1,col,value)
            cell(2,0,group,rowspan=2)
            for row,fields in enumerate(data,start=2):
                if row==4:cell(4,0,last)
                for col,value in enumerate(fields,start=1):cell(row,col,value)
            put(after,48,374,10)
            # Logical row/cell order is independent of the vertically centered merged label.
            cells.sort(key=lambda c:(c['row'],c['column']))
            assert len(cells)==16
            pages.append(dict(page=pno,rows=5,columns=4,before=before,after=after,cells=cells,xGridPt=xs,yGridPt=ys))
        # Declare actual source Unicode, as in validated native22/23 producers.
        # Shared font glyphs must not reverse-map ASCII hyphen/space or 金 to compatibility text.
        mapping={}
        for truth in pages:
            for value in [truth['before'],truth['after'],*[c['text'] for c in truth['cells']]]:
                for char in value:
                    gid=font.has_glyph(ord(char));assert gid and (gid not in mapping or mapping[gid]==char)
                    mapping[gid]=char
        entries=[f'<{gid:04X}> <{char.encode("utf-16-be").hex().upper()}>' for gid,char in sorted(mapping.items())]
        cmap='/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /Native44Truth def /CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange\n'
        for start in range(0,len(entries),100):
            chunk=entries[start:start+100];cmap+=str(len(chunk))+' beginbfchar\n'+'\n'.join(chunk)+'\nendbfchar\n'
        cmap+='endcmap CMapName currentdict /CMap defineresource pop end end'
        for ref in {f[0] for p in doc for f in p.get_fonts() if f[4]=='Table44'}:
            kind,value=doc.xref_get_key(ref,'ToUnicode');assert kind=='xref';doc.update_stream(int(value.split()[0]),cmap.encode())
        pdf=out/(lang+'-continuation.pdf');doc.save(pdf,garbage=4,deflate=True,no_new_id=True);doc.close();sources[pdf.name]=sha(pdf)
        with fitz.open(pdf) as opened:
            assert len(opened)==2 and all(not p.get_images() for p in opened)
            for p,truth in zip(opened,pages):
                extracted=p.get_text();assert all(extracted.count(c['text'])==1 for c in truth['cells']),extracted
        case=dict(id=lang,language=lang,file=pdf.name,sourceSha256=sha(pdf),pages=pages,edit=dict(table=1,row=2,column=3,old='-0074.80',new='-012345.67'))
        cases.append(case)
        actions.extend([dict(id=lang+'-word',input=pdf.name,target='docx'),dict(id=lang+'-office',input='@'+lang+'-word',target='pdf'),dict(id=lang+'-edited-office',input='@'+lang+'-word',target='pdf',edit=case['edit']),dict(id=lang+'-edited-api',input='@'+lang+'-edited-office',target='txt')])
    fonts={}
    for lang,path in FONTS.items():
        font=TTCollection(path).fonts[0] if path.suffix=='.ttc' else TTFont(path)
        fonts[lang]=dict(path=str(path),sha256=sha(path),version=sorted({n.toUnicode() for n in font['name'].names if n.nameID==5}),license='SIL-OFL-1.1')
    plan=dict(parentRevision='b8c337ffe2b0d617330f7401115d9249d8631318',hypothesis='Repeated headers,rectangular horizontal+vertical spans and body order survive page-separated native table conversion and one cell edit;not an inferred stitched continuous table',generatorSha256=sha(Path(__file__)),pymupdfVersion=fitz.VersionBind,fonts=fonts,cases=cases,sources=sources,actions=actions,
        acceptance=dict(nativePdfPagesPerCase=2,editableTablesPerCase=2,logicalCellsPerTable=16,gridRowsPerTable=5,gridColumnsPerTable=4,actualHorizontalSpansPerCase=2,actualVerticalRestartsPerCase=2,actualVerticalContinuationsPerCase=2,
            exactCellTextAndSignedNumericLiteral=True,noDuplicateBodyCellText=True,pageBodyTableOrderExact=True,officePageCountExact=True,visibleGlyphsInsideCorrespondingCell=True,gridAndTextGeometryTolerancePt=9,
            editedCellOnlyOneRun=True,otherWordPartsExact=True,editedOfficeAndActualApiRetainNewLiteralAndExcludeOld=True,fullTextPresenceAloneDoesNotCertifyTableEditability=True),
        limits=dict(httpContracts=8,wholeHttpSeconds=480,perContractSeconds=120,renderDpi=150,sourcePages=4,normalOfficePages=4,editedOfficePages=4,noOCRParameterSweep=True),
        boundaries=['Originalnativeunrotatedruledrectangulartablesonly','Twoexplicitpage-localWordtables;no automaticcross-page tablemerge or new repeat-header behavior','No scans/OCR tables,nonrectangularspans,arbitrarilylongedits,rotation or previousrejectedflow redesign'])
    (out/'expected.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n');print('Frozen2native PDFs/4pages/64logicalcells/8actualHTTP contracts;zeroOCR')
if __name__=='__main__':main()
