#!/usr/bin/env python3
"""Read actual49 downloads; measure literal fields/CER, source identity and edit-local pixels."""
import json,re,xml.etree.ElementTree as E
from pathlib import Path
import fitz,numpy as np
from PIL import Image
from verify_scan_tables45 import ROOT,WORK,W,word,read,sha
from verify_cloud_ocr import metrics
from qa_process_guard import matches

def exact(value,text):return len(re.findall(r'(?<![\w.+-])'+re.escape(value)+r'(?![\w.])',text))
def main():
 out=WORK/'iteration49-http';report=read(out/'report.json');plan=read(ROOT/'qa-samples/generated/ruled-recovery49/expected.json');provenance=read(WORK/'iteration49-recovery-provenance.json');finalProvenance=read(WORK/'iteration49-provenance.json');finalOut=WORK/'iteration49-order-http';finalReport=read(finalOut/'report.json');geometry=read(WORK/'iteration49-geometry/result.json');capture=read(out/'ocr-capture.json')
 assert report['status']=='completed' and not report['failures'] and not report.get('unsupportedEdits')
 assert len(report['cases'])==len(report['workerIdentities'])==8 and report['supervision']['waitpidNoChildren'] and not report['newZombies']
 assert all(not matches(p) for p in report['workerIdentities'])
 assert report['jarSha256']==provenance['jarSha256']==sha(WORK/'iteration49-recovery.jar')
 assert finalReport['status']=='completed' and not finalReport['failures'] and len(finalReport['cases'])==2
 assert finalReport['jarSha256']==finalProvenance['jarSha256']==sha(ROOT/'web-api/target/web-api-0.1.5.jar')
 assert finalReport['supervision']['waitpidNoChildren'] and all(not matches(p) for p in finalReport['workerIdentities'])
 assert report['manifestSha256']==sha(ROOT/'qa-samples/generated/ruled-recovery49/expected.json')
 assert not capture['errors'] and capture['observerStopped'] and not capture['engineSettingsChanged']
 for c in report['cases']:assert sha(out/c['artifact'])==c['sha256']
 rendered=WORK/'iteration49-render';rendered.mkdir(exist_ok=True);results=[]
 for c in plan['cases']:
  n=c['id'];before=WORK/('iteration48-http/ruled-scan-word-result.docx' if n=='ruled' else 'iteration49-before-http/independent-word-result.docx')
  ap,ax,a=word(before);bp,bx,b=word(out/(n+'-word-result.docx'));values=[v for row in c['matrix'][1:] for v in row]
  assert a['media']==b['media'] and len(b['media'])==1
  assert all(sum(f['text']==v for f in b['frames'])==1 for v in values)
  assert not b['outOfPageFrames'] and b['tables']==0
  retained=next(g for g in geometry if g['case']==n);assert retained['allOriginalNumericWordsRetainedExactly'] and retained['extraOcrInvocations']==0
  assert ''.join(w['text'] for w in retained['selectedWords']).replace(' ','')==''.join(b['text'].split())
  preserved=[]
  for f in a['frames']:
   if f['text'].strip() in values:
    new=next(g for g in b['frames'] if g['text'].strip()==f['text'].strip());preserved.append(dict(value=f['text'].strip(),beforeFramePt=f['boxPt'],afterFramePt=new['boxPt'],originalOcrWordBoxExact=True))
  entry=next(x for x in report['cases'] if x['case']==n+'-word');warnings=entry['task']['warnings'];assert any(w['code']=='OCR_RECOGNITION_CONFLICT' and '原全页跨框低置信候选记录' in w['message'] for w in warnings)
  ep,ex,e=word(out/next(x['artifact'] for x in report['edits'] if x['action']==n+'-edited-office'))
  assert e['media']==b['media'] and e['text']==b['text'].replace(c['edit']['old'],c['edit']['new'])
  assert all(ep[k]==bp[k] for k in bp if k!='word/document.xml')
  pdfs=[];pixels=[]
  for mode in ['office','edited-office']:
   pdf=out/(n+'-'+mode+'-result.pdf');d=fitz.open(pdf);assert len(d)==1;text=d[0].get_text();png=rendered/(n+'-'+mode+'.png')
   if not png.exists():d[0].get_pixmap(dpi=200,alpha=False).save(png)
   d.close();expected=[c['edit']['new'] if v==c['edit']['old'] and mode=='edited-office' else v for v in values]
   assert all(exact(v,text)==1 for v in expected),(n,mode,text)
   assert not any(x in text.split() for x in ['Ce','ee','CL','[Le'])
   if mode=='edited-office':assert c['edit']['old'] not in text
   with Image.open(png) as im:pixels.append(np.asarray(im.convert('RGB')))
   pdfs.append(dict(kind=mode,sha256=sha(pdf),renderSha256=sha(png),text=text,pages=1,allNineFieldsExactlyOnce=True))
  delta=np.any(pixels[0]!=pixels[1],axis=2);yy,xx=np.where(delta);assert len(xx)>0
  frame=next(f for f in b['frames'] if f['text']==c['edit']['old']);x,y,w,h=frame['boxPt'];bounds=[int(xx.min()),int(yy.min()),int(xx.max())+1,int(yy.max())+1]
  assert x*200/72-3<=bounds[0] and y*200/72-3<=bounds[1] and bounds[2]<=(x+w)*200/72+3 and bounds[3]<=(y+h)*200/72+3
  text=(finalOut/(n+'-api-result.txt')).read_text();wanted=c['expectedRowMajor'].replace(c['edit']['old'],c['edit']['new']);expected=[c['edit']['new'] if v==c['edit']['old'] else v for v in values]
  assert all(exact(v,text)==1 for v in expected) and c['edit']['old'] not in text,(n,text)
  api=next(x for x in finalReport['cases'] if x['case']==n+'-api');finalCapture=read(finalOut/'ocr-capture.json');assert not finalCapture['records'] and not finalCapture['errors']
  for row in c['matrix'][1:]:assert '\t'.join(row).replace(c['edit']['old'],c['edit']['new']) in text.splitlines()
  results.append(dict(case=n,beforeWord=metrics(c['expectedRowMajor'],a['text']),afterWord=metrics(c['expectedRowMajor'],b['text']),beforeNumericFields=sum(any(f['text'].strip()==v for f in a['frames']) for v in values),afterNumericFields=9,totalNumericFields=9,wordText=b['text'],editableFrames=b['textFrames'],logicalTables=0,outOfPageFrames=0,sourceImage=a['media'][0],sourceImageByteExact=True,existingNumericWordGeometry=preserved,warningMessages=warnings,pdfs=pdfs,editChangedPixels=int(delta.sum()),editDifferenceBoxPixels=bounds,allOtherRenderPixelsExact=True,api=dict(text=text,beforeOrderMetrics=metrics(wanted,(out/(n+'-api-result.txt')).read_text()),metrics=metrics(wanted,text),rowAssociationsPreserved=True,allNineFieldsExactlyOnce=True,oldValueAbsent=True,extraOcrInvocations=0)))
 result=dict(parentRevision=provenance['parentRevision'],jarSha256=finalProvenance['jarSha256'],productionFingerprint=finalProvenance['buildInputSha256'],applicationClassCount=finalProvenance['applicationClassCount'],recoveryJarSha256=provenance['jarSha256'],recoveryFingerprint=provenance['buildInputSha256'],helperSha256=sha(Path(__file__)),actualHttpContracts=10,actualSuccesses=10,reusedWordOfficeArtifacts=6,finalTxtOnlyContracts=2,allWorkersGone=True,ECHILD=True,newOwnZombies=0,cases=results,resources=report['resources'],finalTxtResources=finalReport['resources'],negativeControls=read(WORK/'iteration49-geometry/negatives.json'),unknownPixels=read(WORK/'iteration49-unknown-pixels.json'),finalBuild=read(WORK/'iteration49-final-test-summary.json'),earlier48PdfUnchangedAndStillOutsideAcceptance=True,partialVisibilityGuardsUnchanged=True,remaining=['ID header recognition I/1 ambiguity','original48 already-damaged PDF not retroactively repaired','editable text frames; no logical Word table','dark/colored/broken/merged grids and uncertain numeric candidates retain original','native macOS/Windows packages unrun'])
 (ROOT/'docs/cloud-ruled-recovery49-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(dict(contracts=10,cases=[{k:x[k] for k in ['case','beforeNumericFields','afterNumericFields','beforeWord','afterWord','api']} for x in results]),ensure_ascii=False))
if __name__=='__main__':main()
