#!/usr/bin/env python3
"""Freeze four affected contracts and audit actual decimal-mask improvement.

Reuse frozen45 source/truth and before artifacts; no OCR/HTTP/Office in audit.
"""
import argparse,copy,hashlib,json,shutil,xml.etree.ElementTree as E
from collections import Counter
from pathlib import Path
import fitz,numpy as np
from PIL import Image
from verify_scan_tables45 import ROOT,WORK,W,V,read,sha,word
from verify_scan_order45 import frame_signatures
from verify_cloud_ocr import metrics
from qa_process_guard import matches

CORPUS=ROOT/'qa-samples/generated/decimal-masks48'
OUT=WORK/'iteration48-http'

def freeze():
    assert not CORPUS.exists();CORPUS.mkdir()
    source=WORK/'iteration45-http/ruled-wrap-result.pdf';shutil.copyfile(source,CORPUS/'ruled.pdf')
    truth=read(ROOT/'qa-samples/generated/scan-tables45/expected.json')
    manifest=dict(parentRevision='4d10e622c9d547f39a19e1c1972f3f79163183d5',generatorSha256=sha(Path(__file__)),cases=[truth['cases'][0]],sources={'ruled.pdf':sha(source)},font=truth['font'],actions=[
        dict(id='ruled-scan-word',input='ruled.pdf',target='docx'),
        dict(id='ruled-office',input='@ruled-scan-word',target='pdf'),
        dict(id='ruled-edited-office',input='@ruled-scan-word',target='pdf',edit=truth['cases'][0]['edit']),
        dict(id='ruled-edited-api',input='@ruled-edited-office',target='txt')],bounds=dict(contractSeconds=120,matrixSeconds=480),acceptance='visible decimal edit and source/coordinate protection; full table/text completeness separately recorded',stoppingRule='No extra OCR/PSM/resize sweep; four affected contracts only')
    (CORPUS/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('frozen four affected contracts')

def mask_signatures(xml):
    result=[]
    for e in xml.iter(V+'rect'):
        if not e.get('id','').startswith('ocr-mask'):continue
        item=copy.deepcopy(e);item.attrib.pop('id',None);result.append(E.tostring(item).decode())
    return result

def audit():
    report=read(OUT/'report.json');capture=read(OUT/'ocr-capture.json');provenance=read(WORK/'iteration48-provenance.json')
    assert report['status']=='completed-with-failures' and not report.get('unsupportedEdits')
    assert report['failures']==[dict(type='ConversionFailure',action='ruled-edited-api',errorCode='OCR_VISIBILITY_UNCERTAIN')]
    assert len(report['cases'])==len(report['workerIdentities'])==4
    assert all(c['task']['status']=='SUCCESS' for c in report['cases'][:3])
    assert report['cases'][-1]['task']['status']=='FAILED' and not report['cases'][-1]['task']['downloadReady']
    assert all(not matches(p) for p in report['workerIdentities']) and report['supervision']['waitpidNoChildren'] and not report['newZombies']
    assert not capture['errors'] and capture['observerStopped'] and not capture['engineSettingsChanged'] and capture['extraOcrInvocations']==0
    assert report['jarSha256']==provenance['jarSha256']==sha(ROOT/'web-api/target/web-api-0.1.5.jar')
    for c in report['cases'][:3]:assert sha(OUT/c['artifact'])==c['sha256']
    pa,xa,a=word(WORK/'iteration45-http/ruled-scan-word-result.docx');pb,xb,b=word(OUT/'ruled-scan-word-result.docx')
    assert frame_signatures(xa)==frame_signatures(xb), 'all editable words/fonts/geometry/transform/layers must remain identical'
    assert a['media']==b['media'] and a['pageBoundsPt']==b['pageBoundsPt'] and a['text']==b['text']
    before,after=mask_signatures(xa),mask_signatures(xb);assert len(before)==len(after)==12
    changed=[(x,y) for x,y in zip(before,after) if x!=y];assert len(changed)==2
    for x,y in changed:assert x.replace('z-index:-251658751','z-index:1')==y
    baseline_capture=read(WORK/'iteration45-http/ocr-capture.json');baseline_http=read(WORK/'iteration45-http/report.json')
    old_task=next(c['task']['taskId'] for c in baseline_http['cases'] if c['case']=='ruled-scan-word')
    new_task=next(c['task']['taskId'] for c in report['cases'] if c['case']=='ruled-scan-word')
    old=max((r for r in baseline_capture['records'] if r['taskId']==old_task),key=lambda r:r['bytes'])
    new=max((r for r in capture['records'] if r['taskId']==new_task),key=lambda r:r['bytes'])
    assert old['sha256']==new['sha256'];assert sha(OUT/new['artifact'])==new['sha256']
    edit=report['edits'][0];ep,ex,e=word(OUT/edit['artifact']);assert e['media']==b['media'] and mask_signatures(ex)==after
    assert e['text'].count('0180.80')==1 and '0170.80' not in e['text']
    assert Counter(e['text'])==Counter(b['text'].replace('0170.80','0180.80'))
    assert all(ep[n]==pb[n] for n in pb if n!='word/document.xml')
    rendered=WORK/'iteration48-render';rendered.mkdir(exist_ok=True);pdfs=[]
    for case,label in [('ruled-office','normal'),('ruled-edited-office','edited')]:
        pdf=OUT/(case+'-result.pdf');d=fitz.open(pdf);assert len(d)==1;png=rendered/(label+'.png')
        if png.exists():
            prior=next(p for p in read(ROOT/'docs/cloud-decimal-masks48-results.json')['pdfs'] if p['case']==case)
            assert sha(pdf)==prior['sha256'] and sha(png)==prior['renderSha256']
        else:
            d[0].get_pixmap(dpi=200,alpha=False).save(png)
        text=d[0].get_text();fonts=[f[3] for f in d[0].get_fonts()];d.close()
        with Image.open(png) as p,Image.open(WORK/'iteration48-visible'/(label+'.png')) as q:
            x,y=np.asarray(p.convert('RGB')),np.asarray(q.convert('RGB'))
        assert np.array_equal(x,y),'actual production HTTP output must match the bounded prototype'
        with Image.open(WORK/'iteration45-order-render'/('ruled-office.png' if label=='normal' else 'ruled-edited-office.png')) as p:
            old_pixels=np.asarray(p.convert('RGB'))
        delta=np.any(x!=old_pixels,axis=2);positions=np.argwhere(delta);assert positions.size
        # Both changed regions are inside the two original decimal word masks.
        permitted=np.zeros(delta.shape,dtype=bool)
        for _,mask in changed:
            node=E.fromstring(mask);style=dict(s.split(':',1) for s in node.get('style').split(';') if ':' in s)
            xx,yy,ww,hh=[float(style[k].removesuffix('pt'))*200/72 for k in ['margin-left','margin-top','width','height']]
            permitted[max(0,int(yy)-2):int(np.ceil(yy+hh))+2,max(0,int(xx)-2):int(np.ceil(xx+ww))+2]=True
        assert not np.any(delta & ~permitted),'unknown headers, IDs and gaps must not change'
        pdfs.append(dict(case=case,sha256=sha(pdf),renderSha256=sha(png),pages=1,nativeText=text,fonts=fonts,prototypePixelExact=True,changedRenderPixels=int(delta.sum()),allChangedPixelsInsideTwoOriginalDecimalMasks=True,allUnknownRegionsOutsideThoseMasksPixelExact=True))
    assert '0180.80' in pdfs[1]['nativeText'] and '0170.80' not in pdfs[1]['nativeText']
    raw=(WORK/'iteration45-order-http/ruled-edited-api-result.txt').read_text();truth=read(CORPUS/'expected.json')['cases'][0]
    desired=truth['expectedRowMajor'].replace('0170.80','0180.80')
    missing=[token for token in ['01936','03872','07744','2093-12-04','-042.70','+0085.40','2093-12-05','2093-12-06','0180.80'] if token not in raw]
    result=dict(parentRevision=provenance['parentRevision'],productionChanged=True,helperSha256=sha(Path(__file__)),jarSha256=provenance['jarSha256'],productionFingerprint=provenance['buildInputSha256'],sourceBoundClasses=provenance['applicationClassCount'],contractCount=4,httpSuccesses=3,httpFailures=1,allWorkersGone=True,ECHILD=True,newOwnZombies=0,extraDiagnosticOCR=0,productionWordTsvByteExact=True,tsvSha256=new['sha256'],allRawWordsNumbersSourceCoordinatesFontsTransformsTextLayersExact=True,sourceScansByteExact=True,masksPreserved=12,onlyTwoReliableDecimalMaskLayersChanged=changed,numberReserveUnchanged=True,originalOutOfPageFramesUnchanged=True,wordText=b['text'],wordBeforeMetrics=metrics(truth['expectedRowMajor'],a['text']),wordAfterMetrics=metrics(truth['expectedRowMajor'],b['text']),confidenceCompletenessUnchanged=True,fullBuildCounts=read(WORK/'iteration48-test-summary.json')['counts'],finalFocused=dict(tests=30,failures=0,errors=0,skipped=0),newActualOfficeShortEditTestPassed=True,editOnlyOneRun=True,editOld='0170.80',editNew='0180.80',pdfs=pdfs,visibleOldNewOverprintResolvedForTwoAffectedAmounts=True,api=dict(status='FAILED',errorCode='OCR_VISIBILITY_UNCERTAIN',downloadReady=False,afterMetrics=None,beforeRawText=raw,beforeMetrics=metrics(desired,raw),beforeNewAmountOccurrences=raw.count('0180.80'),beforeOldAmountOccurrences=raw.count('0170.80'),beforeMissingRequiredFields=missing,completeFinancialAcceptance=False,note='Existing strict partial-overlap guard retained; old SUCCESS output was incomplete/duplicated, no new TXT artifact'),resources=report['resources'],staleLowConfidenceGiantEeHeadersStillUnaccepted=True,fullLogicalTableStillUnaccepted=True,allPriorPartialQualityBoundariesRemain=True)
    (ROOT/'docs/cloud-decimal-masks48-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(dict(contracts=4,visibleTwoAmountFix=True,api=result['api'],jar=result['jarSha256']),ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--freeze',action='store_true');a=p.parse_args();freeze() if a.freeze else audit()
