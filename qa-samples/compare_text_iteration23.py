#!/usr/bin/env python3
"""Paired text-only acceptance; tagged intent and ambiguous legacy controls stay distinct."""
import collections,hashlib,json,pathlib,statistics,xml.etree.ElementTree as ET,zipfile
import fitz
from verify_cloud_ocr import metrics
from qa_evidence_guards import align_cases,require_native_geometry
ROOT=pathlib.Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def load(p):return json.loads(p.read_text())
def compact(s):return ''.join(s.split())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def xml(path):
 with zipfile.ZipFile(path) as z:return ET.fromstring(z.read('word/document.xml'))
def stable_word_parts(path):
 with zipfile.ZipFile(path) as z:parts={n:z.read(n) for n in z.namelist() if n!='docProps/core.xml'}
 if 'word/fontTable.xml' in parts:
  table=ET.fromstring(parts['word/fontTable.xml']);keys=[n for n in table.iter() if W+'fontKey' in n.attrib];assert len(keys)==1
  key=bytes.fromhex(keys[0].attrib.pop(W+'fontKey').strip('{}').replace('-',''))
  font=bytearray(parts['word/fonts/DroidSansFallback.odttf'])
  for i in range(32):font[i]^=key[15-i%16]
  assert bytes(font)==(ROOT/'task-service/src/main/resources/fonts/DroidSansFallback.ttf').read_bytes()
  parts['word/fonts/DroidSansFallback.odttf']=bytes(font);parts['word/fontTable.xml']=ET.tostring(table)
 return {n:hashlib.sha256(v).hexdigest() for n,v in parts.items()}
def main():
 corpus=ROOT/'qa-samples/generated/text-iteration23';truth=load(corpus/'expected.json');folders=[ROOT/'qa-samples/report/iteration23-before',ROOT/'qa-samples/report/iteration23-after'];raw=[load(p/'report.json') for p in folders]
 names=[c['file'] for c in truth['cases']];native=[load(ROOT/('qa-samples/work/iteration23-native-'+stage+'.json')) for stage in ['before','after']]
 require_native_geometry(truth['cases'],*native)
 # No font-field exception is needed for this batch: all original blocks must match too.
 for a,b in zip(align_cases(names,native[0],'before native'),align_cases(names,native[1],'after native'),strict=True):assert a['parsed']==b['parsed'] and a['analyzed']==b['analyzed']
 results=[]
 for case in truth['cases']:
  name=pathlib.Path(case['file']).stem;expected=case['expectedText'];texts=[(f/(name+'-native-text.txt')).read_text(encoding='utf-8-sig') for f in folders];values=[metrics(expected,t) for t in texts]
  for t in texts:assert collections.Counter(compact(t))==collections.Counter(compact(expected)),name
  changed=case['tagged']
  if changed:assert values[0]['cer']>0 and compact(texts[1])==compact(expected),(name,values)
  else:
   assert texts[0]==texts[1],name
   assert (folders[0]/(name+'-native-text.txt')).read_bytes()==(folders[1]/(name+'-native-text.txt')).read_bytes(),name
  if case.get('blankPages'):assert all(t.count('\f')==case['pages']-1 for t in texts)
  row={'file':case['file'],'before':values[0],'after':values[1],'beforeText':texts[0],'afterText':texts[1],
       'characterInventoryExactIgnoringWhitespace':True,'unchangedUntaggedBytes':not changed,'orderAssessment':case.get('orderAssessment','explicit-tagged-groups' if changed else 'control')}
  if case.get('wordControl'):
   roots=[xml(f/(name+'-word.docx')) for f in folders];assert ET.tostring(roots[0])==ET.tostring(roots[1]),name
   strings=[''.join(n.text or '' for n in r.iter(W+'t')) for r in roots]
   assert all(collections.Counter(compact(s))==collections.Counter(compact(expected)) for s in strings)
   wordparts=[stable_word_parts(f/(name+'-word.docx')) for f in folders]
   assert wordparts[0]==wordparts[1],name
   with fitz.open(folders[0]/(name+'-office.pdf')) as a,fitz.open(folders[1]/(name+'-office.pdf')) as b:
    assert len(a)==len(b)==case['pages']
    for x,y in zip(a,b,strict=True):
     assert x.get_pixmap(alpha=False).samples==y.get_pixmap(alpha=False).samples,name
     assert x.get_text('words')==y.get_text('words'),name
   office_text=[(f/(name+'-text.txt')).read_text(encoding='utf-8-sig') for f in folders]
   for t in office_text:assert collections.Counter(compact(t))==collections.Counter(compact(expected))
   row.update(wordXmlAndPartsExactExceptCoreTimestampAndVerifiedFontKey=True,officePixelsAndWordBoxesExact=True,
              officeApiCer=[metrics(expected,t)['cer'] for t in office_text],wordLogicalCer=[metrics(expected,s)['cer'] for s in strings])
   assert row['officeApiCer'][1]<=row['officeApiCer'][0]
   if case['kind']=='table':
    cells=[[''.join(n.text or '' for n in cell.iter(W+'t')) for cell in row.findall(W+'tc')] for table in roots[1].iter(W+'tbl') for row in table.findall(W+'tr')];assert cells==case['cells'];row['tableCellsExact']=True
  results.append(row)
 for report in raw:
  assert report['status']=='completed' and not report['failures'] and not report['newZombies']
  assert len(report['cases'])==len(report['workerIdentities'])==14
  expected_contracts={(pathlib.Path(c['file']).stem,s) for c in truth['cases'] for s in (['native-text','word','office','text'] if c.get('wordControl') else ['native-text'])}
  assert {(c['case'],c['stage']) for c in report['cases']}==expected_contracts
 out={'parentRevision':'b11b15ef89f27139ab75303eb0c975a235d1361f','manifestSha256':sha(corpus/'expected.json'),'manifest':truth,'cases':results,
      'http':[{k:r[k] for k in ['jarSha256','manifestSha256','status','helperSha256','resources','newZombies']} for r in raw],
      'allNativeBlocksAndAnalyzedModelsExact':True,'limits':['CER normalizes whitespace and Latin case; independent inventory ignores whitespace; unchanged controls compare actual TXT bytes',
      'Only supported complete author-declared paragraph groups are used; no whitespace/heading/table intent inference',
      'Ambiguous untagged ledger order is retained rather than claimed correct','Raw Office extraction/Word/geometry remain unchanged; only API TXT section order changes','Native Word/macOS/Windows package acceptance unrun']}
 (ROOT/'docs/cloud-text-iteration23-results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps([{'file':r['file'],'cer':[r['before']['cer'],r['after']['cer']],'officeApiCer':r.get('officeApiCer'),'unchanged':r['unchangedUntaggedBytes']} for r in results],ensure_ascii=False,indent=2))
if __name__=='__main__':main()
