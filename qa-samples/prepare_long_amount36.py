#!/usr/bin/env python3
"""Combine frozen original English and corrected Chinese controls without regeneration."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    original=ROOT/'qa-samples/generated/long-amount36';corrected=ROOT/'qa-samples/generated/long-amount36-zh-latin'
    out=ROOT/'qa-samples/generated/long-amount36-final';out.mkdir(exist_ok=False)
    result=dict(sources={},cases=[],actions=[],frozenParents=['long-amount36','long-amount36-zh-latin'],parentRevision='7a3ceb25835b0fa2e032f4fe3cf158487aaafa90',initialChineseFontFailureExcluded=True)
    for folder,language in [(original,'en'),(corrected,'zh')]:
        manifest=json.loads((folder/'expected.json').read_text());cases=[c for c in manifest['cases'] if c['language']==language];ids={c['id'] for c in cases};result['cases'].extend(cases)
        for case in cases:
            name=case['id']+'.png';data=(folder/name).read_bytes();assert hashlib.sha256(data).hexdigest()==manifest['sources'][name]
            (out/name).write_bytes(data);result['sources'][name]=manifest['sources'][name]
        result['actions'].extend(a for a in manifest['actions'] if any(a['id'].startswith(i+'-') for i in ids))
    assert len(result['cases'])==6 and len(result['actions'])==30
    (out/'expected.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print('6 frozen inputs copied;30 bounded requests;0regenerated')
if __name__=='__main__':main()
