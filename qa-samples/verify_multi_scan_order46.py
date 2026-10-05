#!/usr/bin/env python3
"""Audit two-scan ordinal repair and reused45/native negatives;no new OCR/Office/HTTP."""
import csv,hashlib,json,subprocess,zipfile,xml.etree.ElementTree as E
from collections import Counter
from pathlib import Path
import fitz,numpy as np
from PIL import Image
from qa_process_guard import matches
from verify_scan_tables45 import ROOT,WORK,W,V,word,read,sha,compact
from verify_scan_order45 import frame_signatures
from verify_cloud_ocr import metrics
from verify_numeric35 import packaged

def audit_http(out,count):
    report=read(out/'report.json')
    assert report['status']=='completed' and not report['failures'] and not report.get('unsupportedEdits')
    assert len(report['cases'])==len(report['workerIdentities'])==count
    assert not report['newZombies'] and report['supervision']['waitpidNoChildren']
    assert all(not matches(p) for p in report['workerIdentities'])
    for c in report['cases']:assert c['task']['status']=='SUCCESS' and sha(out/c['artifact'])==c['sha256']
    capture=read(out/'ocr-capture.json');assert not capture['errors'] and capture['observerStopped']
    assert not capture['engineSettingsChanged'] and capture['extraOcrInvocations']==0
    bytask={c['task']['taskId']:c['case'] for c in report['cases']};complete={}
    for record in capture['records']:
        assert sha(out/record['artifact'])==record['sha256']
        key=(bytask[record['taskId']],record['source'].split('/conversion/',1)[1])
        if key not in complete or record['bytes']>complete[key]['bytes']:complete[key]=record
    native={}
    for key,record in complete.items():
        rows=list(csv.DictReader((out/record['artifact']).open(),delimiter='\t'));page=next(r for r in rows if r['level']=='1')
        words=[r for r in rows if r['level']=='5' and r['text'].strip()];assert words
        native[key]=dict(**record,case=key[0],conversionSource=key[1],pixels=[int(page['width']),int(page['height'])],
            rawWords='\n'.join(r['text'] for r in words),words=words)
    return report,native

def native_runs(xml):
    boxed={id(t) for b in xml.iter(W+'txbxContent') for t in b.iter(W+'t')}
    return [E.tostring(r) for r in xml.iter(W+'r') if any(id(t) not in boxed for t in r.findall(W+'t'))]

