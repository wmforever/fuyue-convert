#!/usr/bin/env python3
"""Isolate namespace-prefix dispatch with only literal prefix changes in frozen controls."""
from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 old=ROOT/'qa-samples/generated/sparse-ofd29';out=ROOT/'qa-samples/generated/sparse-ofd29-canonical';out.mkdir(exist_ok=False);sources={};actions=[]
 for name in ['native-only','image-only','missing-native-amount']:
  source=old/(name+'.ofd');dest=out/(name+'.ofd')
  with zipfile.ZipFile(source) as a,zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as b:
   for entry in a.infolist():
    data=a.read(entry.filename)
    if entry.filename=='Doc_0/Pages/Page_0/Content.xml':data=data.replace(b'xmlns:ns0=',b'xmlns:ofd=').replace(b'<ns0:',b'<ofd:').replace(b'</ns0:',b'</ofd:')
    b.writestr(entry.filename,data)
  sources[dest.name]=sha(dest);actions.append(dict(id=name+'-txt',input=dest.name,target='txt'))
 (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,helperSha256=sha(Path(__file__))),indent=2)+'\n');print(len(actions))
if __name__=='__main__':main()
