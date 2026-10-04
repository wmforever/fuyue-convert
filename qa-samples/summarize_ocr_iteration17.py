#!/usr/bin/env python3
"""Verify OFD image warning propagation with exact paired downloaded artifacts."""
import collections,hashlib,io,json,pathlib,xml.etree.ElementTree as ET,zipfile
import fitz
from PIL import Image
from verify_cloud_ocr import metrics
from verify_partial_word_preservation import shapes,signature
from summarize_ocr_iteration15 import classes
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    before=ROOT/'qa-samples/report/iteration17-before';after=ROOT/'qa-samples/report/iteration17-after'
    a=load(before/'report.json');b=load(after/'report.json')
    assert a['status']==b['status']=='completed'
    assert len(a['cases'])==len(b['cases'])==len(a['observedWorkerPids'])==len(b['observedWorkerPids'])==13
    report={'sourceParent':'2fe947d156b34339735de3078dd047ec665e8267','before':a,'after':b,
        'word':[],'metrics':[],'warningChanges':[], 'scope':'Warning correctness only; no OCR accuracy/editability/speed gain claim'}
    truth=load(ROOT/'qa-samples/generated/cloud-iteration15/expected.json')['expectedLines']
    handoff=load(ROOT/'qa-samples/generated/cloud-handoff/expected.json')['cases']
    bilingual=[line for name in ['english-tilt-+0.png','chinese-tilt-+0.png']
        for line in next(c for c in handoff if c['file']==name)['expectedLines']]
    for old,new in zip(a['cases'],b['cases']):
        assert (old['file'],old['target'],old['contract'])==(new['file'],new['target'],new['contract'])
        assert old['success']==new['success']==True
        for field in ['text','editableText','originalMediaSha256']:assert old.get(field)==new.get(field),(old['file'],field)
        assert old['task']['files']==new['task']['files']
        supplemental=[w for w in new['task']['warnings'] if w not in old['task']['warnings']]
        assert [w for w in new['task']['warnings'] if w not in supplemental]==old['task']['warnings']
        expected={'incomplete.ofd':(1,1),'complete.ofd':(1,0),'mixed.ofd':(2,1)}.get(old['file'],(0,0))
        for code,count in zip(['OCR_IMAGE_ENHANCED','OCR_POSSIBLE_TEXT_OMISSION'],expected):
            found=[w for w in supplemental if w['code']==code];assert len(found)==count,(old['file'],code,found)
            assert all(w['pageNumber']==1 and w['message'].startswith('OFD 第 1 页图片 ') for w in found)
            if code=='OCR_POSSIBLE_TEXT_OMISSION':
                assert all(w['message'].startswith('OFD 第 1 页图片 1') and '未采用' not in w['message'] for w in found)
        assert len(supplemental)==sum(expected),(old['file'],supplemental)
        if supplemental:report['warningChanges'].append({'file':old['file'],'target':old['target'],'added':supplemental})
        if old['target'] in ['txt','docx'] and old['file'].endswith('.ofd'):
            lines=truth if old['file']!='bilingual.ofd' else bilingual
            if old['file']=='mixed.ofd':lines=truth+truth
            report['metrics'].append({'file':old['file'],'target':old['target'],
                'beforeAfter':metrics('\n'.join(lines),old.get('text',old.get('editableText','')))})
        if old['target']=='docx':
            f,m,media=shapes(before/old['artifact']);nf,nm,nmedia=shapes(after/new['artifact'])
            assert collections.Counter(map(signature,f))==collections.Counter(map(signature,nf))
            assert collections.Counter(map(signature,m))==collections.Counter(map(signature,nm))
            assert media==nmedia and len(f)>0 and len(m)>0
            sources=[ROOT/'qa-samples/generated/cloud-iteration15/accepted-incomplete.png']
            if old['file']=='bilingual.ofd':sources=[ROOT/'qa-samples/generated/cloud-handoff'/name for name in ['english-tilt-+0.png','chinese-tilt-+0.png']]
            source_pixels=[]
            for source in sources:
                with Image.open(source) as image:source_pixels.append(hashlib.sha256(image.convert('RGB').tobytes()).hexdigest())
            with zipfile.ZipFile(after/new['artifact']) as z:
                actual=[]
                for member in media:
                    with Image.open(io.BytesIO(z.read(member))) as image:actual.append(hashlib.sha256(image.convert('RGB').tobytes()).hexdigest())
                assert all(value in actual for value in source_pixels)
            report['word'].append({'file':old['file'],'editableFrames':len(f),'masks':len(m),'sourcePixelsFramesMasksExact':True})
        if old['target']=='pdf':
            with fitz.open(before/old['artifact']) as x,fitz.open(after/new['artifact']) as y:
                assert len(x)==len(y)
                for one,two in zip(x,y):
                    assert one.get_text('words')==two.get_text('words')
                    assert one.get_pixmap(alpha=False).samples==two.get_pixmap(alpha=False).samples
    old=classes(ROOT/'qa-samples/work/iteration17-before.jar');new=classes(ROOT/'web-api/target/web-api-0.1.5.jar')
    assert old.keys()==new.keys();changed=[p for p in old if old[p]!=new[p]]
    assert changed==['task-service/com/fuyue/formatconverter/task/OfdOcrSupport.class'],changed
    totals=collections.Counter();skips=[]
    for xml in ROOT.glob('*/target/surefire-reports/TEST-*.xml'):
        tree=ET.parse(xml).getroot()
        for key in ['tests','failures','errors','skipped']:totals[key]+=int(tree.attrib[key])
        for case in tree.findall('testcase'):
            if case.find('skipped') is not None:skips.append({'class':tree.attrib['name'],'test':case.attrib['name'],'message':case.find('skipped').attrib.get('message')})
    assert totals=={'tests':419,'failures':0,'errors':0,'skipped':1},totals
    report.update(changedClasses=changed,applicationClassCount=len(new),tests=dict(totals),skips=skips,
        provenance=load(ROOT/'qa-samples/work/iteration17-build-provenance.json'),
        reproduction=load(ROOT/'qa-samples/work/iteration17-fresh-reproduction.json'),
        fixtureManifest=load(ROOT/'qa-samples/generated/cloud-iteration17/expected.json'))
    report['provenance'].pop('buildInputs',None)
    pinned=load(ROOT/'docs/cloud-ocr-iteration14-results.json')
    report['runtime']={key:pinned[key] for key in ['versions','hashes']}
    (ROOT/'docs/cloud-ocr-iteration17-results.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print(json.dumps({'contracts':26,'workers':26,'word':report['word'],'tests':dict(totals),'changedClasses':changed,'metrics':report['metrics']},ensure_ascii=False))

if __name__=='__main__':main()
