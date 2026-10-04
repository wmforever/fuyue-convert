"""Paired independent-column evidence; keep unresolved extraction errors visible."""
import collections,json,pathlib,xml.etree.ElementTree as ET,zipfile
import fitz
from summarize_word_iteration19 import W,compact
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def document(p):
    with zipfile.ZipFile(p) as z:return ET.fromstring(z.read('word/document.xml'))
def shapes(p):
    return list(document(p).iter('{urn:schemas-microsoft-com:vml}shape'))
def normalized_frames(p):
    result=[]
    for n in shapes(p):
        n.attrib.pop('id',None) # generated unique IDs may follow changed text order
        result.append(ET.tostring(n))
    return collections.Counter(result)
def main():
    checks=[];runs=[]
    for suffix in ['', '-region']:
        base=ROOT/('qa-samples/report/iteration20'+suffix+'-before')
        final=ROOT/('qa-samples/report/iteration20'+suffix+'-final')
        before=load(base/'quality.json');after=load(final/'quality.json');runs.append({'before':before['http'],'after':after['http']})
        for a,b in zip(before['cases'],after['cases']):
            assert a['file']==b['file'];name=pathlib.Path(a['file']).stem
            for c in [a,b]:
                assert c['wordCharacterInventoryExact'] and c['officeCharacterInventoryExact'],name
                assert c['officePages']==c['sourcePages']==1 and all(c['nonblankOfficePages'])
            assert collections.Counter(a['wordNumericSurfaces'])==collections.Counter(b['wordNumericSurfaces']),name
            assert a['tableCells']==b['tableCells'],name
            if name=='shifted-exact-regions':assert b['wordOrderExact'] and b['wordMetric']['cer']==0,name
            else:assert a['wordText']==b['wordText'],name
            record={'file':a['file'],'wordCerBefore':a['wordMetric']['cer'],'wordCerAfter':b['wordMetric']['cer'],
                    'officeCerBefore':a['officeMetric']['cer'],'officeCerAfter':b['officeMetric']['cer'],
                    'httpTextCerBefore':a['httpTextMetric']['cer'],'httpTextCerAfter':b['httpTextMetric']['cer'],
                    'charactersAndDigitsConserved':True,'pages':1,'framesBefore':a['editableFrames'],'framesAfter':b['editableFrames'],
                    'matchedSourceLinesBefore':a['matchedSourceLines'],'matchedSourceLinesAfter':b['matchedSourceLines'],
                    'maxSourceOfficeBoxShiftPtBefore':max((g['maxShiftPt'] for g in a['geometry']),default=None),
                    'maxSourceOfficeBoxShiftPtAfter':max((g['maxShiftPt'] for g in b['geometry']),default=None)}
            assert normalized_frames(base/(name+'-word.docx'))==normalized_frames(final/(name+'-word.docx')),name
            with fitz.open(base/(name+'-office.pdf')) as x,fitz.open(final/(name+'-office.pdf')) as y:
                assert collections.Counter(tuple(w[:5]) for w in x[0].get_text('words'))==collections.Counter(tuple(w[:5]) for w in y[0].get_text('words')),name
                assert x[0].get_pixmap(alpha=False).samples==y[0].get_pixmap(alpha=False).samples,name
            record['existingFramesStylesExactExceptGeneratedIds']=True;record['officePixelsAndWordBoxesExact']=True
            assert a['httpTextMetric']==b['httpTextMetric'],name
            checks.append(record)
    folder=ROOT/'qa-samples/report/iteration20-region-final';truth=load(ROOT/'qa-samples/generated/word-iteration20-region/expected.json')
    c=next(c for c in truth['cases'] if c['file']=='shifted-exact-regions.pdf');expected=c['expectedText'].replace('00731','00739',1)
    edited=folder/'shifted-exact-regions-edited.docx';actual=''.join(n.text or '' for n in document(edited).iter(W+'t'))
    assert compact(actual)==compact(expected)
    with fitz.open(folder/'shifted-exact-regions-edited-office.pdf') as pdf:
        assert len(pdf)==1 and collections.Counter(compact(''.join(p.get_text() for p in pdf)))==collections.Counter(compact(expected))
    assert collections.Counter(compact((folder/'shifted-exact-regions-edited-text.txt').read_text()))==collections.Counter(compact(expected))
    result={'parentRevision':'57d13a8450fb466ab6a4afa212f6c88cba5c55f8','checks':checks,'runs':runs,
            'edit':{'replacement':['00731','00739'],'wordOrderExact':True,'officeAndApiCharactersExact':True,'pages':1},
            'rejectedNarrowCandidate':'cloud-word-iteration20-rejected.json','limits':['Raw Office PDF text extraction order is measured separately, not claimed fixed',
                      'Unruled numeric ledger order remains unresolved',
                      'Narrow prose inference rejected after Office/API regressions; original narrow behavior retained',
                      'Native Microsoft Word and native installers unrun']}
    (ROOT/'docs/cloud-word-iteration20-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(checks,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
