#!/usr/bin/env python3
"""Verify cached visibility triggers, residual paint and the bounded no-production-change conclusion."""
import hashlib,json,os
from pathlib import Path
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    work=ROOT/'qa-samples/work';trace=load(work/'iteration37-trace-fixed/trace.json');projection=load(work/'iteration37-projection/projection.json')
    assert trace['status']=='completed' and trace['uniqueSourceRecognitions']==2 and trace['httpRequests']==trace['officeInvocations']==0
    a,b,c=trace['rows'];assert a['sourcePngSha256']==b['sourcePngSha256'] and a['recognition']==b['recognition'] and b['recognitionCached']
    assert a['errorCode']==b['errorCode']=='OCR_VISIBILITY_UNCERTAIN' and c['filterSuccess']
    for row in trace['rows']:
        assert sha(ROOT/row['sourcePath'])==row['pdfSha256'] and row['embeddedImageMaxEdge']==1600
        assert not row['unsupported'] and row['uncertainBounds']==[0,0,0,0] and row['operators']<=20000 and row['covers']<=512 and row['diagnosticPixelsExamined']<=250000
        assert all(w['insideImageClip'] for w in row['words'])
    for row in [a,b]:
        assert row['firstTrigger']=='opaqueDoesNotContainWholeWord'
        first=next(w for w in row['words'] if w['trigger']);assert first['text']=='核验' and first['sourceInkPixelsNotFullyCovered']==77
        visible=next(w for w in row['words'] if w['text']=='编辑');assert not visible['touchOpaque'] and not visible['touchUncertain']
        amount=next(w for w in row['words'] if w['text']=='048.65');assert amount['opaqueContainsWholeWord'] and amount['sourceInkPixelsNotFullyCovered']==0
    p=projection['rows'][0];assert projection['diagnosticProjectionNotActualFinalPage'] and projection['ocrInvocations']==projection['officeInvocations']==projection['httpRequests']==0
    residual=next(w for w in p['words'] if w['text']=='核验');assert residual['remainingRenderedNonWhite']==74 and residual['remainingRenderedDark']==23
    unmasked=next(w for w in p['words'] if w['text']=='编辑');assert unmasked['remainingRenderedDark']==2211
    for row in projection['rows']:
        for k in ['projectionPdf','projectionPng']:assert sha(work/'iteration37-projection'/row[k])==row[k+'Sha256']
    beforeTsv=work/'iteration37-trace/zh-positive-edited-office/ocr/tesseract-page-0001.tsv';actualTsv=work/'iteration37-trace-fixed/1-zh-positive-edited-office/ocr/tesseract-page-0001.tsv'
    assert sha(work/'iteration37-trace/zh-positive-edited-office/source.png')==a['sourcePngSha256']
    oldRows=[line.split('\t',11) for line in beforeTsv.read_text().splitlines()[1:]];newRows=[line.split('\t',11) for line in actualTsv.read_text().splitlines()[1:]]
    source=next(r for r in oldRows if r[-1]=='2097');scaled=next(r for r in newRows if r[-1]=='“097')
    assert source[:6]==scaled[:6] and source[0]=='5'
    receipts=[]
    for path in sorted(work.glob('iteration37-*.receipt.json')):
        r=load(path)
        if r['root']['pid']==os.getpid():continue # This command's final receipt is checked at closure.
        assert r['waitpidNoChildren'] and all(not matches(i) for i in r['registered'])
        receipts.append(dict(path=str(path.relative_to(ROOT)),sha256=sha(path),rootExitCode=r['rootExitCode'],ECHILD=True,registeredIdentitiesAbsent=len(r['registered'])))
    parent=load(ROOT/'docs/cloud-long-amount36-results.json');assert sha(ROOT/'web-api/target/web-api-0.1.5.jar')==parent['jarSha256']
    result=dict(parentRevision='7d4d97d8319598bbc08e674c38223c50fa6181bc',jarSha256=parent['jarSha256'],productionBuildInputSha256=parent['buildInputSha256'],productionChanged=False,fullBuildRerun=False,oldHttpOrOfficeMatricesRerun=False,
        conclusion='strict refusal is justified by partially exposed source glyph ink and an unmasked full word;no new coordinate/coverage implementation defect proven;no gate relaxation',
        pdfs=[dict(id=r['id'],pdfSha256=r['pdfSha256'],embeddedImageMaxEdge=r['embeddedImageMaxEdge'],imageBox=r['imageBox'],unsupported=r['unsupported'],uncertainBounds=r['uncertainBounds'],operators=r['operators'],covers=r['covers'],diagnosticPixelsExamined=r['diagnosticPixelsExamined'],filterSuccess=r['filterSuccess'],firstTrigger=r['firstTrigger'],words=r['words']) for r in trace['rows']],
        firstWord='核验',firstSourceInkPixelsNotFullyCovered=77,diagnosticResidualNonWhite=74,diagnosticResidualDark=23,unmaskedWholeWord='编辑',diagnosticUnmaskedDark=2211,
        whiteGapOnlyWords=['编号','文字'],whiteGapDoesNotJustifyBlanketAcceptance=True,allSourceWordsInsideImageClip=True,imageAxisGeometryMatches=True,oldAmount04865FullyCovered=True,
        currentChinesePdfNativeLongAmount='123456789012.65',priorChineseHttpFailuresReused=3,currentChineseHttpFailureReused='OCR_VISIBILITY_UNCERTAIN',acceptedEnglishVisibilityControl=True,
        sourceRecognitionCallsAccepted=2,initialFailedDiagnosticSourceRecognitionCalls=1,totalNewSourceRecognitionCalls=3,sourceTsvFilesObserved=3,httpNew=0,officeNew=0,diagnosticProjections=2,projectionIsNotActualFinalPage=True,
        initialHelperFailure='wrong2600edge(firstsource2000px) and colliding before/after filenames;oneunused source call;receipt/data retained;fixed helper reads actual1600constant and caches image',
        nextDifferentHighValueQuestion=dict(problem='1600px embedded-image normalization changes a visible heading2097 into“097 despite unchanged source and correct native heading;test numeric fidelity/resource tradeoff independently of visibility',sourceWidth=2000,normalizedWidth=1600,expected='2097',observedFullSize='2097',observedNormalized='“097',confidenceFullSize=float(source[10]),confidenceNormalized=float(scaled[10]),fullSizeTsvSha256=sha(beforeTsv),normalizedTsvSha256=sha(actualTsv),observationFromCachedDiagnosticNotApiAcceptance=True,proposedScope='freeze a small independent bilingual mixed-PDF numeric corpus at several font sizes;compare exact number surfaces/CER/completeness,time/RSS under existing pixel/time bounds;no fixed-size or confidence-only relaxation'),
        helperSha256={n:sha(ROOT/'qa-samples'/n) for n in ['PdfVisibilityTriggerProbe37.java','PdfCoverResidualRender37.java','verify_visibility37.py','qa_process_guard.py']},receipts=receipts,
        reusedTests=parent['tests'],reusedBundledTests=10,reusedRuntimeManifestSha256=parent['runtimeManifestSha256'],nativePackagesAndMicrosoftWordUnrun=True,newParallelWriter=False)
    (ROOT/'docs/cloud-visibility37-results.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(dict(productionChanged=False,firstTrigger='核验/partialopaque',residualDark=23,sourceCalls=3,newHttp=0,newOffice=0,next='1600pxnumeric2097→“097'),ensure_ascii=False))
if __name__=='__main__':main()
