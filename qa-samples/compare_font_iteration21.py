"""Separate native Unicode, Word font requests, installed fonts and Office output."""
import collections,hashlib,json,pathlib,re,statistics,xml.etree.ElementTree as ET,zipfile
import fitz
from fontTools.ttLib import TTFont
from summarize_word_iteration19 import W,compact
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def root_xml(p):
    with zipfile.ZipFile(p) as z:return ET.fromstring(z.read('word/document.xml'))
def flag(properties,key):
    n=properties.find(W+key) if properties is not None else None
    return n is not None and n.attrib.get(W+'val','true') not in ['off','false','0']
def runs(path):
    result=[]
    for r in root_xml(path).iter(W+'r'):
        text=''.join(n.text or '' for n in r.iter(W+'t'))
        if not text:continue
        prop=r.find(W+'rPr');fonts=prop.find(W+'rFonts')
        result.append({'text':text,'ascii':fonts.get(W+'ascii'),'eastAsia':fonts.get(W+'eastAsia'),
                       'bold':flag(prop,'b'),'italic':flag(prop,'i')})
    return result
def office_span_fonts(path):
    with fitz.open(path) as doc:
        return sorted({span['font'] for page in doc for block in page.get_text('dict')['blocks']
                       if 'lines' in block for line in block['lines'] for span in line['spans']})
def office_fonts(path):
    with fitz.open(path) as doc:
        return sorted({re.sub(r'^[A-Z]{6}\+','',font[3]) for page in doc for font in page.get_fonts()})
