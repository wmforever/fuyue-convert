#!/usr/bin/env python3
"""Verify final HTTP, Office pixels, editable word structure and numeric surfaces."""
import argparse,copy,hashlib,json,os,re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path
import fitz
from PIL import Image,ImageChops,ImageDraw
from qa_process_guard import ManagedProcess,install_shutdown_handlers,matches
from verify_edit_iteration26 import parts
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';V='{urn:schemas-microsoft-com:vml}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def style(n):return dict(t.split(':',1) for t in n.get('style').split(';') if ':' in t)
def pt(s,key):return float(s[key].removesuffix('pt'))
def process_report(folder,count):
    report=load(folder/'report.json')
    assert len(report['cases'])==count and not report['newZombies'] and report['supervision']['waitpidNoChildren']
    assert len(report['workerIdentities'])==count and all(not matches(p) for p in report['workerIdentities'])
    return report
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--resume-visible',action='store_true');args=parser.parse_args()
    install_shutdown_handlers();out=ROOT/'qa-samples/work/iteration30-visible'
    if args.resume_visible:assert out.is_dir()
    else:out.mkdir(exist_ok=False)
    after=ROOT/'qa-samples/report/iteration30-heading-after';report=process_report(after,22)
    assert report['status']=='completed-with-failures'
    manifest=load(ROOT/'qa-samples/generated/heading30/after/expected.json');assert report['manifestSha256']==sha(ROOT/'qa-samples/generated/heading30/after/expected.json')
    byid={c['case']:c for c in report['cases']};rows=[];runtime=Path(os.environ['FORMAT_CONVERTER_APP_HOME'])/'ocr'
    for case in manifest['cases']:
        name=case['id'];before=ROOT/case['baselineFolder'];oldparts=parts(before/(name+'-word-result.docx'));newparts=parts(after/(name+'-word-result.docx'))
        assert {k:v for k,v in oldparts.items() if k not in ['docProps/core.xml','word/document.xml']}=={k:v for k,v in newparts.items() if k not in ['docProps/core.xml','word/document.xml']}
        a,b=[E.fromstring(p['word/document.xml']) for p in [oldparts,newparts]]
        old={n.get('id'):n for n in a.iter(V+'rect')};new={n.get('id'):n for n in b.iter(V+'rect')};assert old.keys()==new.keys()
        changed=[]
        for key,x in old.items():
            y=new[key]
            if E.tostring(x)==E.tostring(y):continue
            text=''.join(t.text or '' for t in y.iter(W+'t')).strip();assert re.fullmatch('[A-Za-z]{3,32}',text),text
            ox=x.find('.//'+W+'w');ny=y.find('.//'+W+'w');assert ox is not None
            original=int(ox.get(W+'val'));fitted=int(ny.get(W+'val')) if ny is not None else 100;assert 60<=fitted<original
            oldstyle,newstyle=style(x),style(y);oldwidth=pt(oldstyle,'width');newwidth=pt(newstyle,'width')
            oldstyle.pop('width');newstyle.pop('width');assert oldstyle==newstyle
            assert abs(newwidth-oldwidth)<15
            changed.append(dict(text=text,beforeScale=original,afterScale=fitted,beforeStyle=x.get('style'),afterStyle=y.get('style')))
            if ny is None:
                oldprops=x.find('.//'+W+'rPr');props=y.find('.//'+W+'rPr');props.insert(list(oldprops).index(ox),copy.deepcopy(ox))
            else:ny.set(W+'val',ox.get(W+'val'))
            y.set('style',x.get('style'));assert E.tostring(x)==E.tostring(y)
        assert E.tostring(a)==E.tostring(b) and changed,name
        with fitz.open(before/(name+'-office-result.pdf')) as oldpdf,fitz.open(after/(name+'-office-result.pdf')) as newpdf:
            assert len(oldpdf)==len(newpdf)==1 and oldpdf[0].rect==newpdf[0].rect
            oldtext,newtext=oldpdf[0].get_text(),newpdf[0].get_text()
            assert metrics(case['expected'],newtext)['cer']==0
            assert numeric_tokens(oldtext)==numeric_tokens(newtext)==numeric_tokens(case['expected'])
            expected_heading=case['expected'].splitlines()[0]
            assert expected_heading in newtext.splitlines(),newtext
            assert expected_heading not in oldtext.splitlines(),oldtext
            images=[]
            for page in [oldpdf[0],newpdf[0]]:
                pix=page.get_pixmap(dpi=300,alpha=False);images.append(Image.frombytes('RGB',(pix.width,pix.height),pix.samples))
            diff=ImageChops.difference(*images);draw=ImageDraw.Draw(diff)
            for c in changed:
                s=style(old[next(k for k,v in old.items() if v.get('style')==c['beforeStyle'] and ''.join(t.text or '' for t in v.iter(W+'t')).strip()==c['text'])])
                x,y,w,h=[pt(s,k)*300/72 for k in ['margin-left','margin-top','width','height']]
                # Include the baseline's extra wrapped line, whose glyph ink
                # can extend below the declared fixed box. This is comparison
                # evidence only; production masks never grow.
                draw.rectangle((int(x-10),int(y-15),int(x+w+15),int(y+h*3)),fill=(0,0,0))
            assert diff.getbbox() is None,name
            for stage,pdf in [('before',oldpdf),('after',newpdf)]:
                path=out/(name+'-'+stage+'-heading.png')
                png=pdf[0].get_pixmap(dpi=300,alpha=False,clip=fitz.Rect(0,0,pdf[0].rect.width,55)).tobytes('png')
                dest=out/(name+'-'+stage+'-heading');begin=time.monotonic()
                if args.resume_visible:
                    assert path.read_bytes()==png and dest.with_suffix('.txt').is_file()
                    receipt=load(dest.with_suffix('.receipt.json'))
                    assert receipt['rootExitCode']==0 and receipt['waitpidNoChildren'] and receipt['status']=='reaped'
                    assert all(not matches(p) for p in receipt['registered'])
                else:
                    path.write_bytes(png)
                    with (dest.with_suffix('.log')).open('x') as log:
                        with ManagedProcess([runtime/'bin/tesseract',path,dest,'--tessdata-dir',runtime/'tessdata','-l','chi_sim+eng','--psm','6'],
                            receipt=dest.with_suffix('.receipt.json'),stdout=log,stderr=subprocess.STDOUT) as p:
                            assert p.process.wait(timeout=30)==0
                visible=dest.with_suffix('.txt').read_text()
                if stage=='after':assert metrics(expected_heading,visible)['cer']==0,(name,visible)
                case[stage+'VisibleHeading']=dict(text=visible,metrics=metrics(expected_heading,visible),
                    reusedCompletedProbe=args.resume_visible,pngSha256=sha(path),textSha256=sha(dest.with_suffix('.txt')))
        oldapi=(before/(name+'-text-result.txt')).read_text();newapi=(after/(name+'-text-result.txt')).read_text()
        oldtruth=case.get('editedExpected',case['expected'])
        # Three old matrix TXT results followed numeric edits; independently
        # frozen unedited Office->TXT controls supply a same-input comparison.
        if name in ['white','negative','zeros']:
            oldapi=(ROOT/'qa-samples/report/iteration30-existing-before'/(name+'-text-result.txt')).read_text();oldtruth=case['expected']
        rawExact=newapi==case['expected']
        if name=='reserve':
            # Preserve the independently observed remaining separator loss as
            # a failed content check. Neither source truth nor runtime changes.
            assert not rawExact and newapi.replace('REVIEW2068','REVIEW 2068')==case['expected']
        else:assert rawExact,(name,newapi)
        assert numeric_tokens(oldapi)==numeric_tokens(oldtruth) and numeric_tokens(newapi)==numeric_tokens(case['expected'])
        oldreport=load(before/'report.json');oldtask=next(c['task'] for c in oldreport['cases'] if c['case']==name+'-word')
        assert oldtask['warnings']==byid[name+'-word']['task']['warnings']
        rows.append(dict(case=name,changes=changed,beforeNative=oldtext,afterNative=newtext,beforeApiMetrics=metrics(oldtruth,oldapi),
            afterApiMetrics=metrics(case['expected'],newapi),beforeApiText=oldapi,afterApiText=newapi,
            apiRawExact=rawExact,remainingApiIssue=None if rawExact else 'REVIEW 2068 joins as REVIEW2068; pre-existing separator/read-order boundary, not repaired by word fit',
            numericLexemesExact=True,sourceImagesMasksFontSizesPositionsExact=True,
            changedWordScaleOnly=True,pixelsOutsideChangedWordRegionsExact=True,warningsExact=True,
            beforeVisibleHeading=case['beforeVisibleHeading'],afterVisibleHeading=case['afterVisibleHeading']))
    edited=(after/'white-edited-text-result.txt').read_text();expected=next(c['editedExpected'] for c in manifest['cases'] if c['id']=='white')
    assert edited==expected and numeric_tokens(edited)==numeric_tokens(expected)
    with fitz.open(after/'white-edited-office-result.pdf') as d:
        assert 'Amount 541.80' in d[0].get_text().splitlines()
    old=parts(ROOT/'qa-samples/report/iteration27-before/dark-word-result.docx');new=parts(after/'dark-word-result.docx')
    assert {k:v for k,v in old.items() if k!='docProps/core.xml'}=={k:v for k,v in new.items() if k!='docProps/core.xml'}
    sparseTruth=load(ROOT/'qa-samples/generated/sparse-trace30/expected.json')['truth']['independent']
    for name,code in [('original','OCR_NO_NEW_TEXT'),('noise','OCR_NO_TEXT')]:
        c=byid[name+'-ofd-text'];assert c['task']['status']=='FAILED' and c['task']['errorCode']==code and not c['task']['downloadReady'] and 'artifact' not in c
    for name in ['alias-missing-amount','missing-amount']:
        c=byid[name+'-ofd-text'];assert c['task']['status']=='SUCCESS'
        assert (after/c['artifact']).read_text()==sparseTruth
    result=dict(jarSha256=report['jarSha256'],manifestSha256=report['manifestSha256'],rows=rows,
        http=22,success=20,strictFailures=2,visibleHeadingOcrCalls=10,rawApiExactCases=4,
        remainingApiSeparatorFailureCases=1,whitespaceNormalizedCerIsNotCompletenessEvidence=True,sourceMasksAndNumericFallbacksPreserved=True,
        longerEditedAmountAndFullApiExact=True,darkWordPartsExceptCoreExact=True,ofdAliasAndMissingAmountExact=True,
        originalSparseStrictFailureRetained=True,noiseNoTextFailureRetained=True,
        allWorkersExited=True,ECHILD=True,newZombies=[],resources=report['resources'],helperSha256=sha(Path(__file__)))
    (ROOT/'docs/cloud-heading30-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(verified=True,http=22,success=20,expectedFailures=2,headings=5,visibleOcr=10)))
if __name__=='__main__':main()
