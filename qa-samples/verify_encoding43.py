#!/usr/bin/env python3
"""Verify encoding definitions, frozen receipts, actual selection and negative evidence."""
import hashlib,json,re
from pathlib import Path
from PIL import Image
from qa_process_guard import matches
from record_cloud_provenance import inputs,fingerprint
from verify_cloud_ocr import metrics
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'qa-samples/work'
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def text(r):return '\n'.join(b['text'] for b in r['blocks'])
def numbers(value):return re.findall(r'[+\-−]?\d+(?:[.\-]\d+)*(?:[%‰])?',value)
def main():
    corpus=ROOT/'qa-samples/generated/encoding43';plan=read(corpus/'expected.json');pixel=read(WORK/'iteration43-pixels/report.json');native=read(WORK/'iteration43-native/report.json')
    frozen=read(WORK/'iteration43-native/FROZEN.json');old=read(WORK/'iteration41-native/report.json');old={c['id']:c for c in old['cases']}
    assert len(plan['cases'])==len(pixel['cases'])==22 and pixel['newOCR']==0
    assert native['status']=='completed' and native['newNativeOCR']==8 and native['extraReplayOCR']==0
    assert frozen['manifestSha256']==sha(corpus/'expected.json') and frozen['pixelReportSha256']==sha(WORK/'iteration43-pixels/report.json')
    assert frozen['helperSha256']==sha(ROOT/'qa-samples/measure_encoding43.py')
    rows={Path(r['input']).stem:r for r in pixel['cases']};pair_rows=[]
    for case in plan['cases']:
        assert sha(corpus/case['file'])==case['sha256']==rows[case['id']]['sourceSha256']
        row=rows[case['id']];assert row['decoded']['whiteLuminanceHash']==row['javaEquivalent']['whiteLuminanceHash']
        assert row['enhanced']==row['javaEquivalentEnhanced'],case['id']
        if row['enhanced']['available']:
            path=WORK/'iteration43-pixels'/case['id']/'enhanced.png';assert sha(path)==row['enhanced']['pngSha256']
            assert Image.open(path).size==tuple(case['pixels'])
        if case.get('truthSource') and case['kind']=='raw-equivalent-gray':
            name=case['truthSource']['id'];assert case['sha256']==case['truthSource']['sourceSha256']
            if row['enhanced']['available']:assert (WORK/'iteration41-native'/name/'enhanced.png').read_bytes()==path.read_bytes()
    for pair in plan['pairs']:
        if 'gray' in pair:
            a,b=rows[pair['gray']],rows[pair['rgb']];ca,cb=next(c for c in plan['cases'] if c['id']==pair['gray']),next(c for c in plan['cases'] if c['id']==pair['rgb'])
            assert ca['pillowRGBHash']==cb['pillowRGBHash']
            assert a['decoded']['getRGBHash']!=b['decoded']['getRGBHash']
            null=pair['gray'].startswith('none-')
            if null:assert not a['enhanced']['available'] and not b['enhanced']['available']
            else:assert a['enhanced']['rawGrayHash']!=b['enhanced']['rawGrayHash']
            pair_rows.append(dict(**pair,pillowSampleBytesExact=True,javaGetRGBExact=False,enhancedRawExact=null,enhancementAvailable=not null))
        else:
            a,b=rows[pair['alpha']],rows[pair['matte']]
            assert a['decoded']['whiteLuminanceHash']==b['decoded']['whiteLuminanceHash'] and a['enhanced']==b['enhanced']
            pair_rows.append(dict(**pair,javaWhiteLuminanceExact=True,enhancementExact=True))
    measurements=[];commands=[];partial_invocations=0
    for case in native['cases']:
        name=case['id'];truth=next(c['truthSource'] for c in plan['cases'] if c['id']==name+'-gray');expected='\n'.join(truth['expectedLines'])
        current=case['current'];prior=old[name]['current'];assert case['originalNativeTsvByteExact']
        assert current['original']['blocks']==prior['original']['blocks'] and current['original']['confidence']==prior['original']['confidence']
        stage={'grayOriginal':prior['original'],'grayEnhanced':prior['variants']['enhanced']['candidate'],'rgbOriginal':current['original'],'rgbEnhanced':current['variants']['enhanced']['candidate']}
        result={k:dict(text=text(v),metrics=metrics(expected,text(v)),numericSurfaces=numbers(text(v)),exactNumericSequence=numbers(text(v))==truth['expectedNumericSurfaces'],meanConfidence=v['confidence'],wordCount=v['wordCount']) for k,v in stage.items()}
        candidate=current['variants']['enhanced'];assert candidate['selectedOriginal'] and not candidate['fullActualAccepted'] and not candidate['partialActualEligibleAccepted']
        assert not current['productionRetryEligible'] and candidate['candidateBoundsInsideOriginal']
        if name.endswith('gradient'):
            assert result['rgbEnhanced']['metrics']==result['grayEnhanced']['metrics']
            assert result['rgbOriginal']['metrics']['exactLines']==3
        if name.endswith('normal'):assert result['rgbOriginal']['metrics']['cer']==0 and result['rgbOriginal']['exactNumericSequence']
        if name=='zh-normal':assert '+12.509%' in result['rgbEnhanced']['text'] and not result['rgbEnhanced']['exactNumericSequence']
        measurements.append(dict(id=name,expectedText=expected,stages=result,originalTsvAndParsedBlocksConfidenceExact=True,
            actualRetryEligible=False,actualFullAccepted=False,actualPartialAccepted=False,selectedOriginal=True,candidateBoxesInsideOriginal=True,
            uncoveredShadedInk=current['uncoveredShadedInk'],originalGeometryStable=current['originalGeometryStable']))
        for command in case['cli']:
            assert command['exitCode']==0 and command['seconds']<=plan['bounds']['perOCRSeconds'] and command['peakRssKiB']<=plan['bounds']['maximumCliRssKiB']
            assert sha(Path(command['command'][2]+'.tsv'))==command['tsvSha256'];commands.append(command)
    manifest=read(WORK/'iteration25-review-app/ocr/OCR-RUNTIME.json')
    for f in manifest['files']:assert sha(WORK/'iteration25-review-app/ocr'/f['path'])==f['sha256']
    provenance=read(WORK/'iteration41-provenance.json');assert inputs()==provenance['buildInputs'] and fingerprint(inputs())==provenance['buildInputSha256']
    assert sha(ROOT/'web-api/target/web-api-0.1.5.jar')==provenance['jarSha256']
    receipts=[]
    for name in ['freeze','compile','pixel','native']:
        path=WORK/('iteration43-'+name+'-receipt.json');r=read(path)
        assert r['rootExitCode']==0 and r['waitpidNoChildren'] and r['status']=='reaped'
        assert all(not matches(x) for x in [r['root'],r['supervisor'],*r['registered']])
        receipts.append(dict(path=str(path.relative_to(ROOT)),exitCode=0,ECHILD=True,remainingRegisteredProcesses=0))
    ramp={}
    for mode in ['gray','rgb']:
        image=Image.open(WORK/'iteration43-pixels'/('ramp-'+mode)/'java-white-equivalent.png')
        ramp[mode]={v:image.getpixel((v,0))[0] for v in [0,1,4,16,64,112,128,192,238,255]}
    result=dict(parentRevision=plan['parentRevision'],status='closed-no-adoptable-production-gain',productionChanged=False,
        counts=dict(frozenInputs=22,newNativeOCR=8,repeated41OCR=0,selectorReplays=4,partialSelectorInvocations=partial_invocations,http=0,office=0),
        definitions=dict(A='Pillow12.3 RGB sample bytes identical;not universally colorimetrically equal',B=plan['definitionB'],C='Achromatic integer encoded-sample white composite;no OCR truth assigned after changing visibility'),
        manifestSha256=sha(corpus/'expected.json'),pixelReportSha256=sha(WORK/'iteration43-pixels/report.json'),nativeReportSha256=sha(WORK/'iteration43-native/report.json'),
        pairs=pair_rows,all22JavaLuminanceEquivalentEnhancementsExact=True,rampDecodedGrayValues=ramp,measurements=measurements,
        runtime=dict(java=pixel['javaVersion'],pillow=plan['pillowVersion'],numpy=plan['numpyVersion'],tesseract='5.5.2',leptonica='1.87.0',libpng='1.6.57',languages='chi_sim+eng',psm=3,
            modelRevision='87416418657359cb625c412a48b6e1d6d41c29bd',runtimeManifestSha256=sha(WORK/'iteration25-review-app/ocr/OCR-RUNTIME.json'),fonts=read(ROOT/'qa-samples/generated/shadow-local41/expected.json')['fonts']),
        sourceBoundJarSha256=provenance['jarSha256'],productionFingerprint=provenance['buildInputSha256'],sourceBoundClasses=provenance['applicationClassCount'],
        inheritedLocalTests='40 clean full build505 total504pass1optional signed-OFD skip;10bundled conditional tests executed;not rerun43',
        resources=dict(pixelJavaWallSeconds=pixel['wallSeconds'],pixelHeapMiB=256,nativeBatchWallSeconds=native['wallSeconds'],nativeCommandsWallSeconds=sum(c['seconds'] for c in commands),nativeUserSeconds=sum(c['userSeconds'] for c in commands),nativeSystemSeconds=sum(c['systemSeconds'] for c in commands),maximumNativeChildRssKiB=max(c['peakRssKiB'] for c in commands)),receipts=receipts,
        decision='Java gray color conversion is observable and enhancement is encoding-sensitive under raw-byte definitionA. Neither gradient gains a missing line. Chinese normal RGB enhancement trades restored00846 for a new+12.509% numeric error;original full truth remains selected. No production normalization or threshold change justified.',
        limits=['No universal PNG colorimetry claim;metadata-tagged/16bit/palette/colored alpha/EXIF rotation not tested','Local-shadow pairs and blank/noise/alpha controls pixel-only;no newOCR on these','No new Word/Office/mask/HTTP matrix because production artifact unchanged;no claim of visual/editability acceptance','Original sparseOFD/lowCJK/per-mille/oldscanoverprint/mixedstrictAPI unresolved;nativeMacWindows/MicrosoftWord/signedfixture unrun'])
    (ROOT/'docs/cloud-encoding43-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('Verified22 pixel controls;8 new bundledOCR;4 actual selector replays;0 adoptable gain;no production changes')
if __name__=='__main__':main()
