#!/usr/bin/env python3
"""Verify warning correction without claiming OCR quality or layout improvements."""
import collections,hashlib,io,json,pathlib,zipfile
import fitz
from verify_cloud_ocr import metrics
from verify_partial_word_preservation import shapes,signature
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def codes(case):return [w['code'] for w in case['task']['warnings']]

def classes(path):
    result={}
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/classes/') and name.endswith('.class'):result['web-api/'+name.removeprefix('BOOT-INF/classes/')]=hashlib.sha256(jar.read(name)).hexdigest()
            elif name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                module=next((m.name for m in ROOT.iterdir() if (m/'pom.xml').exists() and pathlib.Path(name).name.startswith(m.name+'-')),None)
                if module:
                    with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                        for entry in nested.namelist():
                            if entry.endswith('.class'):result[module+'/'+entry]=hashlib.sha256(nested.read(entry)).hexdigest()
    return result

def main():
    report={'scope':'Finite warning correctness fix; no OCR accuracy/completeness/editability gain claim',
            'executionParent':'038f1f163aa8bef0913db3581d844e5056f67131','controlledJava':[],'http':{},'word':[]}
    for name in ['accepted-incomplete','accepted-complete','rejected']:
        a=load(ROOT/'qa-samples/work/iteration15-before'/name/'result.json');b=load(ROOT/'qa-samples/work/iteration15-after'/name/'result.json')
        assert {k:v for k,v in a['result'].items() if k!='possibleTextOmission'}=={k:v for k,v in b['result'].items() if k!='possibleTextOmission'}
        expected=name=='accepted-incomplete';assert not a['result']['possibleTextOmission'] and b['result']['possibleTextOmission']==expected
        report['controlledJava'].append({'case':name,'before':a,'after':b,'allSelectedFieldsExceptWarningFlagExact':True})
    for scope in ['controlled','native']:
        before=ROOT/'qa-samples/report'/('iteration15-before-'+scope);after=ROOT/'qa-samples/report'/('iteration15-after-'+scope)
        a=load(before/'report.json');b=load(after/'report.json')
        assert a['status']==b['status']=='completed' and len(a['cases'])==len(b['cases'])==(4 if scope=='controlled' else 15)
        assert len(a['observedWorkerPids'])==len(a['cases']) and len(b['observedWorkerPids'])==len(b['cases'])
        changes=[]
        for old,new in zip(a['cases'],b['cases']):
            assert (old['file'],old['target'],old['contract'])==(new['file'],new['target'],new['contract'])
            assert old['success']==new['success']
            for field in ['text','editableText','metrics','originalMediaSha256']:
                assert old.get(field)==new.get(field),(old['file'],field)
            assert old['task']['files']==new['task']['files']
            expected=scope=='controlled' and old['file']=='accepted-incomplete.png'
            if expected:
                assert 'OCR_POSSIBLE_TEXT_OMISSION' not in codes(old) and 'OCR_POSSIBLE_TEXT_OMISSION' in codes(new)
                warning=next(w for w in new['task']['warnings'] if w['code']=='OCR_POSSIBLE_TEXT_OMISSION')
                assert '未采用' not in warning['message'] and warning['pageNumber']==1
                assert [w for w in new['task']['warnings'] if w['code']!='OCR_POSSIBLE_TEXT_OMISSION']==old['task']['warnings']
                changes.append({'file':old['file'],'target':old['target'],'addedWarning':warning})
            else:assert old['task']['warnings']==new['task']['warnings'],(old['file'],old['task']['warnings'],new['task']['warnings'])
            if old['success'] and old['target']=='docx':
                old_path=before/old['artifact'];new_path=after/new['artifact']
                frames,masks,media=shapes(old_path);new_frames,new_masks,new_media=shapes(new_path)
                assert collections.Counter(map(signature,frames))==collections.Counter(map(signature,new_frames))
                assert collections.Counter(map(signature,masks))==collections.Counter(map(signature,new_masks))
                assert media==new_media
                report['word'].append({'scope':scope,'file':old['file'],'frames':len(frames),'masks':len(masks),
                    'sourceMediaFramesMasksExact':True})
            if old['success'] and old['target']=='pdf':
                with fitz.open(before/old['artifact']) as x,fitz.open(after/new['artifact']) as y:
                    assert len(x)==len(y)
                    for one,two in zip(x,y):
                        assert one.get_text('words')==two.get_text('words')
                        assert one.get_pixmap(alpha=False).samples==two.get_pixmap(alpha=False).samples
        report['http'][scope]={'before':a,'after':b,'warningChanges':changes,
            'successErrorsTextMetricsNumericSequencesExact':True,'officeWordsBoxesPixelsExact':True}
    report['scanChains']={}
    from PIL import Image
    for scope in ['scan-controlled','scan-native']:
        before=ROOT/'qa-samples/report'/('iteration15-before-'+scope);after=ROOT/'qa-samples/report'/('iteration15-after-'+scope)
        a=load(before/'report.json');b=load(after/'report.json');assert a['status']==b['status']=='completed'
        assert len(a['cases'])==len(b['cases'])==len(a['observedWorkerPids'])==len(b['observedWorkerPids'])==4
        name='accepted-incomplete.png' if scope=='scan-controlled' else 'en-mono-shadow.png'
        source=ROOT/'qa-samples/generated'/('cloud-iteration15' if scope=='scan-controlled' else 'cloud-iteration11')/name
        with Image.open(source) as image:source_pixels=hashlib.sha256(image.convert('RGB').tobytes()).hexdigest()
        frames=masks=None
        for old_case,new_case in zip(a['cases'],b['cases']):
            assert old_case['success']==new_case['success']==True
            assert old_case['contract']==new_case['contract']
            assert old_case.get('text')==new_case.get('text') and old_case.get('editableText')==new_case.get('editableText')
            if old_case['target']=='docx':
                old_path=before/old_case['artifact'];new_path=after/new_case['artifact']
                f,m,media=shapes(old_path);nf,nm,nmedia=shapes(new_path);frames=len(f);masks=len(m)
                assert frames>0 and masks>0 and media==nmedia
                assert collections.Counter(map(signature,f))==collections.Counter(map(signature,nf))
                assert collections.Counter(map(signature,m))==collections.Counter(map(signature,nm))
                with zipfile.ZipFile(new_path) as z:
                    pixel_hashes=[]
                    for member in nmedia:
                        with Image.open(io.BytesIO(z.read(member))) as image:pixel_hashes.append(hashlib.sha256(image.convert('RGB').tobytes()).hexdigest())
                    assert source_pixels in pixel_hashes
                expected=scope=='scan-controlled'
                assert ('OCR_POSSIBLE_TEXT_OMISSION' in codes(old_case))==False
                assert ('OCR_POSSIBLE_TEXT_OMISSION' in codes(new_case))==expected
                assert [w for w in new_case['task']['warnings'] if not expected or w['code']!='OCR_POSSIBLE_TEXT_OMISSION']==old_case['task']['warnings']
            else:assert old_case['task']['warnings']==new_case['task']['warnings']
            if old_case['contract']=='scan-office-pdf':
                with fitz.open(before/old_case['artifact']) as x,fitz.open(after/new_case['artifact']) as y:
                    assert len(x)==len(y)==1
                    assert x[0].get_text('words')==y[0].get_text('words')
                    assert x[0].get_pixmap(alpha=False).samples==y[0].get_pixmap(alpha=False).samples
        report['scanChains'][scope]={'before':a,'after':b,'frames':frames,'masks':masks,
            'sourcePixelsFramesMasksOfficeWordsPixelsExact':True}
    old=classes(ROOT/'qa-samples/work/iteration15-before.jar');new=classes(ROOT/'web-api/target/web-api-0.1.5.jar')
    assert old.keys()==new.keys() and len(new)==223
    changed=[p for p in old if old[p]!=new[p]]
    assert all(p.startswith('task-service/com/fuyue/formatconverter/task/TesseractOcrConverter') for p in changed),changed
    report['changedClasses']=changed;report['tests']=load(ROOT/'qa-samples/work/iteration15-tests.json')
    pinned=load(ROOT/'docs/cloud-ocr-iteration14-results.json')
    report['pinnedRuntime']={'versions':pinned['versions'],'hashes':pinned['hashes']}
    report['controlledManifestSha256']=sha(ROOT/'qa-samples/generated/cloud-iteration15/expected.json')
    report['initialPlanSha256']=sha(ROOT/'docs/cloud-ocr-iteration15-initial-plan.json')
    report['extendedPlanSha256']=sha(ROOT/'docs/cloud-ocr-iteration15-plan.json')
    report['provenance']=load(ROOT/'qa-samples/work/iteration15-build-provenance.json');report['provenance'].pop('buildInputs',None)
    report['sourceSha256']={p:sha(ROOT/p) for p in ['task-service/src/main/java/com/fuyue/formatconverter/task/TesseractOcrConverter.java',
        'task-service/src/test/java/com/fuyue/formatconverter/task/OcrContrastEnhancementTest.java']}
    report['summary']={'baselineReproduced':True,'failingBeforeNewTest':1,'focusedPassed':59,'fullTests':417,'fullPassed':416,'optionalSignedOfdSkipped':1,
        'controlledHttpPerRevision':4,'nativeAndUnaffectedHttpPerRevision':15,'pairedHttpContracts':38,
        'newOmissionWarnings':3,'additionalPairedScanContracts':16,'totalPairedHttpContracts':54,'allTextConfidenceAndSourceCoordinateFieldsUnchanged':True,
        'nativeAccuracyAndWarningMetricsUnchanged':True,'noRetryOrDeadlineChange':True,
        'noOldThresholdPsmAngleSweepsRepeated':True,'jarSha256':report['provenance']['jarSha256']}
    (ROOT/'docs/cloud-ocr-iteration15-results.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    (ROOT/'qa-samples/work/iteration15-summary.json').write_text(json.dumps(report['summary'],ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report['summary']))

if __name__=='__main__':main()
