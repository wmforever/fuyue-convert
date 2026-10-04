#!/usr/bin/env python3
"""Reuse frozen inputs; append preserved original failure and numeric/visibility controls."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    before=ROOT/'qa-samples/generated/mixed-masks33-before';out=ROOT/'qa-samples/generated/mixed-masks33-final'
    out.mkdir(exist_ok=False);truth=json.loads((before/'expected.json').read_text())
    for n,s in truth['sources'].items():
        assert sha(before/n)==s;shutil.copyfile(before/n,out/n)
    preserved=ROOT/'qa-samples/generated/dedup32-final'
    def add(name):
        shutil.copyfile(preserved/name,out/name);truth['sources'][name]=sha(out/name)
    add('partial-new-digits.ofd');add('partial-new-digits.png')
    truth['actions'] += [dict(id='original-word',input='partial-new-digits.ofd',target='docx'),
        dict(id='original-office',input='@original-word',target='pdf'),dict(id='original-text',input='@original-office',target='txt'),
        dict(id='original-edited-office',input='@original-word',target='pdf',edit=dict(old='048.65',new='147.80')),
        dict(id='original-edited-text',input='@original-edited-office',target='txt')]
    common='DEDUP AUDIT 2076\nRecord 00793\nAmount 048.65\nDate 2076-10-14\n'
    truth['cases'].append(dict(id='original',expected=common,editedExpected=common.replace('048.65','147.80'),
        sourceValue='048.65',editValue='147.80',support='preserved-original-quality-failure'))
    for name in ['digit-extension','negative-sign','decimal-conflict','one-digit-conflict','masked-edited','boundary-reserve']:
        add(name+('.pdf' if name in ['masked-edited','boundary-reserve'] else '.ofd'))
        truth['actions'].append(dict(id=name+'-regression',input=name+('.pdf' if name in ['masked-edited','boundary-reserve'] else '.ofd'),target='txt'))
        baseline=ROOT/('qa-samples/report/iteration32-after/'+name+'-text-result.txt')
        truth.setdefault('regressions',[]).append(dict(id=name,expected=baseline.read_text(),baselineSha256=sha(baseline),
            conflictWarning=name in ['digit-extension','negative-sign','decimal-conflict','one-digit-conflict']))
    truth.update(frozenManifestSha256=sha(before/'expected.json'),finalGeneratorSha256=sha(Path(__file__)))
    (out/'expected.json').write_text(json.dumps(truth,indent=2,ensure_ascii=False)+'\n')
    print(len(truth['actions']),'final requests; frozen inputs unchanged')
if __name__=='__main__':main()