def main():
    corpus=ROOT/'qa-samples/generated/font-iteration21';truth=load(corpus/'expected.json')
    folders=[ROOT/'qa-samples/report/iteration21-before',ROOT/'qa-samples/report/iteration21-after']
    quality=[load(folder/'quality.json') for folder in folders];results=[]
    for case,before,after in zip(truth['cases'],quality[0]['cases'],quality[1]['cases']):
        name=pathlib.Path(case['file']).stem;records=[runs(f/(name+'-word.docx')) for f in folders]
        checks=[]
        for item in case['items']:
            font=truth['fonts'][item['font']];expected=case.get('explicitDescriptorFamily',font['family'])
            found=[r for r in records[1] if compact(r['text'])==compact(item['text'])];assert len(found)==1,(name,item['text'])
            row=found[0];assert row['ascii']==expected,(name,row,expected)
            style=font['subfamily'].lower();assert row['bold']==('bold' in style) and row['italic']==('italic' in style or 'oblique' in style),(name,row)
            checks.append({'text':item['text'],'sourceFontKey':item['font'],'expectedFamily':expected,'actualWordRun':row})
        for measured,folder in zip([before,after],folders):
            assert measured['wordOrderExact'] and measured['wordCharacterInventoryExact'] and measured['officeCharacterInventoryExact'],name
            assert measured['sourcePages']==measured['officePages']==1 and all(measured['nonblankOfficePages'])
            assert collections.Counter(compact((folder/(name+'-text.txt')).read_text(encoding='utf-8-sig')))==collections.Counter(compact(case['expectedText'])),name
        expected_rows=len({(item['page'],item['baseline']) for item in case['items']})
        assert before['bodyParagraphs']==after['bodyParagraphs']==expected_rows,name
        assert after['officeMetric']['cer']<=before['officeMetric']['cer'],name
        assert after['httpTextMetric']['cer']<=before['httpTextMetric']['cer'],name
        assert collections.Counter(before['wordNumericSurfaces'])==collections.Counter(after['wordNumericSurfaces'])
        actual_fonts=[office_fonts(f/(name+'-office.pdf')) for f in folders]
        if name in ['serif-faces','mono-faces','symbols-dejavu']:
            expected=set()
            for item in case['items']:
                ttf=TTFont(truth['fonts'][item['font']]['path'],fontNumber=0);expected.add(ttf['name'].getDebugName(6));ttf.close()
            assert set(actual_fonts[1])==expected,(name,actual_fonts[1],expected)
        unchanged=False
        if name=='unavailable-family':
            assert records[0]==records[1]
            with fitz.open(folders[0]/(name+'-office.pdf')) as a,fitz.open(folders[1]/(name+'-office.pdf')) as b:
                assert a[0].get_pixmap(alpha=False).samples==b[0].get_pixmap(alpha=False).samples
            unchanged=True
        results.append({'file':case['file'],'wordRunsBefore':records[0],'wordRunsAfter':records[1],'fontChecks':checks,
                        'officeFontsBefore':actual_fonts[0],'officeFontsAfter':actual_fonts[1],
                        'officeSpanDisplayFontsBefore':office_span_fonts(folders[0]/(name+'-office.pdf')),
                        'officeSpanDisplayFontsAfter':office_span_fonts(folders[1]/(name+'-office.pdf')),
                        'wordCerBefore':before['wordMetric']['cer'],'wordCerAfter':after['wordMetric']['cer'],
                        'officeCerBefore':before['officeMetric']['cer'],'officeCerAfter':after['officeMetric']['cer'],
                        'httpCerBefore':before['httpTextMetric']['cer'],'httpCerAfter':after['httpTextMetric']['cer'],
                        'allCharactersAndDigitsConserved':True,'unavailableControlPixelExact':unchanged,
                        'geometryBefore':before['geometry'],'geometryAfter':after['geometry'],
                        'matchedLinesBefore':before['matchedSourceLines'],'matchedLinesAfter':after['matchedSourceLines'],
                        'wordParagraphsBefore':before['bodyParagraphs'],'wordParagraphsAfter':after['bodyParagraphs'],'sourceRows':expected_rows,
                        'medianMaxBoxShiftPtBefore':statistics.median(g['maxShiftPt'] for g in before['geometry']),
                        'medianMaxBoxShiftPtAfter':statistics.median(g['maxShiftPt'] for g in after['geometry'])})
    # Compare original parser blocks, allowing only font family/style changes.
    native=[load(ROOT/('qa-samples/work/iteration21-native-'+label+'.json')) for label in ['before','after']]
    for a,b in zip(*native):
        assert a['file']==b['file']
        for x,y in zip(a['parsed']['pages'],b['parsed']['pages']):
            assert len(x['textBlocks'])==len(y['textBlocks'])
            for one,two in zip(x['textBlocks'],y['textBlocks']):
                assert {k:v for k,v in one['style'].items() if k not in ['family','bold','italic']}=={k:v for k,v in two['style'].items() if k not in ['family','bold','italic']}
                one={k:v for k,v in one.items() if k!='style'};two={k:v for k,v in two.items() if k!='style'}
                assert one==two,(a['file'],'native geometry/content changed')
    expected=truth['cases'][0]['expectedText'].replace('00731','00739',1);out=folders[1]
    edited=root_xml(out/'serif-faces-edited.docx');assert compact(''.join(t.text or '' for t in edited.iter(W+'t')))==compact(expected)
    with fitz.open(out/'serif-faces-edited-office.pdf') as d:
        assert len(d)==1 and compact(''.join(p.get_text() for p in d))==compact(expected)
    assert compact((out/'serif-faces-edited-text.txt').read_text())==compact(expected)
    report={'parentRevision':'bc34be353aba56eb99db15ea9f72a5405b7c3932','manifest':truth,'cases':results,
            'before':quality[0]['http'],'after':quality[1]['http'],'originalNativeGeometryAndTextExact':True,
            'edit':{'replacement':['00731','00739'],'occurrences':1,'wordOfficeAndApiTextExact':True,'pages':1},
            'limits':['Installed cloud fonts verified separately from embedded font metadata; correct requests do not guarantee fonts exist on another machine',
                      'Bundled Droid CJK fallback remains intentional and unchanged; no source-perfect CJK font claim',
                      'No new font programs redistributed in production; corpus license notices retained',
                      'Native Microsoft Word/platform installers unrun']}
    (ROOT/'docs/cloud-font-iteration21-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps([{k:v for k,v in c.items() if k not in ['wordRunsBefore','wordRunsAfter','fontChecks','geometryBefore','geometryAfter']} for c in results],ensure_ascii=False,indent=2))
if __name__=='__main__':main()
