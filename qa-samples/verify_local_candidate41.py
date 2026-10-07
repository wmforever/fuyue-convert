#!/usr/bin/env python3
"""Audit frozen native results, literal numeric completeness and safe hypothesis rejection."""
import hashlib,json,re,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageStat
from qa_process_guard import matches
from verify_cloud_ocr import metrics
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'qa-samples/work'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def text(result):return '\n'.join(b['text'] for b in result['blocks'])
def numbers(value):return re.findall(r'[+\-−]?\d+(?:[.\-]\d+)*(?:[%‰])?',value)
def main():
    corpus=ROOT/'qa-samples/generated/shadow-local41';plan=read(corpus/'expected.json');report=read(WORK/'iteration41-native/report.json')
    assert report['status']=='completed' and report['newNativeOcr']==20 and report['extraReplayOcr']==0
    assert report['manifestSha256']==sha(corpus/'expected.json') and report['helperSha256']==sha(ROOT/'qa-samples/measure_local_candidate41.py')
    receipt=read(WORK/'iteration41-native-receipt.json');assert receipt['rootExitCode']==0 and receipt['waitpidNoChildren']
    assert all(not matches(x) for x in [receipt['root'],receipt['supervisor'],*receipt['registered']])
    manifest=read(WORK/'iteration25-review-app/ocr/OCR-RUNTIME.json')
    for payload in manifest['files']:
        path=WORK/'iteration25-review-app/ocr'/payload['path'];assert sha(path)==payload['sha256']
    for font in plan['fonts'].values():assert sha(Path(font['path']))==font['sha256']
    assert sha(ROOT/'web-api/target/web-api-0.1.5.jar')==plan['comparison']['currentJarSha256']
    assert sha(ROOT/plan['comparison']['earlyAccuracyJar'])==plan['comparison']['earlyAccuracyJarSha256']
    counts=dict(sourceOcr=8,enhancedOcr=6,localOcr=6,totalOcr=20,replayOcr=0,http=0,office=0)
    rows=[];native_costs=[];candidate_numeric_regressions=[]
    for case,row in zip(plan['cases'],report['cases']):
        assert case['id']==row['id'] and sha(corpus/case['file'])==case['sourceSha256']
        folder=WORK/'iteration41-native'/case['id'];old=folder/'early';expected='\n'.join(case['expectedLines'])
        original=row['current']['original'];early_original=row['early']['original']
        assert original['blocks']==early_original['blocks'] and original['confidence']==early_original['confidence']
        if row['current']['variants']: assert not row['current']['productionRetryEligible']
        stages={'original':original};selections={}
        for name,value in row['current']['variants'].items():
            stages[name]=value['candidate'];assert value['candidateBoundsInsideOriginal']
            assert value['selectedOriginal'] and not value['fullActualAccepted'] and not value['partialActualEligibleAccepted']
            early=row['early']['variants'][name]
            assert not early['fullActualAccepted'] and early['selectedOriginal']
            assert early['candidate']['blocks']==value['candidate']['blocks'] and early['candidate']['confidence']==value['candidate']['confidence']
            assert (folder/(name+'.tsv')).read_bytes()==(old/(name+'.tsv')).read_bytes()
            selections[name]=dict(currentFullAccepted=False,currentPartialAccepted=False,currentOriginalRetained=True,earlyFullAccepted=False,
                gain=value['candidateGain'],fivePointGainPass=value['candidateGain']+1e-9>=.05,
                earlyEligibilityUnsimulated=True,earlyComparisonScope='actual full-selector replay only;no new early whole-converter/API/deskew run')
        measurements={}
        for stage,result in stages.items():
            actual=text(result);numeric=numbers(actual)
            measurements[stage]=dict(text=actual,metrics=metrics(expected,actual),numericSurfaces=numeric,expectedNumericSurfaces=case['expectedNumericSurfaces'],
                exactNumericSequence=numeric==case['expectedNumericSurfaces'],meanConfidence=result['confidence'],wordCount=result['wordCount'])
        if case['kind']=='normal' and 'local' in stages and numbers(text(original))!=numbers(text(stages['local'])):
            candidate_numeric_regressions.append(case['id'])
        for command in row['cli']:
            assert command['exitCode']==0 and command['seconds']<=plan['bounds']['perCommandSeconds'] and command['peakRssKiB']<=plan['bounds']['maximumCliRssKiB']
            tsv=Path(command['command'][2]+'.tsv');assert sha(tsv)==command['tsvSha256'];native_costs.append(command)
        pixels=[]
        if case['kind'] in ['gradient','local']:
            # Recreate only the already frozen glyph mask; no OCR or new candidate.
            mask=Image.new('L',tuple(case['pixels']));draw=ImageDraw.Draw(mask)
            for i,position in enumerate(case['sourcePositions'][:4]):
                font=ImageFont.truetype(plan['fonts'][case['language']]['path'],position['fontSize'])
                draw.text((100,[120,260,400,540][i]),position['text'],font=font,fill=255,anchor='ls')
            core=mask.point(lambda x:255 if x>=200 else 0)
            for stage,path in [('source',corpus/case['file']),('enhanced',folder/'enhanced.png'),('local',folder/'local.png')]:
                image=Image.open(path).convert('L');hist=image.histogram(core);count=sum(hist)
                pixels.append(dict(stage=stage,coreGlyphPixels=count,meanGray=sum(i*n for i,n in enumerate(hist))/count,
                    below128=sum(hist[:128]),imageSha256=sha(path)))
        rows.append(dict(id=case['id'],expectedText=expected,sourcePositions=case['sourcePositions'],measurements=measurements,
            productionRetryEligible=row['current']['productionRetryEligible'],uncoveredShadedInk=row['current'].get('uncoveredShadedInk'),
            originalGeometryStable=row['current'].get('originalGeometryStable'),selectedOriginalForAllCandidates=True,selections=selections,
            currentEarlyTsvParserBlocksConfidenceExact=True,currentEarlyEnhancedInputByteExact=True,coordinateBoundsOriginal=True,
            faintGlyphImageStatistics=pixels))
    assert candidate_numeric_regressions==['zh-normal'],candidate_numeric_regressions
    for row in rows[:4]:
        assert not row['measurements']['local']['metrics']['cer']<row['measurements']['enhanced']['metrics']['cer']
        assert row['measurements']['local']['metrics']['exactLines']<=3
    for row in rows[-2:]:assert not row['measurements']['original']['text'] and len(row['measurements'])==1
    costs=dict(wallSeconds=sum(c['seconds'] for c in native_costs),userSeconds=sum(c['userSeconds'] for c in native_costs),systemSeconds=sum(c['systemSeconds'] for c in native_costs),maximumChildRssKiB=max(c['peakRssKiB'] for c in native_costs),maximumCommandSeconds=max(c['seconds'] for c in native_costs))
    prior=read(ROOT/'docs/cloud-conflict-word40-results.json')
    result=dict(parentRevision=plan['parentRevision'],hypothesis=plan['hypothesis'],status='rejected-no-faint-row-recovery-and-normal-numeric-omission',productionChanged=False,
        manifestSha256=sha(corpus/'expected.json'),candidateParameters=plan['candidate'],limits=plan['bounds'],rows=rows,
        currentJarSha256=plan['comparison']['currentJarSha256'],earlyJarSha256=plan['comparison']['earlyAccuracyJarSha256'],
        currentEarlyEnhancementAndTsvParserExactControls=8,currentAndEarlyFullAdoption=0,currentPartialAdoption=0,currentPartialSelectorInvocations=0,
        candidateNormalNumericRegressions=candidate_numeric_regressions,selectedNumericRegressions=0,faintRowsRecoveredByCandidate=0,
        sourceOcrCounts=counts,nativeCosts=costs,wholeDiagnosticWallSeconds=report['wallSeconds'],
        currentSourceCoordinatesWordsAndScansUnmodified=True,newWordMaskOrOfficeAcceptance=False,
        inheritedFullSuite=prior['fullSuite'],inheritedBundledTests=prior['bundledTests'],inheritedBuildRevision=plan['parentRevision'],localFullBuildRerun=False,
        runtimeManifest=manifest,runtimeManifestSha256=sha(WORK/'iteration25-review-app/ocr/OCR-RUNTIME.json'),runtimePayloadFilesReverified=12,
        fonts=plan['fonts'],versions=prior['versions'],versionsSource='unchanged tools from40;no replacement',
        helperSha256={name:sha(ROOT/'qa-samples'/name) for name in ['generate_shadow_local41.py','prepare_local_candidate41.py','OcrLocalCandidateProbe41.java','measure_local_candidate41.py','verify_local_candidate41.py']},
        unrun=['new production HTTP/Word/Office matrix:negative candidate,production artifact unchanged','new local full build:reuse unchanged40 source/JAR','native macOS/Windows/Microsoft Word/optional signed fixture'],
        limitations=['early comparison replays actual full selector only,not whole converter eligibility/deskew/HTTP;historical8/14/15 HTTP remains attached to its source',
                    'all four synthetic shaded originals miss upper four rows despite false coverage probe;confidence is not completeness',
                    'local4x cap could leave faint ink too pale for global segmentation;no extra gain/threshold trial performed',
                    'existing gray/shadow,sparse OFD,CJK/number/per-mille,Word overprint and mixed strict API limitations retained'])
    (ROOT/'docs/cloud-shadow-local41-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('REJECT:0faint rows recovered;zh-normal00846 lost in candidates;20OCR/0replay/0HTTP/0Office;production unchanged')
if __name__=='__main__':main()
