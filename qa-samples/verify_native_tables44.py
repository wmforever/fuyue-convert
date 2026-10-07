#!/usr/bin/env python3
"""Audit source/actualHTTP cells, Office geometry/renders and one-cell edits;zeroOCR."""
import hashlib,json,math,zipfile,xml.etree.ElementTree as E
from pathlib import Path
import fitz,numpy as np
from PIL import Image,ImageDraw,ImageFont
from qa_process_guard import matches
from record_cloud_provenance import inputs,fingerprint
from verify_cloud_ocr import metrics
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'qa-samples/work';W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def compact(t):return ''.join(t.split())
def cell_text(c):return ''.join(t['text'] for p in c['paragraphs'] for t in p['runs'])
def xml_text(node):return ''.join(t.text or '' for t in node.iter(W+'t'))
def mm_rect(r):return [r['x']*72/25.4,r['y']*72/25.4,(r['x']+r['width'])*72/25.4,(r['y']+r['height'])*72/25.4]
def word_cells(table):
    result={};physical=0;spans=[];merges=[]
    for row,tr in enumerate(table.findall(W+'tr')):
        column=0
        for tc in tr.findall(W+'tc'):
            physical+=1;grid=tc.find('./'+W+'tcPr/'+W+'gridSpan');span=int(grid.get(W+'val')) if grid is not None else 1
            merge=tc.find('./'+W+'tcPr/'+W+'vMerge');kind=merge.get(W+'val') if merge is not None else None
            if span>1:spans.append((row,column,span))
            if kind:merges.append((row,column,kind))
            if kind!='continue':result[(row,column)]=xml_text(tc)
            else:assert not xml_text(tc),'continuation cell must not duplicate merge anchor'
            column+=span
        assert column==4
    return result,physical,spans,merges
def expected_api(case):
    pages=[]
    for p in case['pages']:
        cells={(c['row'],c['column']):c['text'] for c in p['cells']}
        if p['page']==2:cells[(2,3)]=case['edit']['new']
        lines=[p['before'],cells[(0,0)]]
        for row in range(1,5):lines.append('\t'.join(cells.get((row,col),'') for col in range(4)))
        pages.append('\n'.join([*lines,p['after']])+'\n')
    return '\n\f\n'.join(pages)
