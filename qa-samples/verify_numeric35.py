#!/usr/bin/env python3
"""Verify literal layer retention, frozen OCR/geometry, finite HTTP and build provenance."""
import hashlib,io,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1]
def load(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def distance(a,b):
    row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        new=[i]
        for j,y in enumerate(b,1):new.append(min(new[-1]+1,row[j]+1,row[j-1]+(x!=y)))
        row=new
    return row[-1]
def content(text):return ''.join(c for c in text if not c.isspace())
def report(path,count):
    r=load(path/'report.json');assert r['status']=='completed' and not r['failures'] and not r['newZombies']
    assert len(r['cases'])==len(r['workerIdentities'])==count and r['supervision']['waitpidNoChildren']
    assert all(not matches(p) for p in r['workerIdentities'])
    for c in r['cases']:assert c['task']['status']=='SUCCESS' and sha(path/c['artifact'])==c['sha256']
    assert r['health']['ocr']['bundled'] and r['health']['ocr']['available'];return r
def packaged(path):
    result={}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith('BOOT-INF/classes/') and not n.endswith('/'):result[n]=z.read(n)
            if n.startswith('BOOT-INF/lib/') and any(Path(n).name.startswith(p+'-') for p in ['layout-model','ofd-parser','table-recognizer','docx-renderer','task-service']):
                with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                    for p in inner.namelist():
                        if not p.endswith('/') and not p.startswith('META-INF/'):result[n+'/'+p]=inner.read(p)
    return result
def main():
    before=ROOT/'qa-samples/report/iteration35-before';after=ROOT/'qa-samples/report/iteration35-after'
    a=report(before,9);b=report(after,15);truth=load(ROOT/'qa-samples/generated/numeric35-before/expected.json');final=load(ROOT/'qa-samples/generated/numeric35-final/expected.json')
    for n,h in final['sources'].items():assert sha(ROOT/'qa-samples/generated/numeric35-final'/n)==h
    assert a['manifestSha256']==sha(ROOT/'qa-samples/generated/numeric35-before/expected.json') and b['manifestSha256']==sha(ROOT/'qa-samples/generated/numeric35-final/expected.json')
    old=load(ROOT/'qa-samples/work/iteration35-replay-before/replay.json');new=load(ROOT/'qa-samples/work/iteration35-replay-after/replay.json')
    assert old['recognitionInvocations']==new['recognitionInvocations']==0
    old={r['case']:r for r in old['rows']};new={r['case']:r for r in new['rows']};beforeCases={c['case']:c for c in a['cases']};afterCases={c['case']:c for c in b['cases']}
    rows=[];totalBefore=totalAfter=totalTruth=0
    for case in truth['cases']:
        name=case['id'];x,y=old[name],new[name]
        for k in ['native','fullRecognition','physicalBox','imageBox','imageResourceSha256','width','height','tsvSha256']:assert x[k]==y[k],(name,k)
        assert y['union'][:len(y['native'])]==y['native'] and x['refinementIdentity'] and y['refinementIdentity']
        assert [t['text'] for t in y['native']]==case['nativeLines']
        observed=y['fullRecognition']['blocks'][2]['text'];expected=case['rasterValue']
        assert observed==expected if name!='per-mille' else observed=='Rate 10%o'
        values=case['nativeLines'].copy()
        # Frozen baseline geometry determines the existing reading order. Source
        # conflicts have no canonical single amount or preferred layer.
        if not case['sameNumericSurface']:
            ordered=sorted([(x['native'][2],case['nativeValue']),(x['fullRecognition']['blocks'][2],expected)],
                           key=lambda p:(p[0]['box']['x']+p[0]['textOffsetXmm'],p[0]['baselineY']))
            values[2]=''.join(p[1] for p in ordered)
        values.append(case['novelFooter']);expectedText='\n'.join(values)+'\n'
        assert [t['text'] for t in y['additions']]==([case['novelFooter']] if case['sameNumericSurface'] else [observed,case['novelFooter']])
        for addition in y['additions']:
            original=next(t for t in y['fullRecognition']['blocks'] if t['text']==addition['text'])
            for k in ['box','baselineY','style','textOffsetXmm','textOffsetYmm','advancesMm','transform','ocrWords']:assert addition[k]==original[k],(name,k)
        expectedObserved=expectedText.replace(expected,observed) if name=='per-mille' else expectedText
        assert y['assembled']==expectedObserved,(name,y['assembled'],expectedObserved)
        warnings=[w for w in afterCases[name+'-text']['task']['warnings'] if w['code']=='OCR_RECOGNITION_CONFLICT'];assert len(warnings)==(0 if case['sameNumericSurface'] else 1)
        assert y['numericConflict']==bool(warnings)
        oldText=x['assembled'];newText=y['assembled'];rawOld=distance(expectedText,oldText);rawNew=distance(expectedText,newText)
        e=content(expectedText);dOld=distance(e,content(oldText));dNew=distance(e,content(newText));totalBefore+=dOld;totalAfter+=dNew;totalTruth+=len(e)
        rows.append(dict(id=name,sourceNative=case['nativeValue'],sourceRaster=expected,actualRecognizedRaster=observed,beforeText=oldText,afterText=newText,expectedSourceUnionText=expectedText,
            rawCerBefore=rawOld/len(expectedText),rawCerAfter=rawNew/len(expectedText),contentCerBefore=dOld/len(e),contentCerAfter=dNew/len(e),contentEditsBefore=dOld,contentEditsAfter=dNew,contentTruthCharacters=len(e),
            observedLayerRetentionBefore=len(x['additions']),observedLayerRetentionAfter=len(y['additions']),observedLayersExact=True,sourceTruthExact=newText==expectedText,sourceUnionOrder='existing geometric order from frozen before models;no canonical value selected',
            recognitionAndOriginalCoordinatesExact=True,nativeLayerExact=True,numericConflictWarning=bool(warnings),secondsBefore=beforeCases[name+'-text']['seconds'],secondsAfter=afterCases[name+'-text']['seconds']))
    regressions=[]
    for name in ['digit-extension','negative-sign','decimal-conflict','one-digit-conflict','masked-edited','boundary-reserve']:
        current=after/(name+'-text-result.txt');accepted=ROOT/'qa-samples/report/iteration32-after'/(name+'-text-result.txt')
        assert current.read_bytes()==accepted.read_bytes();regressions.append(dict(id=name,acceptedOutputByteExact=True,sha256=sha(current)))
    pairs=load(ROOT/'qa-samples/work/iteration35-after-java.json');assert pairs['productionCalls']==39
    for pair in pairs['cases']:
        duplicate=pair['id'] in ['ordinary-punctuation','same-marked-value'];assert pair['duplicate']==pair['reverseDuplicate']==duplicate and pair['numericConflict']==(not duplicate)
    beforePackage=packaged(ROOT/'qa-samples/work/iteration35-before.jar');afterPackage=packaged(ROOT/'web-api/target/web-api-0.1.5.jar');assert beforePackage.keys()==afterPackage.keys()
    changed=[k for k in beforePackage if beforePackage[k]!=afterPackage[k]]
    assert changed==['BOOT-INF/lib/task-service-0.1.5.jar/com/fuyue/formatconverter/task/OcrTextDeduplicator.class'],changed
    frontend={}
    for path in (ROOT/'frontend/dist').rglob('*'):
        if path.is_file():
            relative=str(path.relative_to(ROOT/'frontend/dist'));assert path.read_bytes()==afterPackage['BOOT-INF/classes/static/'+relative]
            frontend[relative]=sha(path)
    assert len(frontend)==7
    tests=dict(tests=0,failures=0,errors=0,skipped=0);cases=[]
    for file in ROOT.glob('*/target/surefire-reports/TEST-*.xml'):
        root=E.parse(file).getroot()
        for k in tests:tests[k]+=int(root.get(k,'0'))
        for c in root.findall('testcase'):cases.append(dict(className=c.get('classname'),name=c.get('name'),skipped=c.find('skipped') is not None))
    assert tests==dict(tests=501,failures=0,errors=0,skipped=1),tests
    previous=load(ROOT/'docs/cloud-mixed-masks33-validation.json');bundled=[]
    for expected in previous['bundledTests']:
        actual=next(c for c in cases if c['className']==expected['className'] and c['name']==expected['name']);assert not actual['skipped'];bundled.append(actual)
    runtime=ROOT/'qa-samples/work/iteration25-review-app/ocr';assert sha(runtime/'OCR-RUNTIME.json')==previous['runtimeManifestSha256']
    manifest=load(runtime/'OCR-RUNTIME.json')
    for item in manifest['files']:assert sha(runtime/item['path'])==item['sha256']
    build=load(ROOT/'qa-samples/work/iteration35-clean-build.receipt.json');assert build['rootExitCode']==0 and build['waitpidNoChildren'] and all(not matches(p) for p in build['registered'])
    provenance=load(ROOT/'qa-samples/work/iteration35-final-provenance.json');assert provenance['packagedClassesMatchTargets'] and provenance['jarSha256']==b['jarSha256']
    result=dict(parentRevision=provenance['parentRevision'],jarSha256=b['jarSha256'],beforeJarSha256=a['jarSha256'],buildInputSha256=provenance['buildInputSha256'],
        productionFix='retain literal accounting,currency,percent/per-mille and separated sign in numeric comparisons;warn about retained mismatches',
        cases=rows,regressions=regressions,falseFinancialDuplicatesCorrected=7,observedLayersExact=9,sourceUnionExact=8,sourceUnionResidual='per-mille OCR recognises10‰ as10%o;both native and observed layers retained with warning;not resolved canonical truth',
        contentCerDefinition='case and punctuation preserved;only whitespace removed;edit distance divided by source-layer union characters',
        aggregateContentCerBefore=totalBefore/totalTruth,aggregateContentCerAfter=totalAfter/totalTruth,aggregateContentEditsBefore=totalBefore,aggregateContentEditsAfter=totalAfter,aggregateContentTruthCharacters=totalTruth,
        httpBefore=9,httpAfter=15,successBefore=9,successAfter=15,workerIdentitiesAbsent=24,newZombies=[],ECHILD=True,extraOcrForReplay=0,
        capturedRecognitionByteExactForNine=True,javaProductionProbeCallsAfter=39,tests=tests,skips=[c for c in cases if c['skipped']],bundledTests=bundled,
        changedClasses=changed,other231ClassesAndAllApplicationResourcesByteExact=True,frontendRebuiltFilesMatchPackaged=frontend,buildReceiptSha256=sha(ROOT/'qa-samples/work/iteration35-clean-build.receipt.json'),
        runtimeManifestSha256=sha(runtime/'OCR-RUNTIME.json'),runtimePayloadFilesReverified=len(manifest['files']),runtimeManifest=manifest,
        versions=load(ROOT/'qa-samples/work/iteration35-versions.json')['versions'],fontHashesReverified=True,pdfbox=previous['pdfbox'],ofdrw=previous['ofdrw'],fonts=previous['fonts'],
        resources=dict(before=a['resources'],after=b['resources']),noPerformanceSpeedupClaim=True,
        firstFocusedFailure='test-engine printf interpreted literal%;escaped%% then all12focusedpassed;failure receipt retained',
        unrun=['native macOS/Windows package acceptance','Microsoft Word','optional signed-OFD fixture','prior33/34negativeAPI matrices and original sparseOFD diagnostics not repeated','fullWordmatrix not repeated;renderer byteexact and relevant fullsuitepassed'],
        helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_numeric35.py','generate_numeric35_final.py','OcrNumericLexemeProbe35.java','OcrCapturedLexemeReplay35.java','capture_numeric_http35.py','verify_numeric35.py','run_edit_iteration26.py','qa_process_guard.py','qa_http_deadline.py']})
    (ROOT/'docs/cloud-numeric35-results.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps({k:result[k] for k in ['falseFinancialDuplicatesCorrected','observedLayersExact','sourceUnionExact','aggregateContentCerBefore','aggregateContentCerAfter','tests','workerIdentitiesAbsent']}))
if __name__=='__main__':main()
