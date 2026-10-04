#!/usr/bin/env python3
"""Verify finite route semantics and visible unmodeled ink; no OCR/Office invocation."""
import hashlib,json,sys,io,zipfile
from pathlib import Path
import fitz
from PIL import Image
from qa_process_guard import matches
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def main():
    corpus=ROOT/'qa-samples/generated/route34';folder=ROOT/'qa-samples/report/iteration34-controls'
    truth=load(corpus/'expected.json');report=load(folder/'report.json');native=load(ROOT/'qa-samples/work/iteration34-native.json');byNative={r['case']:r for r in native['rows']}
    assert report['jarSha256']=='6e0f7d94a2e0804074cc535c04a859e7f6c5fd8171cf94a21f9ba4b4a2434c6c' and report['manifestSha256']==sha(corpus/'expected.json')
    for n,s in truth['sources'].items():assert sha(corpus/n)==s
    assert len(report['cases'])==len(report['workerIdentities'])==7 and not report['newZombies'] and report['supervision']['waitpidNoChildren']
    assert all(not matches(p) for p in report['workerIdentities']) and report['health']['ocr']['bundled']
    rows=[];expectedFailures={'complete-local-mask':'OCR_NO_NEW_TEXT','unrecognized-ink':'OCR_NO_NEW_TEXT','transparent-mask':'OCR_VISIBILITY_UNCERTAIN'}
    cases={c['id']:c for c in truth['cases']}
    for c in report['cases']:
        name=c['case'];t=c['task'];trace=byNative[name];assert trace['pdfSha256']==sha(corpus/(name+'.pdf'))
        row=dict(id=name,status=t['status'],error=t['errorCode'],warnings=t['warnings'],nativeCharacters=trace['nativeCharacters'],requiredImages=trace['requiredImages'],nativeOnlyHypotheticalText=trace['nativeOnlyHypotheticalText'])
        if name in expectedFailures:
            assert t['status']=='FAILED' and t['errorCode']==expectedFailures[name] and not t['downloadReady'] and 'artifact' not in c
            row.update(artifactProduced=False,qualityPassed=False)
        else:
            assert t['status']=='SUCCESS' and t['downloadReady'] and sha(folder/c['artifact'])==c['sha256'];text=(folder/c['artifact']).read_text();row['text']=text
            expected=cases[name]['visibleExpected']
            if name=='conflicting-id':
                assert 'Record 00973Record 00974' in text and numeric_tokens(text)==numeric_tokens(cases[name]['nativeExpected']+'Record 00974')
                row.update(bothNumericSourcesPreserved=True,canonicalSingleValue=False)
            else:assert text==expected and numeric_tokens(text)==numeric_tokens(expected);row.update(rawTextExact=True,numericLexemesExact=True)
            if name=='native-only':assert not t['warnings'] and trace['requiredImages']==0
            if name=='complete-full-mask':assert any(w['code']=='OCR_OCCLUDED_TEXT_IGNORED' for w in t['warnings'])
            if name=='partial-native':assert 'Date 2088-09-26' not in trace['nativeOnlyHypotheticalText'] and 'Date 2088-09-26' in text
        rows.append(row)
    # This is actual visible ink, spatially separate from native blocks and mask.
    # Pixel counts are diagnostic evidence, never a proposed general completeness threshold.
    region=(295,1975,425,2005);png=corpus/'unrecognized-ink.png';pdf=corpus/'unrecognized-ink.pdf'
    with Image.open(png) as im:source=im.convert('RGB').crop(region)
    with fitz.open(pdf) as d:
        pix=d[0].get_pixmap(dpi=300,alpha=False);visible=Image.frombytes('RGB',(pix.width,pix.height),pix.samples).crop(region)
    assert source.size==visible.size and source.tobytes()==visible.tobytes()
    dark=sum(max(rgb)<128 for rgb in visible.getdata());assert dark>100
    bounds=[v*25.4/300 for v in region];assert all(not (b['box']['x']<bounds[2] and b['box']['x']+b['box']['width']>bounds[0] and b['box']['y']<bounds[3] and b['box']['y']+b['box']['height']>bounds[1]) for b in byNative['unrecognized-ink']['nativeBlocks'])
    assert 'MISSING' not in byNative['unrecognized-ink']['nativeOnlyHypotheticalText']
    dest=ROOT/'qa-samples/work/iteration34-evidence';dest.mkdir(exist_ok=False);visible.save(dest/'unrecognized-visible-ink.png')
    old=load(ROOT/'qa-samples/report/iteration33-final/report.json');saved=[]
    for c in old['cases']:
        if c['task']['status']!='FAILED':continue
        name=c['case'];pdfName=name.replace('-edited-text','-edited-office').replace('-text','-office')+'-result.pdf'
        path=ROOT/'qa-samples/report/iteration33-final'/pdfName;assert path.exists()
        traceName='saved33-'+name.replace('-edited-text','-edited').replace('-text','');trace=byNative[traceName]
        assert trace['pdfSha256']==sha(path) and trace['requiredImages']==1
        saved.append(dict(id=name,error=c['task']['errorCode'],pdfSha256=sha(path),nativeCharacters=trace['nativeCharacters'],imageCoverage=trace['images'],reusedHttp=True,ocrRerun=False))
    assert len(saved)==9 and native['ocrInvocations']==native['officeInvocations']==0 and len(native['rows'])==16
    result=dict(parentRevision='4022840f961d2fe0d0d39c46593a3e238dd5ecd5',jarSha256=report['jarSha256'],corpusManifestSha256=report['manifestSha256'],
        controls=rows,savedFailures=saved,unrecognizedInk=dict(actualText='MISSING 0063.10',fontPixels=14,renderDpi=300,cropPixelRegion=region,darkPixels=dark,sourceAndVisibleCropPixelExact=True,outsideNativeBlocks=True,absentFromNativeOnlyExtraction=True,apiStrictlyRefused=True,proofIsDiagnosticNotAcceptanceAlgorithm=True),
        nativeProbe=dict(rows=16,ocrInvocations=0,officeInvocations=0,outputSha256=sha(ROOT/'qa-samples/work/iteration34-native.json')),
        newHttp=7,interfaceSuccess=4,strictFailure=3,reusedPriorHttpFailures=9,workersAbsent=7,ECHILD=True,newZombies=[],resources=report['resources'],
        conclusion='No separate explicit PDF OCR-augmentation route or wrong route selection proven. Existing native-only and fully hidden-raster no-ops are valid. Residual OCR duplicates cannot prove all visible raster ink is backed; tiny visible unmodeled text would be lost by blanket duplicate no-op. No safe production gate change adopted.',
        productionCodeChanged=False,sourceJarWordArtifactsUnchanged=True,originalOfdNoveltyAndVisibilityContractsUnchanged=True,
        helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_route34.py','PdfRouteNativeProbe34.java','verify_route34.py','run_edit_iteration26.py','qa_process_guard.py']})
    (ROOT/'docs/cloud-route34-results.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n');print(json.dumps(dict(http=7,success=4,strict=3,nativeOnlyTrace=16,visibleMissingInkPixels=dark,productionChanged=False)))
if __name__=='__main__':main()
