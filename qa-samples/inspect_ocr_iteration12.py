#!/usr/bin/env python3
"""Bound guard risk using actual bundled TSVs, Java strict geometry and frozen HTTP outputs."""
import argparse,hashlib,json,os,subprocess,time
from collections import Counter
from pathlib import Path
from verify_cloud_ocr import metrics
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--samples',type=Path,default=ROOT/'qa-samples/generated/cloud-iteration12');p.add_argument('--out',type=Path,default=ROOT/'qa-samples/work/iteration12-native');p.add_argument('--classpath',required=True);p.add_argument('--manifest-sha256',default='d3bc5e7ac0beb0777ea19332b218f84498ecc22f183ead6f841de15c8b645195');p.add_argument('--before-report',type=Path,default=ROOT/'qa-samples/report/iteration12-before');p.add_argument('--current-report',type=Path,default=ROOT/'qa-samples/report/iteration12-current');args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True);assert not (args.out/'results.json').exists(),'Preserve completed experiments'
 manifest=load(args.samples/'expected.json');assert hashlib.sha256((args.samples/'expected.json').read_bytes()).hexdigest()==args.manifest_sha256
 runtime=ROOT/'desktop/.runtime/ocr';before={c['file']:c for c in load(args.before_report/'report.json')['cases']};after={c['file']:c for c in load(args.current_report/'report.json')['cases']};records=[]
 for c in manifest['cases']:
  source=args.samples/c['file'];assert hashlib.sha256(source.read_bytes()).hexdigest()==c['sha256'];folder=args.out/c['file'];folder.mkdir(exist_ok=False)
  cmd=[str(runtime/'bin/tesseract'),str(source),str(folder/'original'),'--tessdata-dir',str(runtime/'tessdata'),'-l','chi_sim+eng','--psm','3','tsv'];start=time.monotonic()
  with (folder/'original.log').open('wb') as f:
   process=subprocess.Popen(cmd,stdout=f,stderr=subprocess.STDOUT)
   while True:
    pid,status,usage=os.wait4(process.pid,os.WNOHANG)
    if pid:break
    if time.monotonic()-start>120:process.kill();os.wait4(process.pid,0);raise TimeoutError('Native120s budget')
    time.sleep(.02)
   process.returncode=os.waitstatus_to_exitcode(status);assert process.returncode==0
  resource={'wallSeconds':time.monotonic()-start,'userSeconds':usage.ru_utime,'systemSeconds':usage.ru_stime,'peakRssKiB':usage.ru_maxrss,'exitCode':process.returncode}
  subprocess.run(['java','-cp',args.classpath,'com.fuyue.formatconverter.task.OcrDeskewGuardRiskProbe',str(source),str(folder/'original.tsv'),str(folder/'probe.json'),str(runtime)],check=True,timeout=15,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  probe=load(folder/'probe.json');orig='\n'.join(b['text'] for b in probe['original']['blocks']);arranged='\n'.join(probe['arranged']['lines']);inv=lambda t:Counter(ch for ch in t if not ch.isspace());assert inv(orig)==inv(arranged)
  a,b=before[c['file']],after[c['file']];assert a['success'] and b['success'];truth='\n'.join(c['expectedLines'])
  improved=(a.get('metrics',{}).get('cer',0)<b.get('metrics',{}).get('cer',0));old_deskew=any(w['code']=='OCR_DESKEW_APPLIED' for w in a.get('warnings',[]))
  record={'file':c['file'],'sourceSha256':c['sha256'],'sourceSkewDegrees':c['sourceSkewDegrees'],'command':cmd,'resources':resource,'probe':probe,'nativeOriginalMetrics':metrics(truth,orig),'nativeArrangedMetrics':metrics(truth,arranged),'httpBefore':a,'httpCurrent':b,'textMetricsExact':a.get('text')==b.get('text') and a.get('metrics')==b.get('metrics'),'beneficialOldDeskewSuppressed':bool(probe['originalGuardQualifies'] and old_deskew and improved),'strictButAlreadyOrdered':bool(probe['strictValidationSucceeded'] and not probe['arranged']['adjusted']),'potentialExplicitGuardSuppressesBenefit':bool(probe['strictValidationSucceeded'] and old_deskew and a.get('metrics',{}).get('cer',0)<metrics(truth,arranged)['cer'])};records.append(record)
  (args.out/'results.json').write_text(json.dumps({'manifestSha256':hashlib.sha256((args.samples/'expected.json').read_bytes()).hexdigest(),'scope':'Independent actual native/HTTP true-tilt guard risk; no intent inference','cases':records},ensure_ascii=False,indent=2)+'\n')
  print(c['file'],'strict',probe['strictValidationSucceeded'],'adjusted',probe['arranged']['adjusted'],'detected',probe['globalDetectedDegrees'],'before/current CER',a.get('metrics',{}).get('cer'),b.get('metrics',{}).get('cer'),'benefit-suppressed',record['beneficialOldDeskewSuppressed'],flush=True)
if __name__=='__main__':main()
