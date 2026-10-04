#!/usr/bin/env python3
"""Finite native-boundary/Word regression on frozen iteration30/31 artifacts.

Generate the eight PDFs with GeneratePdfBoundaries31 first. --prepare copies
accepted prior HTTP artifacts; it never reruns earlier matrices. Default mode
checks final HTTP downloads, exact whitespace, numeric lexemes, Word parts and
actual Office pixels. Evidence paths remain under ignored QA directories.
"""
import argparse,hashlib,json,re,xml.etree.ElementTree as E
from pathlib import Path
import fitz
from qa_process_guard import matches
from verify_edit_iteration26 import parts
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
V='{urn:schemas-microsoft-com:vml}'
RESERVE='RESERVE REVIEW 2068\nRecord 00916\nAmount 00062.35\nDate 2067-11-23\nControl 45.70\n'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def distance(a,b):
    row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        nxt=[i]
        for j,y in enumerate(b,1):nxt.append(min(nxt[-1]+1,row[j]+1,row[j-1]+(x!=y)))
        row=nxt
    return row[-1]
def text_metrics(expected,actual):
    return dict(actual=actual,exact=actual==expected,rawEditDistance=distance(expected,actual),
                rawCer=distance(expected,actual)/max(1,len(expected)),
                whitespaceNormalized=metrics(expected,actual),
                numericLexemesExact=numeric_tokens(expected)==numeric_tokens(actual))
def checked_report(folder,count):
    report=load(folder/'report.json')
    assert report['status']=='completed' and len(report['cases'])==count
    assert all(c['task']['status']=='SUCCESS' for c in report['cases'])
    assert not report['newZombies'] and report['supervision']['waitpidNoChildren']
    assert len(report['workerIdentities'])==count and all(not matches(p) for p in report['workerIdentities'])
    return report
