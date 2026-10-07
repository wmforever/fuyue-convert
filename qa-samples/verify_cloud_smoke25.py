#!/usr/bin/env python3
"""Audit latest integration results, failures, text/numbers, edits and scan pixels."""
from pathlib import Path
import collections,copy,hashlib,io,json,zipfile,xml.etree.ElementTree as ET
import fitz
from PIL import Image
from compare_text_iteration24 import numeric_tokens,negative_controls
from verify_cloud_ocr import metrics
from qa_process_guard import matches,snapshot
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def compact(s):return ''.join(s.split())
def pixels(data):
 with Image.open(io.BytesIO(data)) as im:return im.size,hashlib.sha256(im.convert('RGB').tobytes()).hexdigest()
def media(p):
 with zipfile.ZipFile(p) as z:return {n:z.read(n) for n in z.namelist() if n.startswith('word/media/')}
def document(p):
 with zipfile.ZipFile(p) as z:return ET.fromstring(z.read('word/document.xml'))
def main():
 corpus=ROOT/'qa-samples/generated/cloud-smoke25';m=json.loads((corpus/'expected.json').read_text());folders=[ROOT/'qa-samples/report/iteration25-smoke',ROOT/'qa-samples/report/iteration25-smoke-remaining'];reports=[json.loads((f/'report.json').read_text()) for f in folders];cases=[(f,c) for f,r in zip(folders,reports,strict=True) for c in r['cases']];assert [c['case'] for f,c in cases]==[a['id'] for a in m['actions']];assert len(cases)==20
 artifacts={c['case']:f/c['artifact'] for f,c in cases if 'artifact' in c};rows=[]
 for action,(folder,c) in zip(m['actions'],cases,strict=True):
  expected=m['truth'][action['truth']];row={'action':action['id'],'target':action['target'],'status':c['task']['status'],'seconds':c['seconds'],'errorCode':c['task'].get('errorCode'),'warnings':c['task']['warnings']}
  if c['task']['status']=='SUCCESS':
   p=artifacts[action['id']];assert sha(p)==c['sha256'];row['sha256']=sha(p)
   if action['target']=='txt':text=p.read_text(encoding='utf-8-sig')
   elif action['target']=='docx':text='\n'.join(n.text or '' for n in document(p).iter(W+'t'))
   elif action['target']=='pdf':
    with fitz.open(p) as d:
     assert len(d)==1;row['pages']=len(d);text='\n'.join(pg.get_text() for pg in d);row['rawPdfTextMetrics']=metrics(expected,text)
    # Image-only PDF deliberately has no native text; OFD rendering has separate fidelity semantics.
    if action['id'] in ['scan-pdf','digital-ofd-pdf']:text=None
   else:
    with zipfile.ZipFile(p) as z:assert 'OFD.xml' in z.namelist()
    text=None
   if text is not None:
    row['text']=text;row['metrics']=metrics(expected,text);row['characterInventoryExact']=collections.Counter(compact(text))==collections.Counter(compact(expected));row['numericLexemesExact']=numeric_tokens(text)==numeric_tokens(expected)
    row['orderExactIgnoringWhitespace']=compact(text)==compact(expected)
  rows.append(row)
 failures=[r for r in rows if r['status']!='SUCCESS'];assert [(r['action'],r['errorCode']) for r in failures]==[('digital-ofd-text','OCR_NO_NEW_TEXT'),('digital-ofd-word','OCR_NO_NEW_TEXT')]
 # Editing changes only one intended visible text run, retaining every other XML property and original media.
 edits=[]
 for folder,r in zip(folders,reports,strict=True):
  for e in r.get('edits',[]):
   action=next(a for a in m['actions'] if a['id']==e['action']);source=artifacts[action['input'][1:]];edited=folder/e['artifact'];a=document(source);b=document(edited);nodes=[n for n in a.iter(W+'t') if e['change']['old'] in (n.text or '')];assert len(nodes)==1;nodes[0].text=nodes[0].text.replace(e['change']['old'],e['change']['new']);assert ET.tostring(a)==ET.tostring(b);assert media(source)==media(edited);edits.append({'action':e['action'],'onlyIntendedTextChanged':True,'allGeometryStylesAndMediaUnchanged':True})
 scan_source=(corpus/'record-scan.png').read_bytes()
 with fitz.open(artifacts['scan-pdf']) as d:
  source_images=[d.extract_image(i[0])['image'] for i in d[0].get_images()];assert pixels(scan_source) in [pixels(v) for v in source_images]
 rendered=(ROOT/'qa-samples/work/iteration25-pdf-background.png').read_bytes();assert pixels(rendered) in [pixels(v) for v in media(artifacts['scan-word']).values()]
 with zipfile.ZipFile(artifacts['scan-ofd']) as z:
  images=[z.read(n) for n in z.namelist() if n.lower().endswith(('.png','.jpg','.jpeg'))];assert len(images)==1
 assert pixels(images[0]) in [pixels(v) for v in media(artifacts['scan-ofd-word']).values()]
 cleanup=[]
 for r in reports:
  assert r['health']['ocr']['bundled'] and r['health']['ocr']['available'];assert not r['newZombies'] and r['supervision']['waitpidNoChildren'];assert len(r['workerIdentities'])==len(r['cases']);assert not any(matches(x) for x in r['workerIdentities']);cleanup.append({'workersAbsent':len(r['workerIdentities']),'ECHILD':True,'newZombies':[]})
 old=json.load(open(ROOT/'qa-samples/work/iteration22-final-check.json'))['allCurrentZombieIdentities'];assert len([v for v in snapshot().values() if v['state']=='Z'])==17 and all(matches(v) for v in old)
 result={'parentRevision':m['parentRevision'],'manifestSha256':sha(corpus/'expected.json'),'manifest':m,'httpAttempts':20,'httpSuccess':18,'httpFailures':2,'cases':rows,'edits':edits,'numericNegativeControls':negative_controls(),'pixelChecks':{'originalPngPixelsInInputPdf':True,'unchangedPdfBox300DpiBackgroundInScanWord':True,'originalOfdRasterPixelsInScanOfdWord':True},'health':reports[0]['health'],'resources':[r['resources'] for r in reports],'cleanup':cleanup,'historicalZombiesUnchanged':17,'runnerHistory':{'initial':reports[0]['helperSha256'],'remaining':reports[1]['helperSha256'],'resumedFrom':reports[1]['resumedFrom']},'buildAudit':json.load(open(ROOT/'qa-samples/work/iteration25-build-audit.json')),'buildProvenance':json.load(open(ROOT/'qa-samples/work/iteration25-build-provenance.json'))};result['buildProvenance'].pop('buildInputs');result['qualityMismatches']=[r['action'] for r in rows if 'metrics' in r and not (r['characterInventoryExact'] and r['numericLexemesExact'] and r['orderExactIgnoringWhitespace'])]
 (ROOT/'docs/cloud-consolidation25-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'httpSuccess':18,'httpFailures':failures,'qualityMismatches':result['qualityMismatches'],'metrics':[(r['action'],r.get('metrics',{}).get('cer')) for r in rows],'pixelChecks':result['pixelChecks'],'resources':result['resources']},ensure_ascii=False))
if __name__=='__main__':main()
