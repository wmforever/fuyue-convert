#!/usr/bin/env python3
"""Freeze final guard validation from existing, hash-checked scan PDF inputs.

Seven unchanged light-paper Word payloads can reuse their prior Office/OCR
measurements only if every substantive DOCX part matches. The original failure
also runs the complete four-request path again on the final JAR.
"""
from pathlib import Path
import hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/edit-iteration26-final';assert not out.exists();out.mkdir(parents=True)
    frozen=json.loads((ROOT/'qa-samples/generated/edit-iteration26-pdf/expected.json').read_text());before=ROOT/'qa-samples/report/iteration26-scan-before';report=json.loads((before/'report.json').read_text());sources={};actions=[]
    for c in frozen['cases']:
        name=c['id'];row=next(r for r in report['cases'] if r['case']==name+'-pdf');src=before/row['artifact'];assert sha(src)==row['sha256'];file=name+'.pdf';shutil.copyfile(src,out/file);sources[file]=sha(src);actions.append({'id':name+'-word','input':file,'target':'docx'})
    original=ROOT/'qa-samples/generated/edit-iteration26-original';om=json.loads((original/'expected.json').read_text())
    for file,digest in om['sources'].items():assert sha(original/file)==digest;shutil.copyfile(original/file,out/file);sources[file]=digest
    actions+=om['actions'];m={'parentRevision':'096f0eda722c626e9680268846979c19c9ee054f','generatorSha256':sha(Path(__file__)),'originalFrozenManifestSha256':sha(ROOT/'qa-samples/generated/edit-iteration26-pdf/expected.json'),'sources':sources,'actions':actions,'scope':'seven substantive Word payload comparisons and four original regression HTTP conversions; no full matrix repeated'}
    (out/'expected.json').write_text(json.dumps(m,indent=2)+'\n');print(json.dumps({'actions':len(actions),'manifestSha256':sha(out/'expected.json')}))
if __name__=='__main__':main()
