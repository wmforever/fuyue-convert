#!/usr/bin/env python3
"""Actual Office PDF crops/native glyphs plus six explicitly separate bundled ROI reads."""
import argparse,hashlib,json,subprocess,xml.etree.ElementTree as E,zipfile
from pathlib import Path
import fitz
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';V='{urn:schemas-microsoft-com:vml}'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--phase',choices=['before','after'],required=True);args=p.parse_args()
    out=ROOT/('qa-samples/work/iteration36-render-'+args.phase);out.mkdir(exist_ok=False)
    truth=json.loads((ROOT/'qa-samples/generated/long-amount36-final/expected.json').read_text());rows=[]
    runtime=ROOT/'qa-samples/work/iteration25-review-app/ocr';engine=runtime/'bin/tesseract'
    for case in truth['cases']:
        name=case['id'];folder=ROOT/('qa-samples/report/iteration36-after' if args.phase=='after' else 'qa-samples/report/iteration36-before' if case['language']=='en' else 'qa-samples/report/iteration36-zh-before')
        with zipfile.ZipFile(folder/(name+'-word-result.docx')) as z:
            root=E.fromstring(z.read('word/document.xml'));media={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('word/media/')}
            maskStyles=[s.get('style') for s in root.iter(V+'rect') if s.get('id','').startswith('ocr-mask-')]
            shapes=[dict(id=s.get('id'),style=s.get('style'),texts=[t.text for t in s.iter(W+'t')]) for s in root.iter(V+'rect') if list(s.iter(W+'t'))]
            target=next(s for s in shapes if any(case['old'] in t for t in s['texts']))
        row=dict(id=name,sourceWordSha256=sha(folder/(name+'-word-result.docx')),mediaSha256=media,maskStyles=maskStyles,originalAmountShape=target,shapes=shapes,pdfs={})
        for stage in ['office','edited-office']:
            source=folder/(name+'-'+stage+'-result.pdf')
            with fitz.open(source) as doc:
                assert len(doc)==1;page=doc[0];pix=page.get_pixmap(dpi=300,alpha=False)
                image=out/(name+'-'+stage+'.png');pix.save(image)
                # All sources are 2000x1700 at300DPI; fixed PDF/Word page remains480x408pt.
                assert abs(page.rect.width-480)<.02 and abs(page.rect.height-408)<.02
                crop=out/(name+'-'+stage+'-amount.png');page.get_pixmap(matrix=fitz.Matrix(300/72,300/72),clip=fitz.Rect(140*72/300,650*72/300,1960*72/300,930*72/300),alpha=False).save(crop)
                row['pdfs'][stage]=dict(sha256=sha(source),pageRect=list(page.rect),nativeText=page.get_text(),words=page.get_text('words'),renderSha256=sha(image),cropSha256=sha(crop),render=image.name,crop=crop.name)
        crop=out/(name+'-edited-office-amount.png');base=out/(name+'-visible');log=out/(name+'-visible.log')
        command=[str(engine),str(crop),str(base),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm','6']
        with log.open('x') as stream:subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=25)
        row['actualRenderedCropOcr']=(base.with_suffix('.txt')).read_text();row['roiCommand']=command;rows.append(row)
    (out/'evidence.json').write_text(json.dumps(dict(rows=rows,extraDiagnosticOcrInvocations=6,ocrConfigChanged=False,pinnedEngineSha256=sha(engine),pinnedRuntimeManifestSha256=sha(runtime/'OCR-RUNTIME.json'),helperSha256=sha(Path(__file__))),indent=2,ensure_ascii=False)+'\n')
    print('actual Office rendered6;separate bounded ROI OCR6;notHTTP substitute')
if __name__=='__main__':main()
