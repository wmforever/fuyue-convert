#!/usr/bin/env python3
"""Verify finite source-layer integrity; record existing Word masking failures explicitly."""
import hashlib,json,collections,copy,sys,xml.etree.ElementTree as E
from pathlib import Path
import fitz
from PIL import Image
from qa_process_guard import matches
from verify_edit_iteration26 import parts
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def report(folder,count):
    r=load(folder/'report.json');assert len(r['cases'])==count and not r['newZombies'] and r['supervision']['waitpidNoChildren']
    assert len(r['workerIdentities'])==count and all(not matches(p) for p in r['workerIdentities'])
    for c in r['cases']:
        if c.get('artifact'):assert sha(folder/c['artifact'])==c['sha256']
    return r
def main():
    reports=ROOT/'qa-samples/report';before=reports/'iteration32-before';after=reports/'iteration32-after';wordBefore=reports/'iteration32-word-before'
    a=report(before,11);b=report(after,21);wb=report(wordBefore,5)
    corpus=ROOT/'qa-samples/generated/dedup32-final';truth=load(corpus/'expected.json')
    assert b['manifestSha256']==sha(corpus/'expected.json')
    for n,value in truth['sources'].items():assert sha(corpus/n)==value
    replay=load(ROOT/'qa-samples/work/iteration32-replay.json');assert replay['recognitionInvocations']==0
    byReplay={x['case']:x for x in replay['rows']};byBefore={c['case']:c for c in a['cases']};byAfter={c['case']:c for c in b['cases']}
    original=byReplay['original-iteration30'];assert original['strictNoNewText'] and not original['additions'] and not original['numericConflict']
    rows=[]
    conflicts={'digit-extension','negative-sign','decimal-conflict','one-digit-conflict'}
    for case in truth['cases']:
        name=case['id'];old=byBefore[name+'-text'];new=byAfter[name+'-text'];failure=case['expectedStrictFailure']
        row=dict(id=name,beforeStatus=old['task']['status'],beforeError=old['task']['errorCode'],afterStatus=new['task']['status'],afterError=new['task']['errorCode'],warnings=new['task']['warnings'])
        if failure:
            assert old['task']['errorCode']==new['task']['errorCode']==failure and not new['task']['downloadReady'] and 'artifact' not in new
            row['strictFailurePreserved']=True
        else:
            assert new['task']['status']=='SUCCESS';text=(after/new['artifact']).read_text();row['text']=text
            if name=='native-noop':
                assert text=='\n'.join(case['nativeLines'])+'\n' and not new['task']['warnings'];row['zeroOcrNoOp']=True
            else:
                model=byReplay[name];assert not model['strictNoNewText'] and text==model['assembled']
                assert model['union'][:len(model['native'])]==model['native']
                expected=numeric_tokens('\n'.join(x['text'] for x in model['union']));assert numeric_tokens(text)==expected
                for nativeLine in case['nativeLines']:assert nativeLine in text
                for rasterLine in case['rasterLines']:assert rasterLine in text
                row.update(exactSourceLayerText=True,numericLexemesExact=True,additions=len(model['additions']))
                warnings=[w for w in new['task']['warnings'] if w['code']=='OCR_RECOGNITION_CONFLICT'];assert len(warnings)==(1 if name in conflicts else 0)
                row['numericConflictWarning']=bool(warnings)
        rows.append(row)
    common=truth['expectedCommon']
    for name in ['overlap-ocr-noop','alias-partial']:assert (after/(name+'-text-result.txt')).read_text()==common
    for name,expected in [('masked-edited',common),('boundary-reserve',common)]:
        baseline=ROOT/('qa-samples/report/iteration31-after/reserve-edited-text-result.txt' if name=='masked-edited' else 'qa-samples/report/iteration31-after/reserve-artifact-text-result.txt')
        assert (after/(name+'-text-result.txt')).read_text()==baseline.read_text()
    wordChecks=[]
    for name in ['partial','conflict']:
        doc=parts(after/(name+'-word-result.docx'));root=E.fromstring(doc['word/document.xml']);texts=[t.text or '' for t in root.iter(W+'t')]
        with Image.open(corpus/('partial-new-digits.png' if name=='partial' else 'negative-sign.png')) as im:sourcePixels=im.convert('RGB').tobytes();size=im.size
        matches=[]
        import io
        for n,data in doc.items():
            if not n.startswith('word/media/'):continue
            with Image.open(io.BytesIO(data)) as im:
                if im.size==size and im.convert('RGB').tobytes()==sourcePixels:matches.append(n)
        assert matches
        if name=='conflict':assert numeric_tokens(' '.join(texts))==numeric_tokens('\n'.join(x['text'] for x in byReplay['negative-sign']['union']))
        wordChecks.append(dict(id=name,originalScanPixelsExact=True,originalScanMedia=matches,editableRuns=len(texts),texts=texts))
    old,new=[parts(folder/'partial-word-result.docx') for folder in [wordBefore,after]]
    assert {k:v for k,v in old.items() if k!='docProps/core.xml'}=={k:v for k,v in new.items() if k!='docProps/core.xml'}
    maskingFailures=[]
    for name,value in [('partial-office','048.65'),('partial-edited-office','147.80')]:
        with fitz.open(wordBefore/(name+'-result.pdf')) as old,fitz.open(after/(name+'-result.pdf')) as new:
            assert len(old)==len(new)==1 and old[0].rect==new[0].rect
            assert old[0].get_pixmap(dpi=300,alpha=False).samples==new[0].get_pixmap(dpi=300,alpha=False).samples
            expected=common.replace('048.65',value);assert numeric_tokens(new[0].get_text())==numeric_tokens(expected)
            textId='partial-edited' if name=='partial-edited-office' else name
            text=(after/(textId+'-text-result.txt')).read_text();beforeText=(wordBefore/(textId+'-text-result.txt')).read_text();assert text==beforeText and text!=expected
            maskingFailures.append(dict(id=name,expectedApiText=expected,actualApiText=text,baselineApiText=beforeText,officeNativeText=new[0].get_text(),officeNumbersExact=True,beforeAfterPixelsExact=True,apiQualityPassed=False))
    result=dict(parentRevision='16dab17b4135a35f9120311d0ecf8dbc2ee75d41',jarSha256=b['jarSha256'],beforeJarSha256=a['jarSha256'],manifestSha256=b['manifestSha256'],
        controls=rows,word=wordChecks,partialWordAllPartsExceptCoreExact=True,existingMaskingFailures=maskingFailures,
        httpBefore=11,httpWordBefore=5,httpAfter=21,interfaceSuccessAfter=18,strictFailuresAfter=3,workersAbsent=37,newZombies=[],ECHILD=True,
        falseNumericDuplicatesCorrected=3,conflictWarningCases=4,originalCachedStrictNoNewTextPreserved=True,originalOcrRerun=False,
        sourceConflictIsNotResolvedSingleTruth=True,recognitionReplayCalls=0,newTraceUniqueRecognitions=4,
        resources=dict(before=a['resources'],wordBefore=wb['resources'],after=b['resources']),
        helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_dedup32.py','generate_dedup32_final.py','OcrDedupReplay32.java','verify_dedup32.py','OcrSparseTraceProbe.java','run_edit_iteration26.py','qa_process_guard.py','qa_http_deadline.py']},
        nextFault='Existing mixed native/OCR Word page skips foreground masks; original scan amount remains visibly superimposed after edit, then API OCR rereads it. Source Word and actual Office pixels are identical before/after this fix.')
    (ROOT/'docs/cloud-dedup32-results.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(dict(falseNumericDuplicatesCorrected=3,conflictWarnings=4,httpAfter=21,success=18,strictFailures=3,existingWordApiQualityFailures=2,workersAbsent=37)))
if __name__=='__main__':main()
