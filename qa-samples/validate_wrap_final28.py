#!/usr/bin/env python3
"""Minimal final sparse-page guard acceptance, reusing the completed 19-request matrix."""
import argparse,hashlib,io,json,zipfile,subprocess
from pathlib import Path
import fitz
from verify_edit_iteration26 import parts
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def classes(p):
 out={}
 with zipfile.ZipFile(p) as z:
  for n in z.namelist():
   if n.endswith('.class'):out[n]=hashlib.sha256(z.read(n)).hexdigest()
   if n.startswith('BOOT-INF/lib/') and any(Path(n).name.startswith(m+'-') for m in ['layout-model','ofd-parser','table-recognizer','docx-renderer','task-service']):
    with zipfile.ZipFile(io.BytesIO(z.read(n))) as j:
     for c in j.namelist():
      if c.endswith('.class'):out[n+'/'+c]=hashlib.sha256(j.read(c)).hexdigest()
 return out
def main():
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['generate','verify']);a=p.parse_args();candidate=ROOT/'qa-samples/report/iteration28-after';corpus=ROOT/'qa-samples/generated/wrap-iteration28/final'
 if a.mode=='generate':
  corpus.mkdir(exist_ok=False);prior=ROOT/'qa-samples/generated/wrap-iteration28/after';m=load(prior/'expected.json');sources={};actions=[]
  def source(name,p):
   (corpus/name).write_bytes(p.read_bytes());sources[name]=sha(p);return name
  for c in m['actions']:
   if c['target']=='docx':actions.append(dict(c,input=source(c['input'],prior/c['input'])))
  actions.extend([dict(id='original-office',input='@original-word',target='pdf'),dict(id='original-edited-office',input='@original-word',target='pdf',edit=dict(old='7.50',new='187.50')),dict(id='original-text',input='@original-edited-office',target='txt')])
  for n in ['white','negative','zeros']:actions.append(dict(id=n+'-text',input=source(n+'-edited.pdf',candidate/(n+'-edited-office-result.pdf')),target='txt'))
  (corpus/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,candidateReportSha256=sha(candidate/'report.json'),helperSha256=sha(Path(__file__))),indent=2)+'\n');print(len(actions));return
 final=ROOT/'qa-samples/report/iteration28-final';r=load(final/'report.json');assert r['status']=='completed' and len(r['cases'])==12
 assert r['manifestSha256']==sha(corpus/'expected.json') and not r['newZombies'] and r['supervision']['waitpidNoChildren']
 assert len(r['workerIdentities'])==12 and all(not matches(w) for w in r['workerIdentities'])
 for n in ['original','white','negative','zeros','blocked','dark']:
  old,new=parts(candidate/(n+'-word-result.docx')),parts(final/(n+'-word-result.docx'))
  assert {k:v for k,v in old.items() if k!='docProps/core.xml'}=={k:v for k,v in new.items() if k!='docProps/core.xml'},n
 for stage in ['office','edited-office']:
  with fitz.open(candidate/('original-'+stage+'-result.pdf')) as a,fitz.open(final/('original-'+stage+'-result.pdf')) as b:
   assert len(a)==len(b)==1 and a[0].get_text('words')==b[0].get_text('words')
   assert a[0].get_pixmap(dpi=300,alpha=False).samples==b[0].get_pixmap(dpi=300,alpha=False).samples
 for n in ['original','white','negative','zeros']:assert (candidate/(n+'-text-result.txt')).read_bytes()==(final/(n+'-text-result.txt')).read_bytes()
 old,new=classes(ROOT/'qa-samples/work/iteration28-candidate.jar'),classes(ROOT/'web-api/target/web-api-0.1.5.jar');assert old.keys()==new.keys()
 changed=[n for n in old if old[n]!=new[n]]
 prefix='BOOT-INF/lib/docx-renderer-0.1.5.jar/com/fuyue/formatconverter/docx/FixedLayoutDocxRenderer'
 suffixes=['OcrMask','OcrAppearance','1','FixedItem']
 assert set(changed)=={prefix+'.class'}|{prefix+'$'+s+'.class' for s in suffixes},changed
 proofdir=ROOT/'qa-samples/work/iteration28-nested-class-proof';proofdir.mkdir(exist_ok=True);nested=[]
 for suffix in suffixes:
  texts=[];hashes={}
  for stage,jar in [('candidate',ROOT/'qa-samples/work/iteration28-candidate.jar'),('final',ROOT/'web-api/target/web-api-0.1.5.jar')]:
   name='FixedLayoutDocxRenderer$'+suffix;path=proofdir/(stage+'-'+name+'.class');dest=proofdir/(stage+'-'+suffix+'.txt')
   with zipfile.ZipFile(jar) as z:
    with zipfile.ZipFile(io.BytesIO(z.read('BOOT-INF/lib/docx-renderer-0.1.5.jar'))) as j:data=j.read('com/fuyue/formatconverter/docx/'+name+'.class')
   if path.exists():assert path.read_bytes()==data
   else:path.write_bytes(data)
   if not dest.exists():dest.write_bytes(subprocess.run(['javap','-c','-p',str(path)],capture_output=True,check=True,timeout=15).stdout)
   texts.append(dest.read_bytes());hashes[stage]=sha(path)
  assert texts[0]==texts[1],suffix
  nested.append(dict(name=suffix,codeAndSignaturesExact=True,disassemblySha256=hashlib.sha256(texts[0]).hexdigest(),classSha256=hashes))
 result=dict(jarSha256=r['jarSha256'],candidateJarSha256=load(candidate/'report.json')['jarSha256'],classesChanged=changed,nestedClassProof=nested,sixWordControlsPartsExceptCoreExact=True,originalUneditedAndEditedOfficePixelsAndNativeBoxesExact=True,fourAmountApiOutputsByteExact=True,httpRequests=12,allWorkersExited=True,ECHILD=True,newZombies=[],resources=r['resources'],manifestSha256=r['manifestSha256'],helperSha256=sha(Path(__file__)))
 (ROOT/'docs/cloud-wrap-final28-results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
