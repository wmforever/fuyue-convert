#!/usr/bin/env python3
"""Verify finite mixed-script evidence without treating confidence or font names as completeness."""
import collections,hashlib,json,pathlib,statistics,xml.etree.ElementTree as ET,zipfile
import fitz
from compare_font_iteration21 import office_fonts,flag,root_xml
from summarize_word_iteration19 import W,compact
from qa_evidence_guards import align_cases,require_native_geometry
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def runs(path):
    result=[]
    for run in root_xml(path).iter(W+'r'):
        text=''.join(t.text or '' for t in run.iter(W+'t'))
        if not text:continue
        prop=run.find(W+'rPr');font=prop.find(W+'rFonts') if prop is not None else None
        result.append({'text':text,'ascii':font.get(W+'ascii') if font is not None else None,
                       'eastAsia':font.get(W+'eastAsia') if font is not None else None,
                       'bold':flag(prop,'b'),'italic':flag(prop,'i')})
    return result
def glyphs(path,codepoints):
    result=[]
    with fitz.open(path) as doc:
        for p in doc:
            for span in p.get_texttrace():
                for value,gid,origin,box in span['chars']:
                    if value in codepoints:result.append({'character':chr(value),'font':span['font'],'gid':gid,'origin':origin,'box':box,'paintType':span['type'],'sequence':span['seqno']})
    return result

