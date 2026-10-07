#!/usr/bin/env python3
"""Validate frozen HTTP artifacts and eight bounded visible amount OCR probes."""
from pathlib import Path
import hashlib,json,os,re,subprocess,time,xml.etree.ElementTree as E
import fitz
from PIL import Image,ImageChops,ImageDraw
from qa_process_guard import ManagedProcess,install_shutdown_handlers,matches
from verify_edit_iteration26 import parts
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';V='{urn:schemas-microsoft-com:vml}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 install_shutdown_handlers();out=ROOT/'qa-samples/work/iteration28-visible';out.mkdir(exist_ok=False)
 before=ROOT/'qa-samples/report/iteration28-before';after=ROOT/'qa-samples/report/iteration28-after';report=json.loads((after/'report.json').read_text());assert report['status']=='completed' and len(report['cases'])==19
 assert report['supervision']['waitpidNoChildren'] and not report['newZombies'] and len(report['workerIdentities'])==19 and all(not matches(r) for r in report['workerIdentities'])
 truth=json.loads((ROOT/'qa-samples/generated/wrap-iteration28/before/expected.json').read_text())
 original=dict(id='original',old='7.50',new='187.50',editedExpected='EDIT REVIEW 2041\nRecord 00571\nAmount 187.50\nDate 2041-08-23\nControl 38.25\n',blocked=False)
 rows=[];runtime=Path(os.environ['FORMAT_CONVERTER_APP_HOME'])/'ocr'
 for c in [original]+truth['cases']:
  name=c['id'];oldbase=ROOT/'qa-samples/report/iteration26-scan-after' if name=='original' else before;oldname='longer-gray' if name=='original' else name
  oldparts=parts(oldbase/(oldname+'-word-result.docx'));newparts=parts(after/(name+'-word-result.docx'))
  assert {k:v for k,v in oldparts.items() if k not in ['docProps/core.xml','word/document.xml']}=={k:v for k,v in newparts.items() if k not in ['docProps/core.xml','word/document.xml']},name
  oldroot=E.fromstring(oldparts['word/document.xml']);newroot=E.fromstring(newparts['word/document.xml']);changed=[]
  oldshapes={n.get('id'):n for n in oldroot.iter(V+'rect')};newshapes={n.get('id'):n for n in newroot.iter(V+'rect')};assert oldshapes.keys()==newshapes.keys()
  for key,new in newshapes.items():
   old=oldshapes[key]
   if old.get('style')!=new.get('style'):
    content=''.join(t.text or '' for t in new.iter(W+'t')).strip();assert re.fullmatch(r'[+-]?[0-9]{1,12}(?:\.[0-9]{1,6})?',content),content
    styles=[dict(t.split(':',1) for t in n.get('style').split(';') if ':' in t) for n in [old,new]]
    a,b=[float(s.pop('width').removesuffix('pt')) for s in styles];assert styles[0]==styles[1] and b>a and b-a<=2*72/25.4*15
    changed.append(dict(text=content,beforeWidthPt=a,afterWidthPt=b));new.set('style',old.get('style'))
  assert E.tostring(oldroot)==E.tostring(newroot),name
  with fitz.open(oldbase/(oldname+'-office-result.pdf')) as a,fitz.open(after/(name+'-office-result.pdf')) as b:
   assert len(a)==len(b)==1 and a[0].get_pixmap(dpi=300,alpha=False).samples==b[0].get_pixmap(dpi=300,alpha=False).samples,name
   assert a[0].get_text('words')==b[0].get_text('words'),name
  row=dict(case=name,onlyTransparentNumericWidthsChanged=changed,originalImageMasksFontsCoordinatesExact=True,uneditedOfficePixelsAndNativeBoxesExact=True)
  if c['blocked']:
   # Other safe numeric words may grow, but the amount beside the red mark cannot.
   assert all(x['text']!=c['old'] for x in changed);row['blockedAmountWidthUnchanged']=True;rows.append(row);continue
  assert any(x['text']==c['old'] for x in changed),name
  for stage,base,prefix in [('before',oldbase,oldname),('after',after,name)]:
   with fitz.open(base/(prefix+'-edited-office-result.pdf')) as d:
    page=d[0];native=page.get_text();pix=page.get_pixmap(dpi=300,alpha=False);image=Image.frombytes('RGB',(pix.width,pix.height),pix.samples)
   row[stage+'Native']=dict(text=native,metrics=metrics(c['editedExpected'],native),numericLexemesExact=numeric_tokens(native)==numeric_tokens(c['editedExpected']))
   region=(300,450,950,650);path=out/(name+'-'+stage+'.png');image.crop(region).save(path);dest=out/(name+'-'+stage);begin=time.monotonic()
   cmd=[str(runtime/'bin/tesseract'),str(path),str(dest),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm','6']
   with (out/(name+'-'+stage+'.log')).open('x') as log:
    with ManagedProcess(cmd,receipt=out/(name+'-'+stage+'.receipt.json'),stdout=log,stderr=subprocess.STDOUT) as process:assert process.process.wait(timeout=30)==0
   text=dest.with_suffix('.txt').read_text();row[stage+'VisibleAmount']=dict(text=text,seconds=time.monotonic()-begin,numericLexemesExact=numeric_tokens(text)==numeric_tokens(c['new']),metrics=metrics(c['new'],text))
   api=(before/'original-text-result.txt' if name=='original' and stage=='before' else base/(prefix+'-text-result.txt')).read_text()
   row[stage+'Api']=dict(text=api,metrics=metrics(c['editedExpected'],api),numericLexemesExact=numeric_tokens(api)==numeric_tokens(c['editedExpected']))
  assert not row['beforeNative']['numericLexemesExact'] and not row['beforeApi']['numericLexemesExact']
  assert row['afterNative']['numericLexemesExact'] and row['afterVisibleAmount']['numericLexemesExact'] and row['afterApi']['numericLexemesExact'],row
  assert re.search(r'^Amount '+re.escape(c['new'])+r'$',row['afterApi']['text'],re.M),row
  # Full document differences outside the edited number region are forbidden.
  with fitz.open(after/(name+'-office-result.pdf')) as a,fitz.open(after/(name+'-edited-office-result.pdf')) as b:
   ims=[]
   for page in [a[0],b[0]]:
    pix=page.get_pixmap(dpi=300,alpha=False);ims.append(Image.frombytes('RGB',(pix.width,pix.height),pix.samples))
   diff=ImageChops.difference(*ims);ImageDraw.Draw(diff).rectangle((300,450,950,650),fill=(0,0,0));assert diff.getbbox() is None
  row['editedPixelsOutsideAmountUnchanged']=True;rows.append(row)
 old=parts(ROOT/'qa-samples/report/iteration27-before/dark-word-result.docx');new=parts(after/'dark-word-result.docx');assert {k:v for k,v in old.items() if k!='docProps/core.xml'}=={k:v for k,v in new.items() if k!='docProps/core.xml'}
 result=dict(jarSha256=report['jarSha256'],manifestSha256=report['manifestSha256'],rows=rows,darkWordPartsExceptCoreExact=True,resources=dict(before=json.loads((before/'report.json').read_text())['resources'],after=report['resources']),httpRequests=19,allWorkersExited=True,newZombies=[],helperSha256={p.name:sha(p) for p in [Path(__file__),ROOT/'qa-samples/generate_wrap_iteration28.py',ROOT/'qa-samples/run_edit_iteration26.py']})
 (ROOT/'docs/cloud-wrap-iteration28-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(dict(verified=True,editedAmounts=4,visibleOcrCalls=8,blockedAndDarkControls=True)))
if __name__=='__main__':main()
