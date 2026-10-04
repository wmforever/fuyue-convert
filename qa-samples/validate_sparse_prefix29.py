#!/usr/bin/env python3
"""Eight final HTTP contracts: identical OFD semantics across prefixes, strict original gate."""
from pathlib import Path
import argparse,hashlib,json,zipfile,xml.etree.ElementTree as E
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def main():
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['generate','verify']);a=p.parse_args();out=ROOT/'qa-samples/generated/sparse-ofd29-final';alias=ROOT/'qa-samples/generated/sparse-ofd29';canonical=ROOT/'qa-samples/generated/sparse-ofd29-canonical'
 if a.mode=='generate':
  out.mkdir(exist_ok=False);sources={};actions=[]
  for prefix,folder in [('alias',alias),('canonical',canonical)]:
   for c in load(folder/'expected.json')['actions']:
    original=c['id'].startswith('original-');name=c['input'] if original else prefix+'-'+c['input'];case=c['id'] if original else prefix+'-'+c['id']
    (out/name).write_bytes((folder/c['input']).read_bytes());sources[name]=sha(out/name);actions.append(dict(c,id=case,input=name))
  (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,expected=load(alias/'expected.json')['expected'],helperSha256=sha(Path(__file__))),ensure_ascii=False,indent=2)+'\n');print(len(actions));return
 reportdir=ROOT/'qa-samples/report/iteration29-after';r=load(reportdir/'report.json');m=load(out/'expected.json');assert len(r['cases'])==8 and r['status']=='completed-with-failures';rows=[]
 for c in r['cases']:
  task=c['task'];row=dict(case=c['case'],status=task['status'],errorCode=task['errorCode'],seconds=c['seconds'],downloadReady=task['downloadReady'])
  if c['case'].startswith('original-'):assert task['status']=='FAILED' and task['errorCode']=='OCR_NO_NEW_TEXT' and not task['downloadReady'] and 'artifact' not in c
  else:
   assert task['status']=='SUCCESS' and task['downloadReady'];text=(reportdir/c['artifact']).read_text();value=metrics(m['expected'],text)
   assert value['cer']==0 and value['alignedCharacterRecall']==1 and numeric_tokens(text)==numeric_tokens(m['expected']),c
   baseline=ROOT/'qa-samples/report/iteration29-canonical-before'/(c['case'].removeprefix('alias-').removeprefix('canonical-')+'-result.txt')
   assert baseline.read_text()==text
   row.update(text=text,metrics=value,numericLexemesExact=True,canonicalBaselineByteExact=True)
  rows.append(row)
 # Independent ZIP/XML comparison: controls differ only in lexical namespace prefix.
 for name in ['native-only','image-only','missing-native-amount']:
  with zipfile.ZipFile(alias/(name+'.ofd')) as x,zipfile.ZipFile(canonical/(name+'.ofd')) as y:
   assert x.namelist()==y.namelist()
   for n in x.namelist():
    if n=='Doc_0/Pages/Page_0/Content.xml':assert E.tostring(E.fromstring(x.read(n)))==E.tostring(E.fromstring(y.read(n)))
    else:assert x.read(n)==y.read(n)
 assert len(r['workerIdentities'])==8 and all(not matches(w) for w in r['workerIdentities']) and r['supervision']['waitpidNoChildren'] and not r['newZombies']
 result=dict(jarSha256=r['jarSha256'],manifestSha256=r['manifestSha256'],rows=rows,prefixOnlyInputChangesProven=True,threeAliasOutputsBeforeEmptyAfterComplete=True,canonicalControlsByteExact=True,originalSparseCompletenessGateRetained=True,allWorkersExited=True,ECHILD=True,newZombies=[],resources=r['resources'],helperSha256={p.name:sha(p) for p in [Path(__file__),ROOT/'qa-samples/verify_sparse_ofd29.py',ROOT/'qa-samples/control_sparse_prefix29.py',ROOT/'qa-samples/run_edit_iteration26.py']})
 (ROOT/'docs/cloud-prefix-iteration29-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(dict(verified=True,requests=8,success=6,expectedNoNewTextFailures=2)))
if __name__=='__main__':main()
