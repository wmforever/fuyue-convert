#!/usr/bin/env python3
"""Compare accepted before/after output; bound the document-order-only change."""
import collections,hashlib,json,pathlib,xml.etree.ElementTree as ET,zipfile
import fitz
from summarize_word_iteration19 import W,compact
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def frames(path):
    with zipfile.ZipFile(path) as z:root=ET.fromstring(z.read('word/document.xml'))
    return collections.Counter(ET.tostring(n) for n in root.iter('{urn:schemas-microsoft-com:vml}shape'))
def main():
    a=ROOT/'qa-samples/report/iteration19-before';b=ROOT/'qa-samples/report/iteration19-after'
    before=load(a/'quality.json');after=load(b/'quality.json');truth=load(ROOT/'qa-samples/generated/word-iteration19/expected.json')
    checks=[]
    for old,new in zip(before['cases'],after['cases']):
        assert old['file']==new['file'];name=pathlib.Path(old['file']).stem
        assert old['wordCharacterInventoryExact'] and new['wordCharacterInventoryExact']
        assert old['officeCharacterInventoryExact'] and new['officeCharacterInventoryExact']
        assert old['wordNumericSurfaces']==new['wordNumericSurfaces'] or name=='columns-wide'
        assert collections.Counter(old['wordNumericSurfaces'])==collections.Counter(new['wordNumericSurfaces'])
        assert old['tableCells']==new['tableCells']
        assert frames(a/(name+'-word.docx'))==frames(b/(name+'-word.docx')),'Floating text frame/style/coordinate changed'
        if name=='columns-wide':assert not old['wordOrderExact'] and new['wordOrderExact'] and new['wordMetric']['cer']==0
        else:assert old['wordText']==new['wordText'] and old['wordMetric']==new['wordMetric']
        pdf_checks=[]
        with fitz.open(a/(name+'-office.pdf')) as x,fitz.open(b/(name+'-office.pdf')) as y:
            assert len(x)==len(y)==old['sourcePages']
            for index,(one,two) in enumerate(zip(x,y),1):
                assert one.rect==two.rect
                first=collections.Counter(tuple(w[:5]) for w in one.get_text('words'))
                second=collections.Counter(tuple(w[:5]) for w in two.get_text('words'))
                assert first==second,(name,index,'word boxes')
                assert one.get_pixmap(alpha=False).samples==two.get_pixmap(alpha=False).samples,(name,index,'rendered pixels')
                pdf_checks.append({'page':index,'originalWordsAndBoxesExact':True,'renderedPixelsExact':True})
        checks.append({'file':old['file'],'wordCerBefore':old['wordMetric']['cer'],'wordCerAfter':new['wordMetric']['cer'],
                       'officeCerBefore':old['officeMetric']['cer'],'officeCerAfter':new['officeMetric']['cer'],
                       'httpTextCerBefore':old['httpTextMetric']['cer'],'httpTextCerAfter':new['httpTextMetric']['cer'],
                       'allCharactersAndNumericSurfacesConserved':True,'frameXmlExact':True,'office':pdf_checks})
    edits=[]
    for edit in after['http']['edits']:
        name=edit['case'];case=next(c for c in truth['cases'] if c['file']==name+'.pdf')
        with zipfile.ZipFile(b/edit['artifact']) as z:
            root=ET.fromstring(z.read('word/document.xml'));text=''.join(n.text or '' for n in root.iter(W+'t'))
        expected=case['expectedText']+edit['change']['append'] if 'append' in edit['change'] else case['expectedText'].replace('.95','.96')
        assert compact(text)==compact(expected)
        with fitz.open(b/(name+'-edited-office.pdf')) as pdf:
            actual=''.join(page.get_text() for page in pdf)
            assert compact(actual)==compact(expected),(name,'edited Office text')
            assert all(page.get_text().strip() for page in pdf)
            if name=='continuous-en':
                assert len(pdf)>case['pages']
                assert len([p for p in root.find(W+'body').findall(W+'p') if ''.join(n.text or '' for n in p.iter(W+'t')).strip()])==1
            else:
                assert len(pdf)==case['pages'];assert sum(n.text=='.96' for cell in root.iter(W+'tc') for n in cell.iter(W+'t'))==1
            assert compact((b/(name+'-edited-text.txt')).read_text())==compact(expected)
            edits.append({'case':name,'pagesBefore':case['pages'],'pagesAfter':len(pdf),'editableContentAndOfficeTextExact':True,'noBlankPages':True,'change':edit['change']})
    report={'sourceParent':'98b0ad77144a405f12bd4e5363c3342baa47cea3','before':before,'after':after,'pairedChecks':checks,'edits':edits,
            'truth':truth,'sourceChecks':load(ROOT/'qa-samples/work/iteration19-source-checks.json'),
            'focusedTests':load(ROOT/'qa-samples/work/iteration19-focused-results.json'),
            'workerAttribution':load(ROOT/'qa-samples/work/iteration19-worker-attribution.json'),
            'cleanPackageTests':load(ROOT/'qa-samples/work/iteration19-build-results.json'),
            'classDiff':load(ROOT/'qa-samples/work/iteration19-class-diff.json'),
            'runtimeRecheck':load(ROOT/'qa-samples/work/iteration19-runtime-recheck.json'),
            'limits':['Word XML/copy order improvement is separate from Office PDF extraction order',
                      'Narrow-gutter ambiguity unchanged; no general column threshold relaxation',
                      'Source-to-Office font/geometry differences remain; before/after equality is not source-perfect rendering',
                      'No native Microsoft Word or installer acceptance']}
    (ROOT/'docs/cloud-word-iteration19-results.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print(json.dumps({'paired':checks,'edits':[{k:v for k,v in e.items() if k!='change'} for e in edits]},ensure_ascii=False))
if __name__=='__main__':main()
