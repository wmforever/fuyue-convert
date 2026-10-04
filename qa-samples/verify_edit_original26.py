#!/usr/bin/env python3
"""Retain and remeasure the original 127.50 -> 128.75 failure, without rerunning it."""
from pathlib import Path
import json, math, os, subprocess, xml.etree.ElementTree as ET
import fitz
from PIL import Image,ImageChops,ImageDraw
from verify_edit_iteration26 import parts,sha,V
from qa_process_guard import ManagedProcess,install_shutdown_handlers
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1]
def main():
    install_shutdown_handlers();out=ROOT/'qa-samples/work/iteration26-original-visible';assert not out.exists();out.mkdir()
    before=ROOT/'qa-samples/report/iteration25-smoke-remaining';after=ROOT/'qa-samples/report/iteration26-original-after';runtime=Path(os.environ['FORMAT_CONVERTER_APP_HOME'])/'ocr'
    files=[before/'scan-edited-office-result.pdf',after/'original-edited-office-result.pdf'];words=[before/'scan-word-result.docx',after/'original-word-result.docx'];rows=[];images=[]
    expected='REVIEW RECORD 2036\nRecord 00842\nAmount 128.75\nDate 2036-04-19\n';sources=[parts(p) for p in words];roots=[ET.fromstring(s['word/document.xml']) for s in sources]
    for root in roots:
        for n in root.iter(V+'rect'):
            if n.get('id','').startswith('ocr-mask-'):n.set('style',n.get('style').replace('z-index:-251658751;','z-index:1;'))
    assert ET.tostring(roots[0])==ET.tostring(roots[1]);assert {k:v for k,v in sources[0].items() if k.startswith('word/media/')}=={k:v for k,v in sources[1].items() if k.startswith('word/media/')}
    for stage,p in zip(['before','after'],files,strict=True):
        with fitz.open(p) as d:
            assert len(d)==1;page=d[0];pix=page.get_pixmap(dpi=300,alpha=False);image=Image.frombytes('RGB',(pix.width,pix.height),pix.samples);images.append(image);native=page.get_text();order=[x[0] for x in page.get_bboxlog()]
        row={'stage':stage,'pdfSha256':sha(p),'nativeText':native,'paintOrder':order}
        for scope,im,psm in [('page',image,3),('amount',image.crop((300,425,600,550)),6)]:
            imagePath=out/(stage+'-'+scope+'.png');im.save(imagePath);dest=out/(stage+'-'+scope)
            command=[str(runtime/'bin/tesseract'),str(imagePath),str(dest),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm',str(psm)]
            with (out/(stage+'-'+scope+'.log')).open('x') as log:
                with ManagedProcess(command,receipt=out/(stage+'-'+scope+'.receipt.json'),stdout=log,stderr=subprocess.STDOUT) as process:assert process.process.wait(timeout=30)==0
            text=dest.with_suffix('.txt').read_text();truth=expected if scope=='page' else '128.75';row[scope+'Ocr']={'text':text,'metrics':metrics(truth,text),'numericLexemesExact':numeric_tokens(text)==numeric_tokens(truth)}
        api=(before/'scan-edited-text-result.txt' if stage=='before' else after/'original-edited-text-result.txt').read_text();row['apiText']={'text':api,'metrics':metrics(expected,api),'numericLexemesExact':numeric_tokens(expected)==numeric_tokens(api)};rows.append(row)
    diff=ImageChops.difference(*images);draw=ImageDraw.Draw(diff)
    for n in roots[1].iter(V+'rect'):
        if not n.get('id','').startswith('ocr-mask-'):continue
        styles=dict(x.split(':',1) for x in n.get('style').split(';') if ':' in x);x,y,w,h=[float(styles[k].removesuffix('pt'))*300/72 for k in ['margin-left','margin-top','width','height']]
        draw.rectangle((math.floor(x)-2,math.floor(y)-2,math.ceil(x+w)+2,math.ceil(y+h)+2),fill=(0,0,0))
    assert diff.getbbox() is None
    result={'originalWordSha256':sha(words[0]),'unchangedGeometryAndScanBytes':True,'unchangedPixelsOutsideSampledMasks':True,'rows':rows}
    (ROOT/'docs/cloud-edit-original26-results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(rows))
if __name__=='__main__':main()
