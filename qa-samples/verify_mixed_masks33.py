#!/usr/bin/env python3
"""Check actual Office/API masks, source conflicts, exact text and bounded pixel parity."""
import copy,hashlib,io,json,math,os,re,subprocess,time,shutil,xml.etree.ElementTree as E
from pathlib import Path
import fitz
from PIL import Image,ImageChops,ImageDraw
from qa_process_guard import ManagedProcess,matches,install_shutdown_handlers
from verify_edit_iteration26 import parts
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';V='{urn:schemas-microsoft-com:vml}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def masks(root):return [n for n in root.iter(V+'rect') if n.get('id','').startswith('ocr-mask-')]
def box(node):
    s=node.get('style');values=[]
    for p in ['margin-left','margin-top','width','height']:
        match=re.search(r'(?:^|;)'+p+r':(-?[\d.]+)(mm|pt)',s)
        values.append(float(match.group(1))*(25.4/72 if match.group(2)=='pt' else 1))
    return [values[0],values[1],values[0]+values[2],values[1]+values[3]]
def raster(p):
    with fitz.open(p) as d:
        assert len(d)==1;pix=d[0].get_pixmap(dpi=300,alpha=False)
        return Image.frombytes('RGB',(pix.width,pix.height),pix.samples),d[0].get_text()
def pixelbox(mm,pad=2):return [math.floor(mm[0]*300/25.4)-pad,math.floor(mm[1]*300/25.4)-pad,math.ceil(mm[2]*300/25.4)+pad,math.ceil(mm[3]*300/25.4)+pad]
def outside(a,b,regions):
    assert a.size==b.size;diff=ImageChops.difference(a,b);full=diff.getbbox();draw=ImageDraw.Draw(diff)
    for region in regions:draw.rectangle(region,fill=(0,0,0))
    return dict(exact=diff.getbbox() is None,changedBounds=full,remainingBounds=diff.getbbox(),excludedPixelRegions=regions)
