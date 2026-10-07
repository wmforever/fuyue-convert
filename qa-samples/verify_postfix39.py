#!/usr/bin/env python3
"""Validate downloaded literal layer retention, separator controls and committed-build evidence."""
import json,re,xml.etree.ElementTree as E
from pathlib import Path
from verify_numeric35 import load,sha,report,packaged,distance,content
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'qa-samples/work'
def main():
    corpus=ROOT/'qa-samples/generated/postfix39-before';final=ROOT/'qa-samples/generated/postfix39-final';truth=load(corpus/'expected.json');finalTruth=load(final/'expected.json')
    assert truth['freezeBeforeProductionChange'] and len(truth['cases'])==12 and len(truth['javaPairs'])==30 and truth['generatorSha256']==sha(ROOT/'qa-samples/generate_postfix39.py')
    for name,digest in finalTruth['sources'].items():assert sha(final/name)==digest
    before=WORK/'iteration39-before-http';after=WORK/'iteration39-after-http';a=report(before,12);b=report(after,27)
    oldReplay=load(WORK/'iteration39-before-replay/replay.json');newReplay=load(WORK/'iteration39-after-replay/replay.json');assert oldReplay['recognitionInvocations']==newReplay['recognitionInvocations']==0
    old={r['case']:r for r in oldReplay['rows']};new={r['case']:r for r in newReplay['rows']};beforeCases={r['case']:r for r in a['cases']};afterCases={r['case']:r for r in b['cases']}
    rows=[];fixed=0;beforeErrors=afterErrors=totalChars=0
    for case in truth['cases']:
        name=case['id'];x,y=old[name],new[name]
        for k in ['native','fullRecognition','physicalBox','imageBox','imageResourceSha256','width','height','tsvSha256']:assert x[k]==y[k],(name,k)
        assert x['refinementIdentity'] and y['refinementIdentity'] and [r['text'] for r in y['native']]==case['nativeLines']
        observed=y['fullRecognition']['blocks'][2]['text'];assert observed==case['rasterValue'],(name,observed)
        expectedAdditions=([observed] if case['expectedConflict'] else [])+[case['novelFooter']];assert [r['text'] for r in y['additions']]==expectedAdditions
        for addition in y['additions']:
            raw=next(r for r in y['fullRecognition']['blocks'] if r['text']==addition['text'])
            for k in ['box','baselineY','style','textOffsetXmm','textOffsetYmm','advancesMm','transform','ocrWords']:assert addition[k]==raw[k]
        expected=case['nativeLines'].copy()
        if case['expectedConflict']:
            ordered=sorted([(x['native'][2],case['nativeValue']),(x['fullRecognition']['blocks'][2],case['rasterValue'])],key=lambda p:(p[0]['box']['x']+p[0]['textOffsetXmm'],p[0]['baselineY']))
            expected[2]=''.join(p[1] for p in ordered)
        expected.append(case['novelFooter']);expected='\n'.join(expected)+'\n';assert y['assembled']==expected
        beforeWarnings=[w for w in beforeCases[name+'-text']['task']['warnings'] if w['code']=='OCR_RECOGNITION_CONFLICT'];afterWarnings=[w for w in afterCases[name+'-text']['task']['warnings'] if w['code']=='OCR_RECOGNITION_CONFLICT'];assert bool(afterWarnings)==case['expectedConflict']==y['numericConflict']
        corrected=case['expectedConflict'] and not beforeWarnings;fixed+=corrected
        if not corrected:assert (before/beforeCases[name+'-text']['artifact']).read_bytes()==(after/afterCases[name+'-text']['artifact']).read_bytes()
        chars=len(content(expected));oldErrors=distance(content(expected),content(x['assembled']));newErrors=distance(content(expected),content(y['assembled']));beforeErrors+=oldErrors;afterErrors+=newErrors;totalChars+=chars
        rows.append(dict(id=name,nativeValue=case['nativeValue'],rasterValue=case['rasterValue'],beforeText=x['assembled'],afterText=y['assembled'],expectedLiteralSourceUnion=expected,sourceConflictHasNoCanonicalValue=case['expectedConflict'],falseDuplicateCorrected=corrected,beforeConflictWarning=bool(beforeWarnings),afterConflictWarning=bool(afterWarnings),recognitionAndOriginalCoordinatesExact=True,sourceImagesAndNativeLayerExact=True,contentCharacters=chars,charErrorsBefore=oldErrors,charErrorsAfter=newErrors,CERBefore=oldErrors/chars,CERAfter=newErrors/chars,secondsBefore=beforeCases[name+'-text']['seconds'],secondsAfter=afterCases[name+'-text']['seconds']))
    assert fixed==7 and afterErrors==0
    regressions=[]
    for action in finalTruth['oldRegressions']:
        current=after/afterCases[action]['artifact'];accepted=ROOT/'qa-samples/report/iteration35-after'/(action+'-result.txt');assert current.read_bytes()==accepted.read_bytes();regressions.append(dict(action=action,acceptedOutputByteExact=True,sha256=sha(current)))
    assert len(regressions)==15
    beforeJava=load(WORK/'iteration39-before-java.json');afterJava=load(WORK/'iteration39-after-java.json');assert beforeJava['productionMatcherCalls']==afterJava['productionMatcherCalls']==90
    for actual,expected in zip(afterJava['rows'],truth['javaPairs'],strict=True):
        assert actual['id']==expected['id'] and actual['duplicate']==actual['reverseDuplicate']==expected['expectedDuplicate'] and actual['numericConflict']==expected['expectedConflict']
        if 'expectedNativeTokens' in expected:assert actual['nativeTokens']==expected['expectedNativeTokens']
    oldPairs=load(WORK/'iteration39-old-java.json');assert oldPairs['productionCalls']==39
    for r in oldPairs['cases']:assert r['duplicate']==r['reverseDuplicate']==(r['id'] in ['ordinary-punctuation','same-marked-value']) and r['numericConflict']==(r['id'] not in ['ordinary-punctuation','same-marked-value'])
    beforePackage=packaged(WORK/'iteration39-before.jar');afterPackage=packaged(ROOT/'web-api/target/web-api-0.1.5.jar');assert beforePackage.keys()==afterPackage.keys()
    changed=[p for p in beforePackage if beforePackage[p]!=afterPackage[p]];assert changed==['BOOT-INF/lib/task-service-0.1.5.jar/com/fuyue/formatconverter/task/OcrTextDeduplicator.class']
    counts=dict(tests=0,failures=0,errors=0,skipped=0);tests=[]
    for p in ROOT.glob('*/target/surefire-reports/TEST-*.xml'):
        root=E.parse(p).getroot()
        for k in counts:counts[k]+=int(root.get(k,'0'))
        tests.extend(dict(className=c.get('classname'),name=c.get('name'),skipped=c.find('skipped') is not None) for c in root.findall('testcase'))
    assert counts==dict(tests=505,failures=0,errors=0,skipped=1),counts
    prior=load(ROOT/'docs/cloud-long-amount36-results.json');bundled=[]
    for expected in prior['bundledTests']:
        actual=next(c for c in tests if c['className']==expected['className'] and c['name']==expected['name']);assert not actual['skipped'];bundled.append(actual)
    assert len(bundled)==10
    runtime=WORK/'iteration25-review-app/ocr';manifest=load(runtime/'OCR-RUNTIME.json')
    for f in manifest['files']:assert sha(runtime/f['path'])==f['sha256']
    provenance=load(WORK/'iteration39-provenance.json');assert provenance['packagedClassesMatchTargets'] and provenance['applicationClassCount']==232 and provenance['jarSha256']==b['jarSha256']
    build=load(WORK/'iteration39-build-receipt.json');assert build['rootExitCode']==0 and build['waitpidNoChildren'] and all(not matches(i) for i in build['registered'])
    focused=(WORK/'iteration39-focused.log').read_text();assert 'Tests run: 15, Failures: 0, Errors: 0, Skipped: 0' in focused and 'BUILD SUCCESS' in focused
    result=dict(parentRevision=truth['parentRevision'],status='validated terminal-sign protection;preexisting omission',productionFix='preserve five existing sign codepoints only at terminal numeric field suffix;keep separators and original source layers;warn without choosing numeric truth',scope='terminal fields only:after sign only whitespace or existing currency/accounting/unit closing markers;no sign borrowed from another logical line',falseDuplicatesCorrected=7,newOFDControls=12,newJavaPairs=30,javaProductionCallsAfter=90,oldJavaProductionCallsAfter=39,httpBefore=12,httpAfter=27,httpSuccessBefore=12,httpSuccessAfter=27,totalWorkersAbsent=39,newZombies=[],ECHILD=True,cases=rows,oldRegressions=regressions,old15DownloadsByteExact=True,beforeJava=beforeJava,afterJava=afterJava,all12RecognitionModelsTsvConfidenceSourceCoordinatesByteExact=True,replayExtraOcrInvocations=0,aggregateTruthCharacters=totalChars,aggregateCharErrorsBefore=beforeErrors,aggregateCharErrorsAfter=afterErrors,aggregateCERBefore=beforeErrors/totalChars,aggregateCERAfter=0,cerDefinition='literal native/OCR source union using existing geometry;case/punctuation/signs retained;only whitespace removed;not canonical financial truth',jarSha256=provenance['jarSha256'],beforeJarSha256=a['jarSha256'],buildInputSha256=provenance['buildInputSha256'],applicationClassCount=232,changedClasses=changed,other231ClassesAndAllApplicationResourcesByteExact=True,rendererAndOcrEngineByteExact=True,focusedTests=15,fullSuite=counts,skips=[c for c in tests if c['skipped']],bundledTests=bundled,runtimeManifestSha256=sha(runtime/'OCR-RUNTIME.json'),runtimePayloadFilesReverified=len(manifest['files']),runtimeManifest=manifest,versions=load(ROOT/'docs/cloud-numeric35-results.json')['versions'],fonts=truth['sourceFontPath'],sourceFontSha256=truth['sourceFontSha256'],sourceFontVersion='LiberationSans2.1.5 hash same as accepted35/36',pillowVersion=truth['pillowVersion'],resources=dict(before=a['resources'],after=b['resources']),noRecognitionAccuracyOrSpeedupClaim=True,noTimeoutRetryPixelChanges=True,noScalingVisibilityExperimentRerun=True,newWordOfficeMatrixUnrun=True,unrun=['nonterminal post-sign followed by additional inline text remains deliberately ambiguous','newWordOffice visual/edit matrix;renderer byteexact and fullsuite covers prior guards','nativeMacWindows/MicrosoftWord/optional signedOFDfixture','scaling38 andvisibility37 experiments ended;not repeated'],remaining=['canonical source financial conflict must be reviewed;both layers retained','original sparseOFD/mixedAPI strictvisibility/adjacentlongedit/perMille/smallglyph andmetadata limits persist'],helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_postfix39.py','prepare_postfix39.py','OcrPostfixProbe39.java','verify_postfix39.py','OcrCapturedLexemeReplay35.java','capture_numeric_http35.py','run_edit_iteration26.py','qa_process_guard.py','qa_http_deadline.py']})
    replaySkipped=sorted({r['case'].removesuffix('-text') for r in b['cases']}-set(new));assert replaySkipped==['boundary-reserve','masked-edited']
    result.update(replayCoverage={'before':len(old),'after':len(new),'newControlsAll12Replayed':True,'oldRegressionAliasesSkipped':replaySkipped,'reason':'existing replay helper accepts id.ofd only;two action IDs alias other filenames;all15 downloaded regression outputs independently byte-exact'},fontsRetainedFromAccepted35=load(ROOT/'docs/cloud-numeric35-results.json')['fonts'])
    (ROOT/'docs/cloud-postfix39-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:result[k] for k in ['falseDuplicatesCorrected','aggregateTruthCharacters','aggregateCERBefore','aggregateCERAfter','httpBefore','httpAfter','fullSuite','jarSha256']},ensure_ascii=False))
if __name__=='__main__':main()
