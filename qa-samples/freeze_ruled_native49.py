#!/usr/bin/env python3
"""Freeze two existing edited Office PDFs for the final TXT-only change; no new rendering/OCR."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 out=ROOT/'qa-samples/generated/ruled-native49';out.mkdir(exist_ok=False);actions=[];sources={}
 for name in ['ruled','independent']:
  source=ROOT/'qa-samples/work/iteration49-http'/(name+'-edited-office-result.pdf');dest=out/(name+'.pdf');shutil.copyfile(source,dest);sources[dest.name]=sha(dest);actions.append(dict(id=name+'-api',input=dest.name,target='txt'))
 (out/'expected.json').write_text(json.dumps(dict(generatorSha256=sha(Path(__file__)),sources=sources,actions=actions,sourceReportSha256=sha(ROOT/'qa-samples/work/iteration49-http/report.json'),acceptance='Every original field exactly once in row order; edited value present and old value absent; zero additional OCR',bounds=dict(contractSeconds=120,matrixSeconds=480)),indent=2)+'\n')
 print('Frozen two actual edited PDF downloads for final native TXT acceptance')
if __name__=='__main__':main()