def main():
    p=argparse.ArgumentParser();p.add_argument('--prepare',action='store_true');a=p.parse_args()
    generated=ROOT/'qa-samples/generated';report=ROOT/'qa-samples/report'
    controls=generated/'boundaries31';corpus=generated/'boundaries31-final';old=report/'iteration30-heading-after'
    if a.prepare:
        corpus.mkdir(exist_ok=False);truth=load(controls/'expected.json');sources={};actions=list(truth['actions'])
        def put(name,source):
            dest=corpus/name;dest.write_bytes(source.read_bytes());sources[name]=sha(dest);return name
        for name,expected in truth['sources'].items():assert sha(controls/name)==expected;put(name,controls/name)
        source=put('reserve-office.pdf',old/'reserve-office-result.pdf')
        actions.append(dict(id='reserve-artifact-text',input=source,target='txt'))
        source=put('reserve-scan.pdf',generated/'heading30/after/reserve-scan.pdf')
        actions.extend([dict(id='reserve-word',input=source,target='docx'),dict(id='reserve-office',input='@reserve-word',target='pdf'),
                        dict(id='reserve-text',input='@reserve-office',target='txt'),
                        dict(id='reserve-edited-office',input='@reserve-word',target='pdf',edit=dict(old='00062.35',new='-054.80')),
                        dict(id='reserve-edited-text',input='@reserve-edited-office',target='txt')])
        source=put('dark-scan.pdf',generated/'heading30/after/dark-scan.pdf');actions.append(dict(id='dark-word',input=source,target='docx'))
        with fitz.open() as doc:doc.new_page(width=595,height=842);doc.save(corpus/'blank.pdf')
        sources['blank.pdf']=sha(corpus/'blank.pdf');actions.append(dict(id='blank-text',input='blank.pdf',target='txt'))
        (corpus/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,cases=truth['cases'],generatorSha256=sha(Path(__file__))),indent=2,ensure_ascii=False)+'\n')
        print('prepared',len(actions),'finite requests');return
    before=report/'iteration31-before';after=report/'iteration31-after'
    baseline=checked_report(before,8);final=checked_report(after,16);truth=load(corpus/'expected.json')
    assert final['manifestSha256']==sha(corpus/'expected.json')
    for name,expected in truth['sources'].items():assert sha(corpus/name)==expected
    rows=[]
    for case in truth['cases']:
        name=case['id'];expected=case['expected'];filename=name+'-text-result.txt'
        row=dict(id=name,expected=expected,before=text_metrics(expected,(before/filename).read_text()),after=text_metrics(expected,(after/filename).read_text()))
        assert row['after']['exact'] and row['after']['numericLexemesExact'],row
        rows.append(row)
    original=dict(id='original-reserve',expected=RESERVE,before=text_metrics(RESERVE,(old/'reserve-text-result.txt').read_text()),
                  after=text_metrics(RESERVE,(after/'reserve-artifact-text-result.txt').read_text()))
    assert original['after']['exact'] and original['after']['numericLexemesExact'];rows.append(original)
    office=[]
    for name in ['reserve','dark']:
        a,b=[parts(folder/(name+'-word-result.docx')) for folder in [old,after]]
        assert {k:v for k,v in a.items() if k!='docProps/core.xml'}=={k:v for k,v in b.items() if k!='docProps/core.xml'},name
        root=E.fromstring(b['word/document.xml']);assert list(root.iter(W+'t')) and list(root.iter(V+'rect'))
        office.append(dict(id=name,allWordPartsExceptCoreExact=True,editableTextRuns=len(list(root.iter(W+'t'))),shapes=len(list(root.iter(V+'rect'))),
                           mediaSha256={k:hashlib.sha256(v).hexdigest() for k,v in b.items() if k.startswith('word/media/')}))
    with fitz.open(old/'reserve-office-result.pdf') as a,fitz.open(after/'reserve-office-result.pdf') as b:
        assert len(a)==len(b)==1 and a[0].rect==b[0].rect
        pixels=[p[0].get_pixmap(dpi=300,alpha=False).samples for p in [a,b]];assert pixels[0]==pixels[1]
        assert b[0].get_text()==RESERVE
        office[0].update(officePixelsExactAt300Dpi=True,pixelSha256=hashlib.sha256(pixels[1]).hexdigest(),officeNativeTextExact=True)
    assert (after/'reserve-text-result.txt').read_text()==RESERVE
    edited=RESERVE.replace('00062.35','-054.80');assert (after/'reserve-edited-text-result.txt').read_text()==edited
    with fitz.open(after/'reserve-edited-office-result.pdf') as doc:
        assert len(doc)==1 and doc[0].get_text()==edited
    assert (after/'blank-text-result.txt').read_text()==''
    byid={c['case']:c for c in final['cases']}
    assert byid['blank-text']['task']['files'][0]['pageCount']==1
    result=dict(parentRevision='fed6b476476ef0b4dfc65d7b6f32f50877b77540',beforeJarSha256=baseline['jarSha256'],jarSha256=final['jarSha256'],
                manifestSha256=final['manifestSha256'],rows=rows,exactBefore=sum(r['before']['exact'] for r in rows),exactAfter=sum(r['after']['exact'] for r in rows),
                office=office,editedAmountExact=True,blankPageExact=True,whitespaceNormalizedCerIsNotCompleteness=True,
                httpBefore=8,httpAfter=16,workersAbsent=24,newZombies=[],ECHILD=True,
                beforeResources=baseline['resources'],afterResources=final['resources'],
                finalRequests=[dict(id=c['case'],seconds=c['seconds'],warnings=c['task']['warnings']) for c in final['cases']],
                helperSha256={n:sha(ROOT/'qa-samples'/n) for n in ['GeneratePdfBoundaries31.java','PdfExplicitSpaceProbe.java','verify_boundaries31.py','run_edit_iteration26.py','qa_process_guard.py','qa_http_deadline.py']},
                originalSparseOfd='Unresolved strict OCR_NO_NEW_TEXT; iteration30 pixels/matrix not rerun')
    dest=ROOT/'docs/cloud-boundaries31-results.json';dest.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(dict(exactBefore=result['exactBefore'],exactAfter=result['exactAfter'],httpAfter=16,wordPartsExact=True,officePixelsExact=True)))
if __name__=='__main__':main()