def main():
    corpus=ROOT/'qa-samples/generated/cjk-iteration22';truth=load(corpus/'expected.json')
    folders=[ROOT/'qa-samples/report/iteration22-before',ROOT/'qa-samples/report/iteration22-after']
    quality=[load(p/'quality.json') for p in folders];names=[c['file'] for c in truth['cases']]
    paired=[align_cases(names,q['cases'],'quality '+stage) for q,stage in zip(quality,['before','after'],strict=True)]
    native=[load(ROOT/('qa-samples/work/iteration22-native-'+stage+'.json')) for stage in ['before','after']]
    require_native_geometry(truth['cases'],*native)
    results=[];rare={0x20bb7,0x3400,0x20ac,0x2212}
    for case,before,after in zip(truth['cases'],*paired,strict=True):
        name=pathlib.Path(case['file']).stem;records=[runs(f/(name+'-word.docx')) for f in folders]
        for measured,folder in zip([before,after],folders,strict=True):
            assert measured['wordOrderExact'] and measured['wordCharacterInventoryExact'] and measured['officeCharacterInventoryExact'],name
            assert measured['sourcePages']==measured['officePages']==case['pages'] and all(measured['nonblankOfficePages'])
            assert collections.Counter(compact((folder/(name+'-text.txt')).read_text(encoding='utf-8-sig')))==collections.Counter(compact(case['expectedText'])),name
        assert before['bodyParagraphs']==after['bodyParagraphs'] and before['editableFrames']==after['editableFrames']
        assert before['wordNumericSurfaces']==after['wordNumericSurfaces']
        assert after['officeMetric']['cer']<=before['officeMetric']['cer'],name
        assert after['httpTextMetric']['cer']<=before['httpTextMetric']['cer'],name
        changed=name in ['cid-cff-regular','cid-cff-bold']
        if changed:
            for r in records[0]:assert r['ascii']=='QACjkExportFace' and not r['bold']
            for r in records[1]:
                assert r['ascii']=='Noto Sans CJK JP' and r['eastAsia']=='Droid Sans Fallback'
                assert r['bold']==(name=='cid-cff-bold') and not r['italic']
        else:
            assert records[0]==records[1],name
            with fitz.open(folders[0]/(name+'-office.pdf')) as a,fitz.open(folders[1]/(name+'-office.pdf')) as b:
                assert len(a)==len(b)==case['pages']
                for x,y in zip(a,b,strict=True):assert x.get_pixmap(alpha=False).samples==y.get_pixmap(alpha=False).samples,name
        if case['kind']=='table':assert before['tableCellsExact'] and after['tableCellsExact'] and len(after['tableCells'])==4
        actual_fonts=[office_fonts(f/(name+'-office.pdf')) for f in folders]
        if changed:
            expected='NotoSansCJKjp-'+('Bold' if name=='cid-cff-bold' else 'Regular')
            assert expected in actual_fonts[1] and 'NotoSerif-Regular' not in actual_fonts[1]
        rare_glyphs=[glyphs(f/(name+'-office.pdf'),rare) for f in folders]
        expected_rare=collections.Counter(c for c in case['expectedText'] if ord(c) in rare)
        for observed in rare_glyphs:
            # Fill and stroke are distinct paint operations for Office's bold fallback.
            # Keep both in evidence; logical inventory is independently checked above.
            assert collections.Counter(g['character'] for g in observed if g['paintType']==0)==expected_rare
            assert all(g['paintType'] in [0,1] for g in observed)
            for g in observed:
                if g['paintType']==1:
                    assert any(f['paintType']==0 and f['sequence']==g['sequence']-1
                               and all(f[k]==g[k] for k in ['character','font','gid','origin','box']) for f in observed)
            assert all(g['gid']>0 for g in observed)
        results.append({'file':case['file'],'wordRunsBefore':records[0],'wordRunsAfter':records[1],
            'wordCer':[before['wordMetric']['cer'],after['wordMetric']['cer']],
            'officeCer':[before['officeMetric']['cer'],after['officeMetric']['cer']],
            'httpCer':[before['httpTextMetric']['cer'],after['httpTextMetric']['cer']],
            'officeFonts':actual_fonts,'rareGlyphsBefore':rare_glyphs[0],'rareGlyphsAfter':rare_glyphs[1],
            'sourceLines':len(case['items']),'matchedLines':[before['matchedSourceLines'],after['matchedSourceLines']],
            'medianMaxBoxShiftPt':[statistics.median(g['maxShiftPt'] for g in q['geometry']) if q['geometry'] else None for q in [before,after]],
            'geometryBefore':before['geometry'],'geometryAfter':after['geometry'],
            'pages':after['officePages'],'paragraphs':after['bodyParagraphs'],'editableFrames':after['editableFrames'],
            'tableCells':after['tableCells'],'allCharacterInventoriesExactIgnoringWhitespace':True,
            'controlPixelExact':not changed})
    name='cid-cff-bold';case=next(c for c in truth['cases'] if c['file']==name+'.pdf')
    expected=case['expectedText'].replace('00486','00489',1);folder=folders[1]
    assert compact(''.join(t.text or '' for t in root_xml(folder/(name+'-edited.docx')).iter(W+'t')))==compact(expected)
    assert compact((folder/(name+'-edited-text.txt')).read_text(encoding='utf-8-sig'))==compact(expected)
    with fitz.open(folder/(name+'-edited-office.pdf')) as d:
        assert len(d)==1
        assert collections.Counter(compact(''.join(p.get_text() for p in d)))==collections.Counter(compact(expected))
    contracts=[]
    for q,stage in zip(quality,['before','after'],strict=True):
        raw=q['http'];assert raw['status']=='completed' and not raw['newZombies'] and not raw['failures']
        assert len(raw['cases'])==len(raw['workerIdentities'])==(18 if stage=='before' else 20)
        contracts.append({k:raw[k] for k in ['jarSha256','manifestSha256','helperSha256','resources','status','newZombies']})
    result={'parentRevision':'4c215b3b581897933451cca9f209ee68da3c841d','manifestSha256':sha(corpus/'expected.json'),
            'runtime':load(ROOT/'qa-samples/work/iteration22-runtime.json'),'sourcePreflight':load(ROOT/'qa-samples/work/iteration22-source-preflight.json'),
            'manifest':truth,'cases':results,'http':contracts,'originalNativeTextAndGeometryExact':True,
            'edit':{'replacement':['00486','00489'],'occurrences':1,'wordAndApiTextExactIgnoringWhitespace':True,'rawOfficeInventoryExactIgnoringWhitespace':True,'pages':1},
            'limits':['CER normalizes whitespace and Latin case; inventory/order checks ignore whitespace; native block strings compare exact whitespace',
                      'Droid fallback lacks U+20BB7/U+3400/U+20AC/U+2212; host Noto fills missing glyphs here, not guaranteed on other machines',
                      'Raw mixed-script extraction and floating heading API order remain measured limitations',
                      'No OS2 fsSelection/head macStyle or localized/missing-subfamily improvement claimed',
                      'No native Microsoft Word/macOS/Windows installer acceptance']}
    (ROOT/'docs/cloud-cjk-iteration22-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps([{k:c[k] for k in ['file','wordCer','officeCer','httpCer','officeFonts','matchedLines','medianMaxBoxShiftPt','controlPixelExact']} for c in results],ensure_ascii=False,indent=2))
if __name__=='__main__':main()