def main():
    plan=read(ROOT/'qa-samples/generated/multi-scan46/expected.json');after_plan=read(ROOT/'qa-samples/generated/multi-scan46-after/expected.json')
    before=WORK/'iteration46-before-http';after=WORK/'iteration46-after-http'
    a,ta=audit_http(before,4);b,tb=audit_http(after,6)
    assert a['jarSha256']=='41a75070b3d15c239bf42fe78c942457d4f0e9db8ba273660b41d69228759669'
    provenance=read(WORK/'iteration46-provenance.json');assert b['jarSha256']==provenance['jarSha256']==sha(ROOT/'web-api/target/web-api-0.1.5.jar')
    assert a['manifestSha256']==sha(ROOT/'qa-samples/generated/multi-scan46/expected.json')
    assert b['manifestSha256']==sha(ROOT/'qa-samples/generated/multi-scan46-after/expected.json')
    assert after_plan['beforeManifestSha256']==a['manifestSha256']
    for name,value in plan['sources'].items():
        assert sha(ROOT/'qa-samples/generated/multi-scan46'/name)==sha(ROOT/'qa-samples/generated/multi-scan46-after'/name)==value
    assert sha(Path(plan['font']['path']))==plan['font']['sha256']
    # Source has one real native header and exactly two disjoint raster objects.
    source=fitz.open(ROOT/'qa-samples/generated/multi-scan46/mixed-two-scans.pdf');page=source[0]
    assert len(source)==1 and page.get_text()==plan['header']+'\n'
    source_images=page.get_images(full=True);assert len(source_images)==2
    source_pixels=[]
    for image,truth in zip(source_images,plan['images']):
        pix=fitz.Pixmap(source,image[0]);decoded=np.frombuffer(pix.samples,dtype=np.uint8).reshape(pix.height,pix.width,pix.n)
        with Image.open(ROOT/'qa-samples/generated/multi-scan46'/truth['file']) as expected:expected=np.asarray(expected.convert('RGB'))
        assert pix.n==3 and np.array_equal(decoded,expected)
        source_pixels.append(dict(file=truth['file'],pixels=[pix.width,pix.height],decodedRGBExact=True))
    source.close()
    assert len(ta)==2 and len(tb)==3
    for key,value in ta.items():
        assert value['pixels']==[960,960] and key in tb and tb[key]['sha256']==value['sha256']
    old=packaged(WORK/'iteration46-before.jar');new=packaged(ROOT/'web-api/target/web-api-0.1.5.jar');assert old.keys()==new.keys()
    changed=[name for name in old if old[name]!=new[name]]
    prefix='BOOT-INF/lib/docx-renderer-0.1.5.jar/com/fuyue/formatconverter/docx/FixedLayoutDocxRenderer'
    assert len(changed)==6 and all(p.startswith(prefix) and p.endswith('.class') for p in changed)
    parity=WORK/'iteration46-class-parity';parity.mkdir(exist_ok=True);inners=[]
    for path in changed:
        if '$' not in path:continue
        dumps=[]
        for label,values in [('before',old),('after',new)]:
            folder=parity/label;folder.mkdir(exist_ok=True);p=folder/Path(path).name;p.write_bytes(values[path])
            dump=subprocess.check_output(['javap','-c','-p',str(p)]);(folder/(p.name+'.javap.txt')).write_bytes(dump);dumps.append(dump)
        assert dumps[0]==dumps[1];inners.append(dict(path=path,methodBytecodeExact=True,javapSha256=hashlib.sha256(dumps[0]).hexdigest()))
    rows=[]
    for case,oldfolder,oldname in [('mixed-word',before,'mixed-word'),('native-word',before,'native-word'),
                                   ('single-word',WORK/'iteration45-order-http','columns-scan-word')]:
        pa,xa,wa=word(oldfolder/(oldname+'-result.docx'));pb,xb,wb=word(after/(case+'-result.docx'))
        assert frame_signatures(xa)==frame_signatures(xb),case
        assert wa['masks']==wb['masks'] and wa['media']==wb['media'] and wa['pageBoundsPt']==wb['pageBoundsPt']
        assert native_runs(xa)==native_runs(xb) and Counter(f['text'] for f in wa['frames'])==Counter(f['text'] for f in wb['frames'])
        assert wa['tables']==wb['tables']==0
        assert wa['outOfPageFrames']==wb['outOfPageFrames']==[]
        document_exact=pa['word/document.xml']==pb['word/document.xml']
        if case!='mixed-word':assert document_exact
        if case=='mixed-word':
            expected_ocr='\n'.join([*plan['columns'][0],*plan['columns'][1]])
            assert compact(wb['text'])==compact(expected_ocr)
            assert compact(wa['text'])!=compact(expected_ocr)
            all_text_a='\n'.join(t.text or '' for t in xa.iter(W+'t'));all_text_b='\n'.join(t.text or '' for t in xb.iter(W+'t'))
            assert all_text_a.count(plan['header'])==all_text_b.count(plan['header'])==1
            assert all_text_a.endswith(plan['header']) and all_text_b.endswith(plan['header'])
            fields=[line.split()[-1] for col in plan['columns'] for line in col[1:4]]
            assert all(wa['text'].count(field)==wb['text'].count(field)==1 for field in fields)
            detail=dict(beforeOcrText=wa['text'],afterOcrText=wb['text'],expectedOcrRegion=expected_ocr,
                beforeOcrRegionMetrics=metrics(expected_ocr,wa['text']),afterOcrRegionMetrics=metrics(expected_ocr,wb['text']),
                beforeWholeWordMetrics=metrics(plan['expectedMixedColumnMajor'],all_text_a),afterWholeWordMetrics=metrics(plan['expectedMixedColumnMajor'],all_text_b),
                sixNumericFieldsExact=fields,headerNativeRunExact=True,headerXmlLastUnchanged=True,
                note='OCRregion order accepted;wholeWord reading stream still places nativeheaderlast')
        else:detail={}
        rows.append(dict(case=case,allRawWordsExact=True,allWordGeometryFontTransformZLayersExact=True,
            masksAndScanBytesExact=True,nativeRunsExact=True,documentXmlByteExact=document_exact,**detail))
    # One-scanned-source45 control must preserve the previous production TSV.
    positive_capture=read(WORK/'iteration45-order-http/ocr-capture.json');positive_http=read(WORK/'iteration45-order-http/report.json')
    positive_id=next(c['task']['taskId'] for c in positive_http['cases'] if c['case']=='columns-scan-word')
    positive=max((v for v in positive_capture['records'] if v['taskId']==positive_id),key=lambda v:v['bytes'])
    single=next(v for k,v in tb.items() if k[0]=='single-word');assert single['sha256']==positive['sha256']
    rendering=WORK/'iteration46-render';rendering.mkdir(exist_ok=True);pdfs=[];new_renders=0
    for label,out,count in [('before',before,2),('after',after,3)]:
        names=['mixed-office','native-office']+(['single-office'] if count==3 else [])
        for name in names:
            path=out/(name+'-result.pdf');doc=fitz.open(path);assert len(doc)==1
            png=rendering/(label+'-'+name+'.png')
            if not png.exists():
                doc[0].get_pixmap(dpi=200,alpha=False).save(png);new_renders+=1
            else:
                prior=next(r for r in read(WORK/'iteration46-render-initial.json')['renders'] if r['stage']==label and r['id']==name)
                assert prior['pdfSha256']==sha(path) and prior['renderSha256']==sha(png)
            truth=plan['expectedMixedColumnMajor'] if name=='mixed-office' else plan['expectedNativeColumnMajor'] if name=='native-office' else read(ROOT/'qa-samples/generated/scan-tables45/expected.json')['cases'][1]['expectedColumnMajor']
            pdfs.append(dict(stage=label,id=name,pages=1,sha256=sha(path),nativeText=doc[0].get_text(),metrics=metrics(truth,doc[0].get_text()),renderDpi=200,renderSha256=sha(png),fonts=[f[3] for f in doc[0].get_fonts()]))
            doc.close()
    if not (WORK/'iteration46-render-initial.json').exists():
        (WORK/'iteration46-render-initial.json').write_text(json.dumps(dict(
            initialHelperSha256=sha(Path(__file__)),renders=[dict(stage=p['stage'],id=p['id'],
            pdfSha256=p['sha256'],renderSha256=p['renderSha256']) for p in pdfs]),indent=2)+'\n')
    for name,oldpath in [('mixed-office',rendering/'before-mixed-office.png'),('native-office',rendering/'before-native-office.png'),('single-office',WORK/'iteration45-order-render/columns-office.png')]:
        with Image.open(oldpath) as pa,Image.open(rendering/('after-'+name+'.png')) as pb:
            aa,bb=np.asarray(pa.convert('RGB')),np.asarray(pb.convert('RGB'))
        assert aa.shape==bb.shape and np.array_equal(aa,bb),name
    mixed_after=next(p for p in pdfs if p['stage']=='after' and p['id']=='mixed-office')
    assert Counter(compact(mixed_after['nativeText']).lower())==Counter(compact(plan['expectedMixedColumnMajor']).lower())
    assert all(mixed_after['nativeText'].count(field)==1 for field in rows[0]['sixNumericFieldsExact'])
    tests=read(WORK/'iteration46-test-summary.json')['counts'];assert tests==dict(tests=507,failures=0,errors=0,skipped=1)
    result=dict(parentRevision=plan['parentRevision'],status='accepted-finite-multi-scan-ordinal-guard',
        productionChanged=True,sourceBoundJarSha256=b['jarSha256'],productionFingerprint=provenance['buildInputSha256'],sourceBoundClasses=232,
        beforeManifestSha256=a['manifestSha256'],afterManifestSha256=b['manifestSha256'],sourceFont=plan['font'],sourceDecodedPixels=source_pixels,
        changedPackagedFiles=changed,unchangedApplicationClasses=226,innerMethodBytecodesExact=inners,
        wordRows=rows,pdfs=pdfs,nativeTsvsBefore=list(ta.values()),nativeTsvsAfter=list(tb.values()),
        twoImageNativeTsvsByteExact=True,single45TsvByteExact=True,extraDiagnosticOCR=0,totalSourceTsvIdentities=5,
        actualHttpBefore=4,actualHttpAfter=6,all10WorkersAbsent=True,ECHILD=True,newZombies=[],
        fullRenderedPages=5,newRendersThisAudit=new_renders,all3BeforeAfterPixelExact=True,nativeWordDocumentByteExact=True,single45WordDocumentByteExact=True,
        mixedOfficeReadingOrderAccepted=False,mixedOfficeRawNumericFieldsExact=True,
        fullBuild=tests,fullBuildPassed=506,bundledConditionalTestsExecuted=10,
        beforeResources=a['resources'],afterResources=b['resources'],bounds=plan['bounds'],
        warningsBefore=[dict(case=c['case'],warnings=c['task'].get('warnings',[])) for c in a['cases']],warningsAfter=[dict(case=c['case'],warnings=c['task'].get('warnings',[])) for c in b['cases']],
        remaining=['MixedWord nativeheaderlast inXML preserved,notcorrected;wholeWord CERdistinctfromOCRregionCER',
            'MixedOffice extractionstillweaves/splitsrows;CER71/139,notzero;pixelidentity doesnot certifyreadingorder',
            'NativeonlyOffice extractionorder limitation unchanged;negativecontrol meansnochange,notfullintentacceptance',
            'Prior45scannedtable/headerlargeframes/oldscanvisibleamountoverprint/APIduplicates andmissingID unchanged',
            'Ambiguous/overlapping/rotated/manyscans intent,MicrosoftWord,nativeMacWindowspackages and optional signedOFDunrun'],
        regressionFixtureNotes='Initialexpectedheaderfirst includedpreexistingseparateanchorboundary;isolatedOCRframes stillfailedbefore. Initialafterimagecount assumedtwoidenticalPNGparts;POIvalidlydeduplicates;madefixture scansdistinct;actualPDF alwaystwo distinctimages. InitialQAassumedOfficeCERzero;actualOfficeorder remainsunaccepted;5existingrenders retained/hashbound;noHTTP/OCR/Office rerun.')
    (ROOT/'docs/cloud-multi-scan-order46-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('Verified4before+6after HTTP;OCRregionorder exact;Officeorder stillunaccepted;5TSVsunchanged;3pixelExactcontrols;nativeheaderXMLboundary preserved;507total506pass1skip')
if __name__=='__main__':main()
