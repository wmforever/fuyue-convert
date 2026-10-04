#!/usr/bin/env python3
"""Copy frozen nine lexical controls plus six already accepted numeric/mask regressions."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    baseline=ROOT/'qa-samples/generated/numeric35-before';old=ROOT/'qa-samples/generated/dedup32-final'
    out=ROOT/'qa-samples/generated/numeric35-final';assert not out.exists();shutil.copytree(baseline,out)
    manifest=json.loads((out/'expected.json').read_text());controls=json.loads((old/'expected.json').read_text())
    selected={'digit-extension-text','negative-sign-text','decimal-conflict-text','one-digit-conflict-text','masked-edited-text','boundary-reserve-text'}
    for action in controls['actions']:
        if action['id'] not in selected:continue
        source=old/action['input'];assert sha(source)==controls['sources'][source.name];shutil.copyfile(source,out/source.name)
        manifest['sources'][source.name]=sha(source);manifest['actions'].append(action)
    assert len(manifest['actions'])==15
    manifest['frozenBaselineManifestSha256']=sha(baseline/'expected.json');manifest['oldRegressionManifestSha256']=sha(old/'expected.json')
    manifest['finalGeneratorSha256']=sha(Path(__file__));manifest['regeneratedInputs']=False
    (out/'expected.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n');print('frozen inputs=15;regenerated=0')
if __name__=='__main__':main()
