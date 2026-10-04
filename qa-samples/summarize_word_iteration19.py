#!/usr/bin/env python3
"""Measure frozen source truth against downloaded Word and actual Office artifacts."""
import argparse,collections,hashlib,json,pathlib,re,xml.etree.ElementTree as ET,zipfile
import fitz
from verify_cloud_ocr import metrics
ROOT=pathlib.Path(__file__).resolve().parents[1]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def compact(s):return ''.join(s.split())
def numeric(s):return re.findall(r'(?<![\w.])[+-]?(?:\d+(?:,\d{3})*(?:\.\d+)?|\.\d+)(?!\w)',s)
def measure(folder):
    manifest=json.loads((ROOT/'qa-samples/generated/word-iteration19/expected.json').read_text());raw=json.loads((folder/'report.json').read_text());assert raw['status']=='completed' and not raw['failures']
    result={'http':raw,'cases':[]}
    for c in manifest['cases']:
        name=pathlib.Path(c['file']).stem;expected=c['expectedText'];word=folder/(name+'-word.docx')
        with zipfile.ZipFile(word) as z:
            root=ET.fromstring(z.read('word/document.xml'));texts=[t.text or '' for t in root.iter(W+'t')];text=''.join(texts)
            table_cells=[[''.join(t.text or '' for t in cell.iter(W+'t')) for cell in row.findall(W+'tc')] for table in root.iter(W+'tbl') for row in table.findall(W+'tr')]
            paras=[''.join(t.text or '' for t in p.iter(W+'t')) for p in root.find(W+'body').findall(W+'p') if ''.join(t.text or '' for t in p.iter(W+'t')).strip()]
            frames=[n for tag in ['{urn:schemas-microsoft-com:vml}shape','{urn:schemas-microsoft-com:vml}rect'] for n in root.iter(tag) if n.find('{urn:schemas-microsoft-com:vml}textbox') is not None]
            images=[n for n in z.namelist() if n.startswith('word/media/')]
        actual_txt=(folder/(name+'-text.txt')).read_text(encoding='utf-8-sig')
        with fitz.open(folder/(name+'-office.pdf')) as d,fitz.open(ROOT/'qa-samples/generated/word-iteration19'/c['file']) as src:
            pdf_text=''.join(p.get_text() for p in d);geometry=[]
            for item in c['items']:
                index=item['page']-1;source=src[index].search_for(item['text']);found=d[index].search_for(item['text']) if index<len(d) else []
                if source and found:
                    a=source[0];b=found[0]
                    for rect in source[1:]:a|=rect
                    for rect in found[1:]:b|=rect
                    geometry.append({'page':index+1,'text':item['text'],'sourceBox':list(a),'officeBox':list(b),'maxShiftPt':max(abs(x-y) for x,y in zip(a,b))})
            rec={'file':c['file'],'kind':c['kind'],'sourcePages':len(src),'officePages':len(d),'nonblankOfficePages':[bool(p.get_text().strip()) for p in d],
                 'wordText':text,'officeText':pdf_text,'httpText':actual_txt,'wordMetric':metrics(expected,text),'officeMetric':metrics(expected,pdf_text),'httpTextMetric':metrics(expected,actual_txt),
                 'wordCharacterInventoryExact':collections.Counter(compact(expected))==collections.Counter(compact(text)),
                 'officeCharacterInventoryExact':collections.Counter(compact(expected))==collections.Counter(compact(pdf_text)),
                 'wordOrderExact':compact(expected)==compact(text),'officeOrderExact':compact(expected)==compact(pdf_text),
                 'bodyParagraphs':len(paras),'editableFrames':len(frames),'mediaCount':len(images),'tableCells':table_cells,
                 'expectedNumericSurfaces':numeric(expected),'wordNumericSurfaces':numeric(' '.join(texts)),'matchedSourceLines':len(geometry),'sourceLines':len(c['items']),'geometry':geometry}
            if c['kind']=='table':rec['tableCellsExact']=table_cells==c['cells']
            result['cases'].append(rec)
    (folder/'quality.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps([{'file':c['file'],'wordCER':c['wordMetric']['cer'],'officeCER':c['officeMetric']['cer'],'wordInventory':c['wordCharacterInventoryExact'],'officeInventory':c['officeCharacterInventoryExact'],'wordOrder':c['wordOrderExact'],'officeOrder':c['officeOrderExact'],'pages':[c['sourcePages'],c['officePages']],'frames':c['editableFrames'],'paras':c['bodyParagraphs'],'cells':c.get('tableCellsExact')} for c in result['cases']],ensure_ascii=False))
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--folder',type=pathlib.Path,required=True);a=p.parse_args();measure(a.folder)