def main():
    corpus=ROOT/'qa-samples/generated/native-tables44-fixed';plan=read(corpus/'expected.json');out=WORK/'iteration44-http';http=read(out/'report.json');render=WORK/'iteration44-render';render.mkdir(exist_ok=False)
    assert http['status']=='completed' and not http['failures'] and not http.get('unsupportedEdits') and not http['newZombies']
    assert len(http['cases'])==len(http['workerIdentities'])==8 and http['supervision']['waitpidNoChildren']
    assert all(not matches(x) for x in http['workerIdentities'])
    assert http['manifestSha256']==sha(corpus/'expected.json') and http['jarSha256']=='1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b'
    for contract in http['cases']:assert contract['task']['status']=='SUCCESS' and sha(out/contract['artifact'])==contract['sha256']
    for name,value in plan['sources'].items():assert sha(corpus/name)==value
    for font in plan['fonts'].values():assert sha(Path(font['path']))==font['sha256']
    r=read(WORK/'iteration43-provenance.json');assert inputs()==r['buildInputs'] and fingerprint(inputs())==r['buildInputSha256'] and sha(ROOT/'web-api/target/web-api-0.1.5.jar')==r['jarSha256']
    rows=[];all_png=[];warnings=[w for c in http['cases'] for w in c['task'].get('warnings',[])]
    assert not warnings
    for case in plan['cases']:
        lang=case['id'];raw=read(WORK/'iteration44-source-models'/(case['file']+'.json'));source_pages=raw['analyzedPages'];assert len(source_pages)==2
        with zipfile.ZipFile(out/(lang+'-word-result.docx')) as z:parts={n:z.read(n) for n in z.namelist()}
        xml=E.fromstring(parts['word/document.xml']);body=xml.find(W+'body');tables=body.findall(W+'tbl');assert len(tables)==2
        assert not any(n.startswith('word/media/') for n in parts) and not xml.findall('.//'+W+'txbxContent')
        actual_order=[(n.tag.split('}')[-1],xml_text(n)) for n in body if n.tag in [W+'p',W+'tbl'] and xml_text(n)]
        expected_order=[];cell_records=[]
        for truth,source,table in zip(case['pages'],source_pages,tables):
            assert len(source['tables'])==1 and not source['images']
            st=source['tables'][0];source_cells={(c['row'],c['column']):cell_text(c) for c in st['cells']}
            expected={(c['row'],c['column']):c['text'] for c in truth['cells']}
            actual,physical,spans,merges=word_cells(table)
            assert source_cells==actual==expected and len(actual)==16 and physical==17
            assert spans==[(0,0,4)] and merges==[(2,0,'restart'),(3,0,'continue')]
            assert len(table.findall(W+'tr'))==5 and len(table.findall('./'+W+'tblGrid/'+W+'gridCol'))==4
            assert not table.findall('.//'+W+'tblHeader'),'explicit source repeated rows;no automatic repeat-header claim'
            expected_order.extend([('p',truth['before']),('tbl',''.join(c['text'] for c in truth['cells'])),('p',truth['after'])])
            cell_records.append(dict(page=truth['page'],logicalCells=16,physicalCells=17,sourceAndWordCellTextExact=True,horizontalSpans=spans,verticalMerges=merges))
        assert actual_order==expected_order
        edit=case['edit'];edited_path=out/(lang+'-edited-office-edited.docx')
        with zipfile.ZipFile(edited_path) as z:edited={n:z.read(n) for n in z.namelist()}
        assert parts.keys()==edited.keys() and all(a==edited[n] for n,a in parts.items() if n!='word/document.xml')
        nodes=[n for n in xml.iter(W+'t') if edit['old'] in (n.text or '')];assert len(nodes)==1 and nodes[0].text.count(edit['old'])==1
        nodes[0].text=nodes[0].text.replace(edit['old'],edit['new']);assert E.tostring(xml,encoding='utf-8',xml_declaration=True)==edited['word/document.xml']
        api=(out/(lang+'-edited-api-result.txt')).read_text();expected=expected_api(case)
        assert api==expected,(lang,'API literal/row/column/page separators mismatch',repr(api),repr(expected))
        assert edit['new'] in api and edit['old'] not in api
        stages={};images={};geometry=[];ink_counts=[]
        for stage,path in [('source',corpus/case['file']),('normal',out/(lang+'-office-result.pdf')),('edited',out/(lang+'-edited-office-result.pdf'))]:
            document=fitz.open(path);assert len(document)==2
            model=raw if stage=='source' else read(WORK/'iteration44-office-models'/(lang+('-office-result.pdf.json' if stage=='normal' else '-edited-office-result.pdf.json')))
            for index,(page,truth,actual) in enumerate(zip(document,case['pages'],model['analyzedPages'])):
                assert not page.get_images() and not actual['images'] and len(actual['tables'])==1
                table=actual['tables'][0];actual_cells={(c['row'],c['column']):c for c in table['cells']};expected_cells={(c['row'],c['column']):c['text'] for c in truth['cells']}
                if stage=='edited' and index==1:expected_cells[(2,3)]=edit['new']
                assert {k:cell_text(c) for k,c in actual_cells.items()}==expected_cells
                assert len(table['xGrid'])==5 and len(table['yGrid'])==6 and len(table['merges'])==2
                dx=max(abs(x-y)*72/25.4 for x,y in zip(source_pages[index]['tables'][0]['xGrid'],table['xGrid']));dy=max(abs(x-y)*72/25.4 for x,y in zip(source_pages[index]['tables'][0]['yGrid'],table['yGrid']))
                assert max(dx,dy)<=plan['acceptance']['gridAndTextGeometryTolerancePt']
                geometry.append(dict(stage=stage,page=index+1,maxGridXDeltaPt=dx,maxGridYDeltaPt=dy,tableBoxPt=mm_rect(table['box'])))
                trace=page.get_texttrace();assert trace and all(t['opacity']>=.99 and t['type'] in [0,1] for t in trace)
                png=render/(lang+'-'+stage+'-p'+str(index+1)+'.png');pix=page.get_pixmap(dpi=150,alpha=False);pix.save(png);all_png.append(png);image=Image.open(png).convert('RGB');images[(stage,index)]=image
                array=np.asarray(image);dpi=150/72
                for key,c in actual_cells.items():
                    box=mm_rect(c['box'])
                    for paragraph in c['paragraphs']:
                        for run in paragraph['runs']:
                            bounds=mm_rect(run['box']);assert bounds[0]>=box[0]-.5 and bounds[1]>=box[1]-.5 and bounds[2]<=box[2]+.5 and bounds[3]<=box[3]+.5,(lang,stage,key,bounds,box)
                            left,top,right,bottom=[round(v*dpi) for v in bounds];crop=array[max(0,top):bottom+1,max(0,left):right+1];dark=int(np.any(crop<160,axis=2).sum());assert dark>=4
                            ink_counts.append(dict(stage=stage,page=index+1,row=key[0],column=key[1],text=run['text'],visibleDarkPixelsInGlyphBox=dark))
            stages[stage]=dict(pages=2,pdfSha256=sha(path),cellTextExact=True,editableNativeText=True,noRasterBacking=True)
            document.close()
        diff_records=[]
        for index in [0,1]:
            before=np.asarray(images[('normal',index)]);after=np.asarray(images[('edited',index)]);assert before.shape==after.shape;changed=np.any(before!=after,axis=2)
            if index==0:assert not changed.any();diff_records.append(dict(page=1,pixelExact=True,changedPixels=0))
            else:
                table=read(WORK/'iteration44-office-models'/(lang+'-office-result.pdf.json'))['analyzedPages'][1]['tables'][0];cell=next(c for c in table['cells'] if c['row']==2 and c['column']==3);box=mm_rect(cell['box']);left,top,right,bottom=[round(v*150/72) for v in box];outside=changed.copy();outside[max(0,top-2):bottom+3,max(0,left-2):right+3]=False
                assert changed.any() and not outside.any();diff_records.append(dict(page=2,changedPixels=int(changed.sum()),outsideEditedCellChangedPixels=0,editCellBoxPt=box))
        # Contact sheet is a labelled inspection view; full150DPI PNGs determine metrics.
        contact=Image.new('RGB',(2700,1300),'white');draw=ImageDraw.Draw(contact);label_font=ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',18)
        for index in [0,1]:
            for column,stage in enumerate(['source','normal','edited']):
                image=images[(stage,index)];cropped=image.crop((0,0,image.width,round(415*150/72)));cropped.thumbnail((900,610));contact.paste(cropped,(column*900,index*650+30));draw.text((column*900+10,index*650+5),lang+' '+stage+' page '+str(index+1),fill='black',font=label_font)
        contact.save(render/(lang+'-contact.png'))
        rows.append(dict(id=lang,wordSha256=sha(out/(lang+'-word-result.docx')),cellStructure=cell_records,bodyTableCrossPageOrderExact=True,noCellTextDuplicatedInBody=True,autoHeaderFlags=0,pageLocalTablesNotStitched=True,
            oneEditedCellOnly=True,allOtherWordPartsByteExact=True,edit=edit,apiRawExactIncludingTabsAndPageBreak=True,apiMetrics=metrics(expected,api),stages=stages,geometry=geometry,inkChecks=ink_counts,normalToEditedRasterDiff=diff_records))
    result=dict(parentRevision=plan['parentRevision'],manifestSha256=sha(corpus/'expected.json'),generatorSha256=plan['generatorSha256'],status='passed-finite-native-page-local-table-boundary',productionChanged=False,
        counts=dict(originalNativePdfs=2,sourcePages=4,logicalSourceCells=64,actualHttpContracts=8,independentWorkers=8,normalOfficeConversions=2,editedOfficeConversions=2,editedApiReads=2,diagnosticOCR=0,sourceModelParses=2,officeModelParses=4,renderedPages=12),
        acceptance=plan['acceptance'],rows=rows,resources=http['resources'],warnings=warnings,sourceFonts=plan['fonts'],versions=dict(java='17.0.16+8',pymupdf=fitz.VersionBind,pdfbox='3.0.8',poi='5.4.1',office='26.8.0.0.alpha0+ 2c87e51eeaa2b413ff4ae097b2705eea1995d8e5'),sourceBoundJarSha256=http['jarSha256'],productionFingerprint=r['buildInputSha256'],sourceBoundClasses=232,
        renderedPngs=[dict(path=str(p.relative_to(ROOT)),sha256=sha(p)) for p in all_png],allWorkersAbsent=True,ECHILD=True,newZombies=[],
        inheritedLocalTests='Unchanged40clean505total/504pass/1optional signedfixture skip;10bundledtests executed;not a fresh44localfullbuild',
        boundaries=plan['boundaries'],unrun=['Automaticstitchedcross-pageTable and automaticrepeatedheaderaftereditpagination','Scanned/nonrectangular/rotated/nestedtables/arbitrarylongedit/rowinsert/delete/mixedpagegeometry','MicrosoftWord/nativeMacWindowspackages/optional signedfixture;priorOCRAPI/oldscan/Wordoverprintlimitations unchanged'],initialSourceFailure='OriginalMuPDF reverseCMap changed nativeASCIIspaces/hyphens and金 compatibility character;fixedproducer declaresexactToUnicode before8HTTP. Initialsources/log/receipt retained andexcludedfromacceptedcorpus.')
    (ROOT/'docs/cloud-native-tables44-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('Verified64logicalcells/4tables/8HTTP;12renderedpages;2literalamountedits/APIexact;no productionfixneeded')
if __name__=='__main__':main()