def stats(expected,actual):
    prev=list(range(len(actual)+1))
    for i,x in enumerate(expected,1):
        cur=[i]
        for j,y in enumerate(actual,1):cur.append(min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
        prev=cur
    return dict(text=actual,exact=actual==expected,rawCER=prev[-1]/max(1,len(expected)),normalizedMetrics=metrics(expected,actual),numericLexemesExact=numeric_tokens(expected)==numeric_tokens(actual))
def report(folder,count,strict=None):
    strict=strict or {}
    j=load(folder/'report.json');assert j['status'] in ['completed','completed-with-failures'] and len(j['cases'])==count and not j['newZombies']
    assert j['supervision']['waitpidNoChildren'] and len(j['workerIdentities'])==count and all(not matches(p) for p in j['workerIdentities'])
    assert j['health']['ocr']['bundled']
    for c in j['cases']:
        if c['case'] in strict:
            assert c['task']['status']=='FAILED' and c['task']['errorCode']==strict[c['case']]
            assert not c['task']['downloadReady'] and 'artifact' not in c
        else:assert c['task']['status']=='SUCCESS' and sha(folder/c['artifact'])==c['sha256']
    return j
def main():
    install_shutdown_handlers();before=ROOT/'qa-samples/report/iteration33-before';after=ROOT/'qa-samples/report/iteration33-after'
    after=ROOT/'qa-samples/report/iteration33-final'
    strict={n+s:'OCR_NO_NEW_TEXT' for n in ['white','gray','split','original'] for s in ['-text','-edited-text']}
    strict['marked-text']='OCR_VISIBILITY_UNCERTAIN'
    a=report(before,20);b=report(after,31,strict);candidate=report(ROOT/'qa-samples/report/iteration33-after',31)
    corpus=ROOT/'qa-samples/generated/mixed-masks33-final';truth=load(corpus/'expected.json')
    assert b['manifestSha256']==sha(corpus/'expected.json') and a['manifestSha256']==truth['frozenManifestSha256']
    for n,s in truth['sources'].items():assert sha(corpus/n)==s
    dest=ROOT/'qa-samples/work/iteration33-visible-final';dest.mkdir(exist_ok=False);rows=[];api={c['case']:c for c in b['cases']}
    for case in truth['cases']:
        name=case['id'];oldfolder=before if name!='original' else ROOT/'qa-samples/report/iteration32-word-before';oldname=name if name!='original' else 'partial'
        old,new=[parts(folder/(prefix+'-word-result.docx')) for folder,prefix in [(oldfolder,oldname),(after,name)]]
        oldroot,newroot=[E.fromstring(j['word/document.xml']) for j in [old,new]]
        assert {k:v for k,v in old.items() if k not in ['docProps/core.xml','word/document.xml']}=={k:v for k,v in new.items() if k not in ['docProps/core.xml','word/document.xml']}
        restored=copy.deepcopy(newroot);promoted=[]
        for node in masks(restored):
            if 'z-index:1;' in node.get('style'):
                promoted.append(box(node));node.set('style',node.get('style').replace('z-index:1;','z-index:-251658751;'))
        assert E.tostring(restored)==E.tostring(oldroot),name+' changed Word content beyond mask z-index'
        source=corpus/('partial-new-digits.png' if name=='original' else name+'.png')
        with Image.open(source) as im:size=im.size;pixels=im.convert('RGB').tobytes()
        preserved=[]
        for n,data in new.items():
            if not n.startswith('word/media/'):continue
            with Image.open(io.BytesIO(data)) as im:
                if im.size==size and im.convert('RGB').tobytes()==pixels:preserved.append(n)
        assert preserved
        row=dict(id=name,onlyMaskZIndexChanged=True,sourceScanPixelsExact=True,sourceMedia=preserved,promotedMaskBoxesMm=promoted,
            editableText=''.join(n.text or '' for n in newroot.iter(W+'t')),warnings=api[name+'-word']['task']['warnings'])
        if name=='conflict':
            assert not promoted
            actual=(after/'conflict-native-text-result.txt').read_text();assert actual==(before/'conflict-native-text-result.txt').read_text()
            assert '127.50' in actual and '127.51' in actual and any(w['code']=='OCR_RECOGNITION_CONFLICT' for w in row['warnings'])
            row.update(preservedBothConflictValues=True,conflictWarningPreserved=True,wordAllPartsExceptCoreExact=True,unsupportedCanonicalAmount=True);rows.append(row);continue
        pairs=[]
        for suffix in ['office','edited-office'] if case.get('editedExpected') else ['office']:
            oldpix,_=raster(oldfolder/(oldname+'-'+suffix+'-result.pdf'));newpix,native=raster(after/(name+'-'+suffix+'-result.pdf'))
            parity=outside(oldpix,newpix,[pixelbox(mm) for mm in promoted]);assert parity['exact'],(name,suffix,parity)
            id=name+('-edited-text' if suffix=='edited-office' else '-text')
            oldid=oldname+('-edited-text' if suffix=='edited-office' else '-text')
            if name=='original' and suffix=='office':oldid='partial-office-text'
            expected=case.get('editedExpected') if suffix=='edited-office' else case['expected']
            beforeText=(oldfolder/(oldid+'-result.txt')).read_text();task=api[id]['task']
            pair=dict(stage=suffix,beforeApi=stats(expected,beforeText),afterApi=dict(status=task['status'],error=task['errorCode'],downloadReady=False,text=None,rawCER=None,qualityPassed=False),
                nativeText=native,nativeNumbersExact=numeric_tokens(native)==numeric_tokens(expected),outsidePromotedMasks=parity)
            if name!='marked':assert pair['nativeNumbersExact'] and not pair['beforeApi']['exact'],(name,pair)
            pairs.append(pair)
            if suffix=='edited-office':
                unedited,_=raster(after/(name+'-office-result.pdf'));edited=parts(after/(name+'-edited-office-edited.docx'));editroot=E.fromstring(edited['word/document.xml'])
                assert {k:v for k,v in new.items() if k!='word/document.xml'}=={k:v for k,v in edited.items() if k!='word/document.xml'}
                expectedroot=copy.deepcopy(newroot);oldvalue='12' if name=='split' else case['sourceValue'];newvalue='56' if name=='split' else case['editValue']
                changed=[n for n in expectedroot.iter(W+'t') if oldvalue in (n.text or '')];assert len(changed)==1
                changed[0].text=changed[0].text.replace(oldvalue,newvalue);assert E.tostring(expectedroot)==E.tostring(editroot)
                editshapes=[n for n in editroot.iter(V+'rect') if newvalue in ''.join(t.text or '' for t in n.iter(W+'t'))]
                assert len(editshapes)==1;editbox=pixelbox(box(editshapes[0]));editparity=outside(unedited,newpix,[editbox]);assert editparity['exact'],(name,editparity)
                row['onlyIntendedEditableValueChanged']=True;row['outsideEditedWord']=editparity
                newpix.save(dest/(name+'-edited-visible.png'))
                oldregion=[min(m[0] for m in promoted),min(m[1] for m in promoted)-1,max(m[2] for m in promoted),max(m[3] for m in promoted)+2]
                region=list(oldregion);region[2]=max(region[2],box(editshapes[0])[2])
                crop=dest/(name+'-amount.png');output=dest/(name+'-amount')
                runtime=Path(os.environ['FORMAT_CONVERTER_APP_HOME'])/'ocr'
                command=[str(runtime/'bin/tesseract'),str(crop),str(output),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm','6']
                started=time.monotonic()
                cached=ROOT/'qa-samples/work/iteration33-visible';prior=cached/(name+'-amount.png');priorText=cached/(name+'-amount.txt')
                reused=False
                if prior.exists() and priorText.exists() and numeric_tokens(priorText.read_text())==numeric_tokens(case['editValue']):
                    with Image.open(prior) as im:cachedPixels=im.convert('RGB').tobytes();cachedSize=im.size
                    currentCrop=newpix.crop(pixelbox(oldregion))
                    if cachedSize==currentCrop.size and cachedPixels==currentCrop.tobytes():
                        for suffix in ['.png','.txt','.log','.receipt.json']:shutil.copyfile(cached/(name+'-amount'+suffix),dest/(name+'-amount'+suffix))
                        reused=True;region=oldregion
                if not reused:
                    newpix.crop(pixelbox(region)).save(crop)
                    with (dest/(name+'-amount.log')).open('x') as log:
                        with ManagedProcess(command,receipt=dest/(name+'-amount.receipt.json'),stdout=log,stderr=subprocess.STDOUT) as p:assert p.process.wait(timeout=30)==0
                elapsed=time.monotonic()-started;visible=output.with_suffix('.txt').read_text()
                row['visibleAmountOcr']=dict(text=visible,seconds=None if reused else elapsed,cacheReuseSeconds=elapsed if reused else None,
                    metrics=stats('Amount '+case['editValue']+'\n',visible),numbersExact=numeric_tokens(visible)==numeric_tokens(case['editValue']),
                    reusedPixelIdenticalCompletedOcr=reused,cropPixels=pixelbox(region),cropSha256=sha(crop))
                assert row['visibleAmountOcr']['numbersExact'],(name,visible)
        row['officeApi']=pairs
        if name=='marked':
            row.update(qualitySupported=False,originalMarkPreservedOutsideMasks=True,unsupportedReason='Colored annotation blocks reliable per-word paper sampling; preserved scan can still produce duplicate text.')
        else:row.update(wordMaskQualitySupported=True,apiQualitySupported=False,apiUnsupportedReason='Strict OCR_NO_NEW_TEXT: remaining native-backed raster has no novel recognized text; completeness is not proven.')
        rows.append(row)
    regressions=[]
    for c in truth['regressions']:
        name=c['id'];actual=(after/(name+'-regression-result.txt')).read_text();assert actual==c['expected']
        warning=[w for w in api[name+'-regression']['task']['warnings'] if w['code']=='OCR_RECOGNITION_CONFLICT'];assert len(warning)==int(c['conflictWarning'])
        regressions.append(dict(id=name,rawTextExact=True,numbersExact=True,conflictWarningPreserved=bool(warning)))
    result=dict(parentRevision='4c6217978aa264b4ddbcba7c99e502c8a606ff07',beforeJarSha256=a['jarSha256'],jarSha256=b['jarSha256'],
        frozenManifestSha256=a['manifestSha256'],finalManifestSha256=b['manifestSha256'],cases=rows,regressions=regressions,
        httpBefore=20,httpCandidate=31,httpAfter=31,existingOriginalWordOverlayFailuresFixed=2,independentWordOverlayFailuresFixed=6,
        exactWordOfficeVisibleAmountOutcomes=8,exactWordOfficeApiOutcomes=0,interfaceSuccessAfter=22,boundedStrictFailuresAfter=9,
        unsupported=['8 supported Word masks have strict PDF→TXT OCR_NO_NEW_TEXT/no artifact; completeness gate unchanged','same-position native/OCR conflict retains both values and warning','marked paper retains uncertain raster and strict OCR_VISIBILITY_UNCERTAIN/no artifact'],
        workersAbsent=82,newZombies=[],ECHILD=True,pixelDpi=300,pixelBoundaryAllowance=2,renderLibrary=fitz.VersionBind,
        resources=dict(before=a['resources'],candidate=candidate['resources'],after=b['resources']),
        common20RequestSeconds=dict(before=sum(c['seconds'] for c in a['cases']),final=sum(c['seconds'] for c in b['cases'] if c['case'] in {v['case'] for v in a['cases']}),differentSuccessSemantics=True),
        helpers={n:sha(ROOT/'qa-samples'/n) for n in ['generate_mixed_masks33.py','generate_mixed_masks33_final.py','verify_mixed_masks33.py','run_edit_iteration26.py','qa_process_guard.py','qa_http_deadline.py']},
        originalSparseOcrNotRerun=True,nativeInstallersNotRun=True)
    (ROOT/'docs/cloud-mixed-masks33-results.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps({k:result[k] for k in ['httpBefore','httpAfter','existingOriginalWordOverlayFailuresFixed','independentWordOverlayFailuresFixed','workersAbsent','unsupported']},ensure_ascii=False))
if __name__=='__main__':main()
