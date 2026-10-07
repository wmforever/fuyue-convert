#!/usr/bin/env python3
"""Measure visible Office edits separately from native PDF and API text.

Runs bundled OCR only on the actual composited Office page, never on an
extracted original scan. Preserves numeric signs, decimal points and zeros.
"""
import argparse, copy, hashlib, io, json, os, re, subprocess, time, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
import fitz
from PIL import Image, ImageChops, ImageDraw
from qa_process_guard import ManagedProcess, install_shutdown_handlers, matches
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
V='{urn:schemas-microsoft-com:vml}'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def parts(p):
    with zipfile.ZipFile(p) as z: return {n:z.read(n) for n in z.namelist()}
def pixels(data):
    with Image.open(io.BytesIO(data)) as image: return image.size, hashlib.sha256(image.convert('RGB').tobytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--corpus',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    install_shutdown_handlers();assert not a.out.exists();a.out.mkdir(parents=True)
    report=json.loads((a.report/'report.json').read_text());manifest=json.loads((a.corpus/'expected.json').read_text())
    assert sha(a.corpus/'expected.json')==report['manifestSha256']
    assert report['health']['ocr']['bundled'] and not report['newZombies'] and report['supervision']['waitpidNoChildren']
    assert not any(matches(v) for v in report['workerIdentities'])
    runtime=Path(os.environ['FORMAT_CONVERTER_APP_HOME'])/'ocr';rows=[]
    for case in manifest['cases']:
        name=case['id'];r={'case':name,'editingNotPromised':case['editingNotPromised']}
        src=parts(a.report/(name+'-word-result.docx'));edit=parts(a.report/(name+'-edited-office-edited.docx'))
        root=ET.fromstring(src['word/document.xml']);expected=copy.deepcopy(root)
        nodes=[n for n in expected.iter(W+'t') if case['old'] in (n.text or '')];assert len(nodes)==1
        nodes[0].text=nodes[0].text.replace(case['old'],case['new']);assert ET.tostring(expected)==ET.tostring(ET.fromstring(edit['word/document.xml']))
        assert {k:v for k,v in src.items() if k!='word/document.xml'}=={k:v for k,v in edit.items() if k!='word/document.xml'}
        source=(a.corpus/case['file']).read_bytes()
        with fitz.open(a.report/(name+'-pdf-result.pdf')) as d:
            assert pixels(source) in [pixels(d.extract_image(i[0])['image']) for i in d[0].get_images()]
        raster=a.out/(name+'-source-pdfbox.png')
        command=['java','-cp','qa-samples/work/iteration20-classpath/BOOT-INF/lib/*','qa-samples/PdfReviewRasterProbe.java',str(a.report/(name+'-pdf-result.pdf')),str(raster)]
        with (a.out/(name+'-source-pdfbox.log')).open('x') as log:
            with ManagedProcess(command,receipt=a.out/(name+'-source-pdfbox.receipt.json'),stdout=log,stderr=subprocess.STDOUT) as process:
                assert process.process.wait(timeout=30)==0
        assert pixels(raster.read_bytes()) in [pixels(v) for k,v in src.items() if k.startswith('word/media/')]
        r.update(originalPngPixelsInInputPdf=True,originalPdfBoxScanPixelsPreserved=True,onlyIntendedTextChanged=True)
        masks=[n for n in root.iter(V+'rect') if n.get('id','').startswith('ocr-mask-')]
        r['masks']=[{'style':n.get('style'),'color':n.get('fillcolor')} for n in masks]
        pages=[]
        for suffix in ['office','edited-office']:
            pdf=a.report/(name+'-'+suffix+'-result.pdf')
            with fitz.open(pdf) as d:
                assert len(d)==1;page=d[0];pix=page.get_pixmap(dpi=300,alpha=False)
                pages.append(Image.frombytes('RGB',(pix.width,pix.height),pix.samples))
                if suffix=='edited-office':
                    r['nativeText']=page.get_text();r['paintOrder']=[v[0] for v in page.get_bboxlog()];r['nativeWordBoxes']=page.get_text('words')
        # Frozen generator puts only the edited amount within this region.
        # Full page comparison outside it catches movements of all other content.
        region=(300,450,950,650);diff=ImageChops.difference(*pages).convert('RGB');draw=ImageDraw.Draw(diff);draw.rectangle(region,fill=(0,0,0))
        r['unchangedOutsideAmountRegion']=diff.getbbox() is None
        r['editRegionPixels']=region
        image=a.out/(name+'-edited-visible.png');pages[1].save(image)
        for scope,img,psm in [('page',pages[1],3),('amount',pages[1].crop(region),6)]:
            path=a.out/(name+'-'+scope+'.png');img.save(path);dest=a.out/(name+'-'+scope)
            command=[str(runtime/'bin/tesseract'),str(path),str(dest),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm',str(psm)]
            started=time.monotonic()
            with (a.out/(name+'-'+scope+'.log')).open('x') as log:
                with ManagedProcess(command,receipt=a.out/(name+'-'+scope+'.receipt.json'),stdout=log,stderr=subprocess.STDOUT) as process:
                    assert process.process.wait(timeout=30)==0
            text=dest.with_suffix('.txt').read_text();r[scope+'Ocr']={'text':text,'seconds':time.monotonic()-started,'numericLexemesExact':numeric_tokens(text)==numeric_tokens(case['editedExpected'] if scope=='page' else case['new']),'metrics':metrics(case['editedExpected'] if scope=='page' else case['new'],text)}
        text=(a.report/(name+'-edited-text-result.txt')).read_text();r['apiText']={'text':text,'metrics':metrics(case['editedExpected'],text),'numericLexemesExact':numeric_tokens(text)==numeric_tokens(case['editedExpected'])}
        rows.append(r);print(name,r['amountOcr']['text'].strip(),r['amountOcr']['numericLexemesExact'],r['unchangedOutsideAmountRegion'],flush=True)
    result={'manifestSha256':report['manifestSha256'],'jarSha256':report['jarSha256'],'helperSha256':sha(Path(__file__)),'runtimeManifestSha256':sha(runtime/'OCR-RUNTIME.json'),'runtimeManifest':json.loads((runtime/'OCR-RUNTIME.json').read_text()),'renderLibrary':fitz.VersionBind,'dpi':300,'cases':rows,'httpResources':report['resources'],'httpContracts':len(report['cases']),'httpSuccess':sum(c['task']['status']=='SUCCESS' for c in report['cases']),'workersAbsent':len(report['workerIdentities']),'ECHILD':True}
    (a.out/'evidence.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
if __name__=='__main__':main()
