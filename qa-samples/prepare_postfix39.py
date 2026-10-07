#!/usr/bin/env python3
"""Copy frozen new controls and 15 accepted regressions; audit an existing copy without writes."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    baseline=ROOT/'qa-samples/generated/postfix39-before';old=ROOT/'qa-samples/generated/numeric35-final';out=ROOT/'qa-samples/generated/postfix39-final'
    manifest=json.loads((baseline/'expected.json').read_text());previous=json.loads((old/'expected.json').read_text());copied={}
    for name,digest in manifest['sources'].items():assert sha(baseline/name)==digest;copied[name]=(baseline/name).read_bytes()
    for action in previous['actions']:
        p=old/action['input'];assert sha(p)==previous['sources'][p.name] and p.name not in manifest['sources'];copied[p.name]=p.read_bytes();manifest['sources'][p.name]=sha(p);manifest['actions'].append(action)
    manifest['oldRegressions']=[a['id'] for a in previous['actions']];manifest['oldRegressionManifestSha256']=sha(old/'expected.json');manifest['beforeManifestSha256']=sha(baseline/'expected.json');manifest['regeneratedInputs']=False;assert len(manifest['actions'])==27
    if out.exists():
        assert json.loads((out/'expected.json').read_text())==manifest
        for name,data in copied.items():assert (out/name).read_bytes()==data
        print('Existing frozen27-control corpus byte-verified;0writes/0regenerated')
    else:
        out.mkdir()
        for name,data in copied.items():(out/name).write_bytes(data)
        (out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('Frozen27controls copied;0regenerated')
if __name__=='__main__':main()
