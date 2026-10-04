#!/usr/bin/env python3
"""Audit frozen truth, actual downloaded text/Word, cached models and conservative stop."""
import hashlib,io,json,re,statistics,xml.etree.ElementTree as E,zipfile,os
from pathlib import Path
from PIL import Image,ImageChops
from qa_process_guard import matches
from measure_numeric_scale38 import metrics
from record_cloud_provenance import inputs,fingerprint
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'qa-samples/work'
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
V='{urn:schemas-microsoft-com:vml}'
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def style_box(node):
    fields={k:float(v) for k,v in re.findall(r'(margin-left|margin-top|width|height):([-0-9.]+)pt',node.get('style',''))}
    return [fields['margin-left'],fields['margin-top'],fields['margin-left']+fields['width'],fields['margin-top']+fields['height']]
def main():
    corpus=ROOT/'qa-samples/generated/numeric-scale38';truth=load(corpus/'expected.json')
    assert len(truth['cases'])==8 and truth['freezeBeforeOcr'] and truth['generatorSha256']==sha(ROOT/'qa-samples/generate_numeric_scale38.py')
    for path,digest in truth['sources'].items():assert sha(corpus/path)==digest
    raw=load(WORK/'iteration38-measure3/results.json');original=load(WORK/'iteration38-pdf-original/results.json');candidate=load(WORK/'iteration38-bilinear/results.json')
    assert raw['status']==original['status']==candidate['status']=='completed' and len(raw['rows'])==16 and len(original['rows'])==len(candidate['rows'])==8
    parity=load(WORK/'iteration38-http-tsv-parity.json');prepared=load(WORK/'iteration38-prepare-check/results.json')
    assert len(parity['rows'])==6 and len(prepared['rows'])==8 and parity['extraOcrCalls']==prepared['extraOcrCalls']==0
    for row in parity['rows']:assert (ROOT/row['httpTsv']).read_bytes()==(ROOT/row['pngDiagnosticTsv']).read_bytes()
    for row in prepared['rows']:
        name=row['id'];a=WORK/'iteration38-prepare-check'/name/'prepared.png';b=WORK/'iteration38-bilinear'/(name+'-bilinear1600')/'prepared.png'
        assert sha(a)==sha(b)==row['sha256']
    current={r['id']:r for r in raw['rows'] if r['mode']=='current1600'};rawOriginal={r['id']:r for r in raw['rows'] if r['mode']=='original'}
    orig={r['id']:r for r in original['rows']};bilinear={r['id']:r for r in candidate['rows']}
    http=load(WORK/'iteration38-http/report.json');capture=load(WORK/'iteration38-http/ocr-capture.json');assert http['status']=='completed' and len(http['cases'])==len(http['workerIdentities'])==14 and not http['failures'] and not http['newZombies']
    assert http['supervision']['waitpidNoChildren'] and all(not matches(i) for i in http['workerIdentities']) and not capture['errors'] and capture['extraOcrInvocations']==0
    requests={c['case']:c for c in http['cases']};rows=[];boundChecks=0
    settings=None
    for case in truth['cases']:
        name=case['id'];row=dict(id=name,fontPixels=case.get('fontPixels'),control=case['control'],current1600=current[name]['metrics'],pdfOriginal2000=orig[name]['metrics'],rawPngOriginal2000=rawOriginal[name]['metrics'],bilinear1600=bilinear[name]['metrics'])
        originalImage=Image.open(corpus/(name+'.png'));decodedImage=Image.open((ROOT/orig[name]['model']).parent/'pdf-source.png')
        assert originalImage.size==decodedImage.size and originalImage.convert('RGB').tobytes()==decodedImage.convert('RGB').tobytes()
        row['originalPngVsPdfDecodedSource']=dict(rgbPixelsExact=True,pngDpi=originalImage.info.get('dpi'),pdfDecodedPngDpi=decodedImage.info.get('dpi'))
        txt=requests[name+'-txt'];assert txt['task']['status']=='SUCCESS' and sha(WORK/'iteration38-http'/txt['artifact'])==txt['sha256']
        text=(WORK/'iteration38-http'/txt['artifact']).read_text();row['http']=dict(status='SUCCESS',metrics=metrics(case['nativeExpected']+case['expected'],text),warningConfidence=[w['confidence'] for w in txt['task']['warnings'] if w['code']=='OCR_APPLIED'])
        if case['control']:
            assert text==case['nativeExpected'] and not txt['task']['warnings'];row['http']['engineCalls']=0
        else:
            assert text==case['nativeExpected']+current[name]['metrics']['actual'];row['http']['engineCalls']=1
            docx=requests[name+'-docx'];assert docx['task']['status']=='SUCCESS' and sha(WORK/'iteration38-http'/docx['artifact'])==docx['sha256']
            with zipfile.ZipFile(WORK/'iteration38-http'/docx['artifact']) as z:
                root=E.fromstring(z.read('word/document.xml'));texts=[n.text or '' for n in root.iter(W+'t')];assert texts.count(case['nativeExpected'].strip())==1
                editable='\n'.join(t for t in texts if t!=case['nativeExpected'].strip());assert metrics(current[name]['metrics']['actual'],editable)['exactTextWithoutWhitespace']
                scans=[]
                for p in z.namelist():
                    if p.startswith('word/media/'):
                        image=Image.open(io.BytesIO(z.read(p))).convert('RGB')
                        if image.size==(2000,1700):
                            assert ImageChops.difference(image,Image.open(corpus/(name+'.png')).convert('RGB')).getbbox() is None;scans.append(dict(part=p,sha256=hashlib.sha256(z.read(p)).hexdigest(),pixelExact=True))
                assert len(scans)==1
                masks=[n for n in root.iter(V+'rect') if n.get('id','').startswith('ocr-mask-')]
                row['word']=dict(editableScanTextMetrics=metrics(case['expected'],editable),nativeHeaderPreservedExactlyOnce=True,nativeHeaderXmlAfterScan=texts[-1]==case['nativeExpected'].strip(),xmlOrderNotVisualReadingOrder=True,sourceScans=scans,maskCount=len(masks),officeReopenUnrun=True)
                if name=='zh-24':
                    bad=[n for n in root.iter(V+'rect') if 'f073.26' in ''.join(t.text or '' for t in n.iter(W+'t'))];assert len(bad)==1
                    amount=style_box(bad[0]);cx=(amount[0]+amount[2])/2;cy=(amount[1]+amount[3])/2
                    covering=[n for n in masks if (b:=style_box(n))[0]<=cx<=b[2] and b[1]<=cy<=b[3]];assert covering
                    indices=[int(re.search(r'z-index:([-0-9]+)',n.get('style')).group(1)) for n in covering];assert all(z<0 for z in indices)
                    row['word']['wrongAmountEditable']='f073.26';row['word']['lowConfidenceAmountMaskNotPromoted']=True;row['word']['lowConfidenceMaskZIndices']=indices
        for mode,path in [('current1600',WORK/'iteration38-prepare-check/replayed-current-en-56/model.json' if name=='en-56' else ROOT/current[name]['model']),('pdfOriginal2000',ROOT/orig[name]['model']),('bilinear1600',ROOT/bilinear[name]['model'])]:
            model=load(path);recognition=model['recognition'];actualSettings=model['settings'];settings=settings or actualSettings;assert actualSettings==settings
            assert not recognition['imageEnhanced'] and recognition['deskewDegrees']==0 and not recognition['partialRecovery']
            x,y,w,h=case['physicalImageBoxMm']
            for block in recognition['blocks']:
                for word in block['ocrWords']:
                    b=word['box'];assert b['x']>=x-1e-8 and b['y']>=y-1e-8 and b['x']+b['width']<=x+w+1e-8 and b['y']+b['height']<=y+h+1e-8;boundChecks+=1
            row[mode+'Model']=dict(confidence=recognition['confidence'],wordCount=recognition['wordCount'],possibleTextOmission=recognition['possibleTextOmission'],imageEnhanced=False,deskewDegrees=0,conflicts=recognition['conflicts'])
            if mode=='current1600' and not case['control']:assert abs(recognition['confidence']-row['http']['warningConfidence'][0])<1e-12
        rows.append(row)
    aggregates={}
    for mode in ['current1600','pdfOriginal2000','rawPngOriginal2000','bilinear1600']:
        text=[r[mode] for r in rows if not r['control']];total=sum(r['truthChars'] for r in text);errors=sum(r['charErrors'] for r in text)
        aggregates[mode]=dict(truthChars=total,charErrors=errors,contentCER=errors/total,exactTextCases=sum(r['exactTextWithoutWhitespace'] for r in text),exactNumericSequenceCases=sum(r['exactNumericSequence'] for r in text),textCases=6,emptyControlsNoInventedText=all(not r[mode]['actual'].strip() for r in rows if r['control']))
    assert aggregates['current1600']['charErrors']==aggregates['bilinear1600']['charErrors']==1
    assert aggregates['pdfOriginal2000']['charErrors']==8 and aggregates['rawPngOriginal2000']['charErrors']==4
    costs={}
    for mode,data in [('rawPngCurrent1600',[r for r in raw['rows'] if r['mode']=='current1600']),('rawPngOriginal2000',[r for r in raw['rows'] if r['mode']=='original']),('rawPngBilinear1600',candidate['rows']),('pdfDecodedOriginal2000',original['rows'])]:
        text=[r['resource'] for r in data if r['id'] not in ['blank','noise']]
        costs[mode]=dict(medianWallSeconds=statistics.median(r['wallSeconds'] for r in text),totalWallSeconds=sum(r['wallSeconds'] for r in text),maxDescendantRssKiB=max(r['maxRssKiB'] for r in text),totalUserSeconds=sum(r['userSeconds'] for r in text),totalSystemSeconds=sum(r['systemSeconds'] for r in text))
    provenance=load(WORK/'iteration37-provenance.json');assert fingerprint(inputs())==provenance['buildInputSha256'] and sha(ROOT/'web-api/target/web-api-0.1.5.jar')==provenance['jarSha256']
    receipts=[]
    for p in sorted(WORK.rglob('*receipt.json')):
        if 'iteration38' not in str(p):continue
        record=load(p)
        if record['supervisor']['pid']==os.getppid() or record['root']['pid']==os.getpid():continue
        assert record['status']=='reaped' and record['waitpidNoChildren'] and not any(matches(i) for i in record['registered'])
        receipts.append(dict(path=str(p.relative_to(ROOT)),rootExitCode=record['rootExitCode'],ECHILD=True))
    prior=load(ROOT/'docs/cloud-long-amount36-results.json')
    result=dict(status='completed; no measured production improvement adopted',parentRevision=truth['parentRevision'],productionChanged=False,jarSha256=provenance['jarSha256'],buildInputSha256=provenance['buildInputSha256'],manifestSha256=sha(corpus/'expected.json'),aggregates=aggregates,rows=rows,settings=settings,versionsRetainedFrom36=prior.get('versionsRetainedFromAcceptedHead35'),fonts=truth['fonts'],pillowVersion=truth['pillowVersion'],pymupdfVersion=truth['pymupdfVersion'],resources=costs,httpResources=http['resources'],httpRequests=14,httpSuccesses=14,httpTextExactCases=5,httpTextCases=6,wordOutputs=6,blankNoiseApiNoOcr=True,wordSourcePixelsExact=True,originalCoordinateWordBoundsChecked=boundChecks,newDiagnosticEngineCalls=32,newHttpEngineCalls=12,extraEngineCallsForReplayAndNormalizationChecks=0,newOfficeInvocations=0,rawPngDiagnosticCalls=24,pdfDecodedOriginalCalls=8,fullModelsMissingDuringInitialSerializationRecoveredByActualHttpTsvReplay=True,failuresRetained=['missing /usr/bin/time:0enginecalls','initial post-OCR Duration JSON serialization failure:1enginecall; saved TSV reused without new OCR'],productionPipelineParity='all6 current HTTP complete TSVs byte-exact to cached current1600 PNG; all8 PDF-decoded bilinear prepared PNGs byte-exact to cached candidate',resourceLimitsUnchanged=True,timeoutSeconds=120,maxImagePixels=25000000,maxConcurrency=1,noGlobalResolutionIncrease=True,noExtraProductionRetry=True,resourceCostScope='fresh per-recognition Python RUSAGE_CHILDREN max descendant RSS; not simultaneous total; raw PNG costs exclude PDF parse and cannot be directly compared to PDF-decoded-original costs or HTTP totals',rejectedCandidates=['retain original2000: repairs small numeric sign but PDF-origin text errors increase1→8 and numeric regression on zh38','bilinear1600:same error and exact numeric results as current; no measured gain'],remaining=['1600px zh24 +073.26 recognized f073.26; API SUCCESS with92.17% average; word56.86% preserves original lowconfidence scan region','2000px raw PNG versus PDF-decoded source recognition differs despite RGB pixel equality; metadata/DPI sensitivity not causally isolated','no broader-resolution/alternative-kernel evidence; do not select candidate by truth or mean confidence','new6Word Office/MicrosoftWord/nativepackages/signedfixture unrun; prior accepted matrices not rerun','prior Chinese mixedAPI strictvisibility/sparseOFD/adjacentlongedit/perMille/sourceconflicts retained'],receipts=receipts,helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_numeric_scale38.py','measure_numeric_scale38.py','OcrScaleProbe38.java','verify_numeric_scale38.py']})
    runtime=WORK/'iteration25-review-app/ocr/OCR-RUNTIME.json';manifest=load(runtime)
    result.update(runtimeManifestSha256=sha(runtime),runtimePayloadFilesReverified=12,models=manifest['models'],runtimeComponents=manifest['components'],fontVersionsBoundToAcceptedHashes={'Liberation Sans':'2.1.5','Droid Sans Fallback':'2.55'},sourcePixelSize=[2000,1700],currentPreparedPixelSize=[1600,1360],preservedOriginalPixelSize=[2000,1700],sourcePixels=3400000,currentPreparedPixels=2176000)
    (ROOT/'docs/cloud-numeric-scale38-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(dict(aggregates=aggregates,http='14success;5/6text exact;6Wordsourcepixel exact',coordinateBounds=boundChecks,diagnosticOCR=32,httpOCR=12,productionChanged=False),ensure_ascii=False))
if __name__=='__main__':main()
