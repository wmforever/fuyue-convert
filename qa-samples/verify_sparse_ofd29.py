#!/usr/bin/env python3
"""Freeze five finite sparse-OFD contracts; diagnose without weakening completeness."""
import argparse,hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1];NS='{http://www.ofdspec.org/2016}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['generate','verify']);a=p.parse_args();out=ROOT/'qa-samples/generated/sparse-ofd29';source=ROOT/'qa-samples/report/iteration25-smoke/digital-ofd-result.ofd'
 if a.mode=='generate':
  out.mkdir(exist_ok=False);sources={};actions=[]
  with zipfile.ZipFile(source) as z:parts={n:z.read(n) for n in z.namelist()}
  page='Doc_0/Pages/Page_0/Content.xml';base=E.fromstring(parts[page]);native=''.join(n.text or '' for n in base.iter(NS+'TextCode'))
  for mode in ['original','native-only','image-only','missing-native-amount']:
   data=dict(parts);r=E.fromstring(data[page])
   for parent in r.iter():
    for n in list(parent):
     remove=(mode=='native-only' and n.tag==NS+'ImageObject') or (mode=='image-only' and n.tag==NS+'TextObject') or (mode=='missing-native-amount' and n.tag==NS+'TextObject' and any(t in ''.join(n.itertext()) for t in ['金额','Amount']))
     if remove:parent.remove(n)
   if mode!='original':data[page]=E.tostring(r,encoding='utf-8',xml_declaration=True)
   path=out/(mode+'.ofd')
   if mode=='original':path.write_bytes(source.read_bytes())
   else:
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
     for n,b in data.items():z.writestr(n,b)
   sources[path.name]=sha(path);actions.append(dict(id=mode+'-txt',input=path.name,target='txt'))
   if mode=='original':actions.append(dict(id='original-word',input=path.name,target='docx'))
  truth=(ROOT/'qa-samples/generated/cloud-smoke25/record.txt').read_text()
  (out/'expected.json').write_text(json.dumps(dict(sourceSha256=sha(source),sources=sources,actions=actions,expected=truth,nativeText=native,helperSha256=sha(Path(__file__))),ensure_ascii=False,indent=2)+'\n');print(len(actions));return
 folder=ROOT/'qa-samples/report/iteration29-sparse-ofd';r=json.loads((folder/'report.json').read_text());m=json.loads((out/'expected.json').read_text());assert r['status']=='completed-with-failures' and len(r['cases'])==5
 rows=[]
 for c in r['cases']:
  expected_failure=c['case'].startswith('original-');t=c['task'];row=dict(case=c['case'],status=t['status'],errorCode=t['errorCode'],seconds=c['seconds'],downloadReady=t['downloadReady'])
  if expected_failure:assert t['status']=='FAILED' and t['errorCode']=='OCR_NO_NEW_TEXT' and not t['downloadReady'] and 'artifact' not in c
  else:
   assert t['status']=='SUCCESS' and t['downloadReady'];text=(folder/c['artifact']).read_text();row.update(text=text,metrics=metrics(m['expected'],text),numericLexemesExact=numeric_tokens(m['expected'])==numeric_tokens(text),warnings=t['warnings'])
  rows.append(row)
 assert not r['newZombies'] and r['supervision']['waitpidNoChildren'] and len(r['workerIdentities'])==5 and all(not matches(w) for w in r['workerIdentities'])
 result=dict(jarSha256=r['jarSha256'],sourceSha256=m['sourceSha256'],manifestSha256=sha(out/'expected.json'),rows=rows,nativeXmlMetrics=metrics(m['expected'],m['nativeText']),resources=r['resources'],allWorkersExited=True,ECHILD=True,newZombies=[],helperSha256=sha(Path(__file__)),decision='Retain OCR_NO_NEW_TEXT. Duplicate recognition/native agreement does not prove image completeness; no producer-metadata or confidence bypass.')
 (ROOT/'docs/cloud-sparse-ofd29-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
