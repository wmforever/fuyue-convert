#!/usr/bin/env python3
"""Actual frozen Word/Office geometry and rendered ROI OCR; never selects a source value."""
import hashlib,io,json,subprocess,xml.etree.ElementTree as E,zipfile
from pathlib import Path
import fitz
from PIL import Image,ImageChops
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';V='{urn:schemas-microsoft-com:vml}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/work/iteration40-render';out.mkdir(exist_ok=False);truth=json.loads((ROOT/'qa-samples/generated/conflict-word40/expected.json').read_text());normal=ROOT/'qa-samples/work/iteration40-normal-http';edited=ROOT/'qa-samples/work/iteration40-edits-http'
    originalRequests={c['case']:c for c in json.loads((normal/'report.json').read_text())['cases']};editRequests={c['case']:c for c in json.loads((edited/'report.json').read_text())['cases']};runtime=ROOT/'qa-samples/work/iteration25-review-app/ocr';rows=[];calls=0
    for case in truth['cases']:
        name=case['id'];req=originalRequests[name+'-word'];word=normal/req['artifact'];source=ROOT/'qa-samples/generated/conflict-word40'/(name+'.png')
        with zipfile.ZipFile(word) as z:
            root=E.fromstring(z.read('word/document.xml'));scans=[]
            for member in z.namelist():
                if member.startswith('word/media/'):
                    image=Image.open(io.BytesIO(z.read(member))).convert('RGB');original=Image.open(source).convert('RGB')
                    if image.size==original.size:
                        assert ImageChops.difference(image,original).getbbox() is None;scans.append(dict(part=member,sha256=hashlib.sha256(z.read(member)).hexdigest(),pixelExact=True,pixels=list(image.size)))
            assert len(scans)==1
            shapes=[dict(id=n.get('id'),style=n.get('style'),texts=[t.text for t in n.iter(W+'t')],fillcolor=n.get('fillcolor')) for n in root.iter(V+'rect')]
            textNodes=[n.text or '' for n in root.iter(W+'t')]
        row=dict(id=name,sourceWordSha256=sha(word),sourceRasterSha256=sha(source),sourceScans=scans,wordTextNodes=textNodes,shapes=shapes,warnings=req['task']['warnings'],pdfs={})
        stages=[('normal',normal/originalRequests[name+'-office']['artifact'],None)]
        stages.extend((e['id'],edited/editRequests[e['id']]['artifact'],e) for e in case['edits'])
        for stage,pdf,edit in stages:
            with fitz.open(pdf) as doc:
                assert len(doc)==1;page=doc[0];render=out/(name+'-'+stage+'.png');page.get_pixmap(dpi=300,alpha=False).save(render)
                assert abs(page.rect.width-612)<.05 and abs(page.rect.height-792)<.05
                # Predeclared common numeric row: enough context to include both representations.
                crop=out/(name+'-'+stage+'-value.png');page.get_pixmap(matrix=fitz.Matrix(300/72,300/72),clip=fitz.Rect(260*72/300,880*72/300,2000*72/300,1100*72/300),alpha=False).save(crop)
                native=page.get_text();words=page.get_text('words');searches={s:[list(r) for r in page.search_for(s)] for s in [case['nativeValue'],case['rasterValue']]}
                if edit:searches[edit['new']]=[list(r) for r in page.search_for(edit['new'])]
            base=out/(name+'-'+stage+'-visible');log=base.with_suffix('.log');command=[str(runtime/'bin/tesseract'),str(crop),str(base),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm','6']
            with log.open('x') as stream:subprocess.run(command,check=True,stdout=stream,stderr=subprocess.STDOUT,timeout=25)
            calls+=1;row['pdfs'][stage]=dict(sha256=sha(pdf),pageRect=[0,0,612,792],nativeText=native,words=words,valueSearchBoxes=searches,render=render.name,renderSha256=sha(render),crop=crop.name,cropSha256=sha(crop),actualVisibleRoiOcr=base.with_suffix('.txt').read_text(),roiOcrCommand=command,edit=edit)
        rows.append(row)
    (out/'evidence.json').write_text(json.dumps(dict(rows=rows,actualOfficePdfs=11,extraDiagnosticRoiOcr=calls,pinnedEngineSha256=sha(runtime/'bin/tesseract'),pinnedRuntimeManifestSha256=sha(runtime/'OCR-RUNTIME.json'),helperSha256=sha(Path(__file__)),roiPixelRectangle=[260,880,2000,1100],roiPsm=6,ocrIsDiagnosticNotHttpAcceptance=True),ensure_ascii=False,indent=2)+'\n');print('ActualOfficePDFs11;renderedROIs11;sourceScanpixels4exact;boundedROI OCR',calls)
if __name__=='__main__':main()
