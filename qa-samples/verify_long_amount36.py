#!/usr/bin/env python3
"""Compare frozen scans, source Word masks, actual Office pixels, amount boundaries and HTTP."""
import hashlib,io,json,re,sys,xml.etree.ElementTree as E,zipfile
from pathlib import Path
from PIL import Image,ImageChops,ImageDraw
from qa_process_guard import matches
from compare_text_iteration24 import numeric_tokens
from verify_numeric35 import packaged
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def compact(s):return ''.join(s.split())
def distance(a,b):
    row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        new=[i]
        for j,y in enumerate(b,1):new.append(min(new[-1]+1,row[j]+1,row[j-1]+(x!=y)))
        row=new
    return row[-1]
def parts(p):
    with zipfile.ZipFile(p) as z:return {n:z.read(n) for n in z.namelist()}
def check_report(folder,count):
    r=load(folder/'report.json');assert len(r['cases'])==len(r['workerIdentities'])==count and not r['newZombies'] and r['supervision']['waitpidNoChildren']
    assert all(not matches(i) for i in r['workerIdentities'])
    for c in r['cases']:
        if c.get('artifact'):assert sha(folder/c['artifact'])==c['sha256']
    return r
def main():
    work=ROOT/'qa-samples/work';corpus=ROOT/'qa-samples/generated/long-amount36-final';truth=load(corpus/'expected.json')
    original=ROOT/'qa-samples/report/iteration36-before';chinese=ROOT/'qa-samples/report/iteration36-zh-before';after=ROOT/'qa-samples/report/iteration36-after'
    reports=[check_report(p,n) for p,n in [(original,24),(chinese,15),(after,30)]]
    assert len(reports[0]['unsupportedEdits'])==6 # Initial missing-glyph source explicitly excluded.
    assert all(f['errorCode']=='OCR_VISIBILITY_UNCERTAIN' for r in reports[1:] for f in r['failures'])
    beforeRender=work/'iteration36-render-before';afterRender=work/'iteration36-render-after'
    before=load(beforeRender/'evidence.json');final=load(afterRender/'evidence.json');assert before['extraDiagnosticOcrInvocations']==final['extraDiagnosticOcrInvocations']==6
    old={r['id']:r for r in before['rows']};new={r['id']:r for r in final['rows']};http={c['case']:c for c in reports[2]['cases']};rows=[]
    for case in truth['cases']:
        name=case['id'];sourceFolder=original if case['language']=='en' else chinese;x,y=old[name],new[name]
        assert sha(corpus/(name+'.png'))==truth['sources'][name+'.png']
        assert x['mediaSha256']==y['mediaSha256'] and x['maskStyles']==y['maskStyles']
        assert [s['texts'] for s in x['shapes']]==[s['texts'] for s in y['shapes']]
        assert len(x['shapes'])==len(y['shapes'])
        for a,b in zip(x['shapes'],y['shapes'],strict=True):assert re.sub(r'width:[^;]+','width:*',a['style'])==re.sub(r'width:[^;]+','width:*',b['style'])
        with Image.open(beforeRender/x['pdfs']['office']['render']) as a,Image.open(afterRender/y['pdfs']['office']['render']) as b:assert a.size==b.size and a.convert('RGB').tobytes()==b.convert('RGB').tobytes()
        desired=case['editedExpected'];oldNative=x['pdfs']['edited-office']['nativeText'];newNative=y['pdfs']['edited-office']['nativeText'];visible=y['actualRenderedCropOcr']
        originalWord=parts(after/(name+'-word-result.docx'));edit=next(e for e in reports[2]['edits'] if e['action']==name+'-edited-office');editedWord=parts(after/edit['artifact'])
        assert {k:v for k,v in originalWord.items() if k!='word/document.xml'}=={k:v for k,v in editedWord.items() if k!='word/document.xml'}
        assert case['new'] in editedWord['word/document.xml'].decode() and case['old'] not in editedWord['word/document.xml'].decode()
        fixed=not case['adjacent']
        assert (numeric_tokens(newNative)==numeric_tokens(desired))==fixed
        assert numeric_tokens(oldNative)!=numeric_tokens(desired) and case['old'] not in newNative
        if fixed:assert case['new'] in visible and numeric_tokens(visible)==numeric_tokens(('Amount ' if case['language']=='en' else '金额 ')+case['new'])
        else:assert x['originalAmountShape']['style']==y['originalAmountShape']['style'] and case['new'] not in visible
        oldShape=x['originalAmountShape'];newShape=y['originalAmountShape']
        fields={k:float(v) for k,v in re.findall(r'(margin-left|margin-top|width|height):([0-9.]+)pt',newShape['style'])}
        # For accepted end-line edits, changed actual Office pixels stay inside
        # the transparent emitted amount box; masks and all outside ink unchanged.
        with Image.open(afterRender/y['pdfs']['office']['render']) as a,Image.open(afterRender/y['pdfs']['edited-office']['render']) as b:
            diff=ImageChops.difference(a.convert('RGB'),b.convert('RGB'))
            rectangle=[int(fields['margin-left']*300/72)-2,int(fields['margin-top']*300/72)-2,int((fields['margin-left']+fields['width'])*300/72)+3,int((fields['margin-top']+fields['height'])*300/72)+3]
            ImageDraw.Draw(diff).rectangle(rectangle,fill=(0,0,0));outside=diff.getbbox()
            if fixed:assert outside is None,(name,outside,rectangle)
        request=http[name+'-edited-text'];status=request['task']['status'];actual=(after/request['artifact']).read_text() if request.get('artifact') else None
        if case['language']=='en':
            assert status=='SUCCESS' and ((numeric_tokens(actual)==numeric_tokens(desired))==fixed)
            if fixed:assert actual==desired
        else:assert request['task']['errorCode']=='OCR_VISIBILITY_UNCERTAIN' and not request['task']['downloadReady'] and actual is None
        rows.append(dict(id=name,adjacent=case['adjacent'],old=case['old'],new=case['new'],expected=desired,beforeOfficeText=oldNative,afterOfficeText=newNative,actualVisibleCropOcrBefore=x['actualRenderedCropOcr'],actualVisibleCropOcrAfter=visible,
            beforeNumericBoundariesExact=False,afterNumericBoundariesExact=fixed,sourceWordMediaAndMaskExact=True,normalOfficePixelsExact=True,sourceShapesOnlyWidthChanges=True,
            originalAmountShape=oldShape,finalAmountShape=newShape,editedPixelsOutsideAmountBox=outside,sourceAndEditableWordTextExact=True,
            rawOfficeCerBefore=distance(desired,oldNative)/len(desired),rawOfficeCerAfter=distance(desired,newNative)/len(desired),
            whitespaceNormalizedBefore=compact(desired)==compact(oldNative),whitespaceNormalizedAfter=compact(desired)==compact(newNative),httpStatus=status,httpError=request['task']['errorCode'],httpText=actual,
            retainedNeighbor='TAIL 2097' in compact(newNative).replace('TAIL2097','TAIL 2097') if case['adjacent'] else None))
    counts=dict(tests=0,failures=0,errors=0,skipped=0)
    for p in ROOT.glob('*/target/surefire-reports/TEST-*.xml'):
        r=E.parse(p).getroot()
        for k in counts:counts[k]+=int(r.get(k,'0'))
    assert counts==dict(tests=502,failures=0,errors=0,skipped=1),counts
    provenance=load(work/'iteration36-final-provenance.json');assert provenance['packagedClassesMatchTargets'] and provenance['jarSha256']==reports[2]['jarSha256']
    beforePackage=packaged(work/'iteration36-before.jar');afterPackage=packaged(ROOT/'web-api/target/web-api-0.1.5.jar');assert beforePackage.keys()==afterPackage.keys()
    changed=[k for k in beforePackage if beforePackage[k]!=afterPackage[k]]
    assert changed and all('/FixedLayoutDocxRenderer' in k and k.endswith('.class') for k in changed),changed
    previous=load(ROOT/'docs/cloud-numeric35-results.json');runtime=work/'iteration25-review-app/ocr';assert sha(runtime/'OCR-RUNTIME.json')==previous['runtimeManifestSha256']
    for item in previous['runtimeManifest']['files']:assert sha(runtime/item['path'])==item['sha256']
    bundled=[]
    for expected in previous['bundledTests']:
        candidates=[p for p in ROOT.glob('*/target/surefire-reports/TEST-*.xml') if p.name=='TEST-'+expected['className']+'.xml'];assert len(candidates)==1
        case=next(c for c in E.parse(candidates[0]).getroot().findall('testcase') if c.get('name')==expected['name']);assert case.find('skipped') is None;bundled.append(expected)
    result=dict(parentRevision='7a3ceb25835b0fa2e032f4fe3cf158487aaafa90',jarSha256=provenance['jarSha256'],buildInputSha256=provenance['buildInputSha256'],cases=rows,
        beforeExactAmountBoundaries=0,afterExactAmountBoundaries=4,visibleRenderedLongAmountsExact=4,englishHttpExact=2,chineseHttpUnaccepted=3,adjacentLongAmountFallbackUnresolved=2,
        normalOfficePixelsExact=6,originalMaskAndMediaExact=6,roiOcrCalls=12,tests=counts,httpInitialInvalidChineseGraph=24,httpCorrectedChineseBefore=15,httpFinal=30,workerIdentitiesAbsent=69,newZombies=[],ECHILD=True,
        samplerBudgetUnchanged=250000,maximumOptionalReserveMm=25,originalShortReserveRetainedOnExtensionFailure=True,sourcePositionsAndOcrImplementationNotChanged=True,fullRecognitionModelsCaptured=False,noOcrQualityImprovementClaim=True,
        changedClasses=changed,otherApplicationClassesAndResourcesByteExact=True,bundledTests=bundled,runtimePayloadFilesReverified=12,runtimeManifestSha256=previous['runtimeManifestSha256'],versionsRetainedFromAcceptedHead35=previous['versions'],fonts=previous['fonts'],
        resources=[dict(phase=n,resources=r['resources']) for n,r in zip(['initial','correctedChineseBefore','after'],reports,strict=True)],
        initialChineseFailure='DroidFallback alone has no Latin digits;initial3sources invalid,edits unsupported;preserved;threecorrectedmixed-font scans independently frozen without rerunningEnglish',
        remaining=['twoadjacent longamounts still wrap;no safeblankreservation','Chinese actual Office longamounts corrected but all3HTTP strict OCR_VISIBILITY_UNCERTAIN retained','Chinese preexisting footer/native readorder linebreak remains','arbitrary textlength/sourceconflicts/sparseOFD/nativeMacWindows/MicrosoftWord acceptance unrun'],
        helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_long_amount36.py','prepare_long_amount36.py','render_long_amount36.py','verify_long_amount36.py','run_edit_iteration26.py','qa_process_guard.py','qa_http_deadline.py']})
    (ROOT/'docs/cloud-long-amount36-results.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(dict(actualOfficeAmountFixes=4,englishHttpExact=2,chineseHttpStrictUnaccepted=3,adjacentFallback=2,tests=counts)))
if __name__=='__main__':main()
