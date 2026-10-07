#!/usr/bin/env python3
"""Exact fallback/order and numeric-token checks for the frozen adversarial corpus."""
from pathlib import Path
import collections,hashlib,json,re,xml.etree.ElementTree as ET
import fitz
from compare_text_iteration23 import stable_word_parts,xml,W
from qa_evidence_guards import align_cases,require_native_geometry
from verify_cloud_ocr import metrics
ROOT=Path(__file__).resolve().parents[1]
def compact(s):return ''.join(s.split())
def numeric_tokens(s):return collections.Counter(re.findall(r'[+-]?(?:\d+(?:[.,:/-]\d+)*|\.\d+)',s))
def negative_controls():
 pairs=[('12 34','1234'),('ID000571','ID571'),('-742.63','742.63'),('+0.47','0.47'),('2035-09-17','20350917'),('1,308.56','1308.56'),('.47','47'),('12.30','12 .30')]
 for a,b in pairs:assert numeric_tokens(a)!=numeric_tokens(b),(a,b)
 assert numeric_tokens('12 34')==numeric_tokens('12\n34')
 return {'rejectedNumericBoundaryOrSurfaceChanges':pairs,'acceptedNumericWhitespaceOnly':['12 34','12\n34'],'exactOutputComparisonAlsoRequired':True}
def main():
 guards=negative_controls();corpus=ROOT/'qa-samples/generated/text-iteration24';m=json.loads((corpus/'expected.json').read_text());folders=[ROOT/'qa-samples/report/iteration24-before',ROOT/'qa-samples/report/iteration24-after'];raw=[json.loads((f/'report.json').read_text()) for f in folders];probes=[{c['file']:c for c in json.loads((ROOT/f'qa-samples/work/iteration24-{s}-probe.json').read_text())} for s in ['before','after']]
 native=[json.loads((ROOT/f'qa-samples/work/iteration24-native-{s}.json').read_text()) for s in ['before','after']];require_native_geometry(m['cases'],*native);names=[c['file'] for c in m['cases']]
 for a,b in zip(align_cases(names,native[0],'before'),align_cases(names,native[1],'after'),strict=True):assert a['parsed']==b['parsed'] and a['analyzed']==b['analyzed']
 out=[];negative=[]
 for c in m['cases']:
  name=Path(c['file']).stem;expected=c['expectedText'];texts=[(f/(name+'-native-text.txt')).read_text(encoding='utf-8-sig') for f in folders]
  for i,t in enumerate(texts):
   assert collections.Counter(compact(t))==collections.Counter(compact(expected)),name
   assert numeric_tokens(t)==numeric_tokens(expected),name
   assert t==probes[i][c['file']]['tagged'],name
   assert t.count('\f')==c['pages']-1,name
  assert texts[1]==expected,name
  if c['expectFallback']:assert texts[1]==probes[1][c['file']]['legacy'],name
  else:assert texts[0]==texts[1]==expected and texts[1]!=probes[1][c['file']]['legacy'],name
  if texts[0]!=expected:negative.append(name)
  row={'file':c['file'],'before':metrics(expected,texts[0]),'after':metrics(expected,texts[1]),'beforeText':texts[0],'afterText':texts[1],'exactFinalTruth':True,'inventoryAndNumericTokensExact':True,'legacyFallbackRequired':c['expectFallback'],'originalNativeGeometryExact':True}
  if c.get('wordControl'):
   roots=[xml(f/(name+'-word.docx')) for f in folders];assert ET.tostring(roots[0])==ET.tostring(roots[1]);assert stable_word_parts(folders[0]/(name+'-word.docx'))==stable_word_parts(folders[1]/(name+'-word.docx'))
   for r in roots:
    text=''.join(n.text or '' for n in r.iter(W+'t'));assert collections.Counter(compact(text))==collections.Counter(compact(expected))
   with fitz.open(folders[0]/(name+'-office.pdf')) as a,fitz.open(folders[1]/(name+'-office.pdf')) as b:
    assert len(a)==len(b)==c['pages']
    for x,y in zip(a,b,strict=True):assert x.get_pixmap(alpha=False).samples==y.get_pixmap(alpha=False).samples and x.get_text('words')==y.get_text('words')
   ot=[(f/(name+'-text.txt')).read_text(encoding='utf-8-sig') for f in folders];assert ot[0]==ot[1]==expected
   row.update(wordXmlAndVerifiedStablePartsExact=True,officePixelsAndWordBoxesExact=True,officeApiTextExact=True)
  out.append(row)
 assert negative==['artifact-nested','duplicate-content-id'],negative
 contracts={(Path(c['file']).stem,s) for c in m['cases'] for s in (['native-text','word','office','text'] if c.get('wordControl') else ['native-text'])}
 for r in raw:
  assert r['status']=='completed' and not r['failures'] and not r['newZombies'];assert len(r['cases'])==len(r['workerIdentities'])==15
  assert {(c['case'],c['stage']) for c in r['cases']}==contracts
 result={'parentRevision':m['parentRevision'],'manifestSha256':hashlib.sha256((corpus/'expected.json').read_bytes()).hexdigest(),'manifest':m,'numericBoundaryChecks':guards,'observedFailuresBeforeFix':negative,'cases':out,'http':[{k:r[k] for k in ['jarSha256','manifestSha256','status','helperSha256','resources','newZombies']} for r in raw],'limits':['Small bounded synthetic malformed inputs; no arbitrary stress/DoS benchmark','Numeric lexical surfaces retained including sign/grouping/date separators; not a semantic numeric parser','Artifact metadata never removes visible text; unsupported cases keep legacy order','Native package/Word acceptance unrun; prior font/OCR limitations remain']}
 (ROOT/'docs/cloud-text-iteration24-results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'cases':len(out),'beforeFallbackDefects':negative,'afterExactTruth':len(out),'httpContracts':30,'numericNegativeControls':8}))
if __name__=='__main__':main()
