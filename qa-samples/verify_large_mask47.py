#!/usr/bin/env python3
"""Read-only audit of finite rejected mask candidate; no OCR/Office/HTTP."""
import csv,hashlib,json,xml.etree.ElementTree as E
from collections import Counter
from pathlib import Path
import numpy as np
from PIL import Image
from probe_large_mask47 import ROOT,WORK,OUT,JAR,sha
from verify_scan_tables45 import word,W,V
from verify_scan_order45 import frame_signatures

def signature(shape):
    shape=E.fromstring(E.tostring(shape));shape.attrib.pop('id',None)
    return E.tostring(shape).decode()

def masks(xml):
    return Counter(signature(s) for s in xml.iter(V+'rect') if s.get('id','').startswith('ocr-mask'))

def main():
    execution=json.loads((OUT/'execution.json').read_text());exact=json.loads((OUT/'exact/execution.json').read_text())
    assert sha(JAR)==execution['jarSha256']==exact['jarSha256']=='2e1961405e080daf66bb60bec6b6c8784739ef7091b0171301834336aaae6061'
    assert execution['ocrInvocations']==exact['ocrInvocations']==0
    assert execution['httpInvocations']==exact['httpInvocations']==0
    assert sha(ROOT/'docx-renderer/src/main/java/com/fuyue/formatconverter/docx/FixedLayoutDocxRenderer.java')==execution['productionSourceSha256']
    records=[]
    for case,folder in [('ruled',OUT/'exact'),('direct',OUT),('dark',OUT),('ordinary',OUT)]:
        a,b=folder/'baseline',folder/'candidate';pa,xa,wa=word(a/(case+'.docx'));pb,xb,wb=word(b/(case+'.docx'))
        assert frame_signatures(xa)==frame_signatures(xb)
        assert wa['media']==wb['media'] and wa['pageBoundsPt']==wb['pageBoundsPt']
        outside=lambda entries: [{k:v for k,v in entry.items() if k!='id'} for entry in entries]
        assert outside(wa['outOfPageFrames'])==outside(wb['outOfPageFrames'])
        assert (a/(case+'-model.tsv')).read_bytes()==(b/(case+'-model.tsv')).read_bytes()
        assert (a/(case+'-source.png')).read_bytes()==(b/(case+'-source.png')).read_bytes()
        before,after=masks(xa),masks(xb);assert not (after-before)
        removed=list((before-after).elements())
        if case=='ruled':assert len(removed)==2
        else:assert not removed and pa['word/document.xml']==pb['word/document.xml']
        with Image.open(a/(case+'.png')) as p,Image.open(b/(case+'.png')) as q:
            x,y=np.asarray(p.convert('RGB')),np.asarray(q.convert('RGB'))
        assert x.shape==y.shape
        changed=int(np.any(x!=y,axis=2).sum());assert changed==0
        geometry=dict(case=case,rawModelSha256=sha(a/(case+'-model.tsv')),rawModel=(a/(case+'-model.tsv')).read_text(),allWordsConfidenceSourceCoordinatesExact=True,allTextFramesFontsTransformsColorsZLayersExact=True,sourceScansByteExact=True,sourceScanSha256=sha(a/(case+'-source.png')),textFrames=wa['textFrames'],beforeMasks=len(wa['masks']),afterMasks=len(wb['masks']),removedMasksXml=removed,remainingMasksGeometryColorZExact=True,outOfPageFrames=wa['outOfPageFrames'],renderPixels=list(x.shape[:2][::-1]),changedRenderPixels=changed,allUnknownRegionsPixelExact=True,documentXmlByteExact=pa['word/document.xml']==pb['word/document.xml'])
        if case=='ruled':
            _,original,old=word(WORK/'iteration45-http/ruled-scan-word-result.docx')
            assert frame_signatures(original)==frame_signatures(xa) and masks(original)==masks(xa) and old['media']==wa['media']
            geometry['actualProductionFrameMaskScanParity']=True
            # Every unknown cell, header and numeric ROI unchanged, including the
            # still-occluded header. Identity is not visibility acceptance.
            truth=json.loads((ROOT/'qa-samples/generated/scan-tables45/expected.json').read_text())['cases'][0]
            sx=x.shape[1]/1920;sy=x.shape[0]/960;cells=[]
            for row in range(4):
                for col in range(3):
                    left,right=[round(v*sx) for v in truth['xGridPixels'][col:col+2]]
                    top,bottom=[round(v*sy) for v in truth['yGridPixels'][row:row+2]]
                    aa,bb=x[top:bottom,left:right],y[top:bottom,left:right]
                    assert np.array_equal(aa,bb)
                    cells.append(dict(row=row,col=col,truth=truth['matrix'][row][col],pixelExact=True,renderCropSha256=hashlib.sha256(aa.tobytes()).hexdigest()))
            geometry['all12SourceCellRois']=cells
            geometry['giantEeStillOccludesDateAmountHeaders']=True
            geometry['newlyRecoveredText']=False
        if case=='dark':
            crop=y[round(20*200/25.4):round(27*200/25.4),round(25*200/25.4):round(48*200/25.4)]
            bright=int(np.all(crop>200,axis=2).sum());assert bright>20
            geometry.update(brightTextPixels=bright,redUnknownRegionUnchanged=True,existingDarkRegressionGeometryReused=True,Office24_2Run=False)
        records.append(geometry)
    raw=[]
    for p in execution['frozenTsvs']:
        path=ROOT/p['path'];assert sha(path)==p['sha256']
        rows=list(csv.DictReader(path.open(),delimiter='\t'));words=[w for w in rows if w['level']=='5' and w['text'].strip()]
        raw.append(dict(**p,words=words))
    result=dict(status='rejected-no-visual-benefit',productionChanged=False,parentRevision=execution['parentRevision'],jarSha256=sha(JAR),productionSourceSha256=execution['productionSourceSha256'],candidateSourceSha256=execution['candidateSourceSha256'],frozenNativeTsvs=raw,cases=records,initialExecution=execution,sourceBoundExecution=exact,extraOCR=0,actualHTTP=0,officeActions=10,fullRenders=10,Office24_2RegressionEvidence=dict(commit='096f0eda722c626e9680268846979c19c9ee054f',run=37207251967,job=111450914787,version='24.2.7',failure='editable white letters must remain visibly readable on dark paper',reference='docs/cloud-edit-iteration26.md'),limits=dict(optionalImageDecodePixelCap=25000000,maxActualSourcePixels=3749*2500,knownWordCounts=[13,22,48,5],perOfficeTimeoutSeconds=90,rendererTimeoutSeconds=120,matrixTimeoutSeconds=600,newOCRTimeoutOrConcurrencyChanges=False,cpuAndPeakRssNotMeasured=True,officeSecondsAreSerialSingleRunsNotSpeedComparison=True),skipped=['new HTTP/amount edit: no positive renderer gain','Office24.2 local control unavailable; no global layering change','Microsoft Word/native Mac/Windows packaging','OCR or PSM/model/font sweep'],bootstrapFailure='missing QA ParseLimits import before any render/Office; corrected and resumed existing bootstrap',coordinateCorrection='Initial rounded243.84mm replay differed by0.001pt; only changed ruled pair repeated using actual PDF dimensions; all production frames/masks/scans now match exactly',decision='Mask-only omission is insufficient: huge editable ee glyphs still cover unknown headers. Keep production and layered dark-paper fallback unchanged; no confidence-only word filtering or shrinking adopted.')
    (ROOT/'docs/cloud-large-mask47-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=result['status'],productionChanged=False,all4PairsPixelExact=True,actualSourceParity=True,extraOCR=0,actualHTTP=0,officeActions=10)))

if __name__=='__main__':main()
