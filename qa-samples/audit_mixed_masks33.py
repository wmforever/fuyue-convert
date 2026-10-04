#!/usr/bin/env python3
"""Audit final clean build, pinned runtime, tests and changes against accepted parent JAR."""
import hashlib,io,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def application(jar):
    entries={}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):entries[name]=hashlib.sha256(z.read(name)).hexdigest()
            elif name.startswith('BOOT-INF/lib/') and any(Path(name).name.startswith(m+'-') for m in ['task-service','docx-renderer','layout-model','ofd-parser','table-recognizer']):
                with zipfile.ZipFile(io.BytesIO(z.read(name))) as q:
                    for n in q.namelist():
                        if not n.endswith('/'):entries[name+'/'+n]=hashlib.sha256(q.read(n)).hexdigest()
    return entries
def main():
    counts={k:0 for k in ['tests','failures','errors','skipped']};skips=[];bundled=[]
    for p in ROOT.glob('*/target/surefire-reports/TEST-*.xml'):
        r=E.parse(p).getroot()
        for k in counts:counts[k]+=int(r.get(k,0))
        for t in r.findall('testcase'):
            row=dict(className=t.get('classname'),name=t.get('name'),skipped=t.find('skipped') is not None)
            if row['skipped']:skips.append(row)
            if 'bundled' in row['name'].lower():bundled.append(row)
    assert counts==dict(tests=496,failures=0,errors=0,skipped=1),counts
    assert skips[0]['className'].endswith('OfdrwParserSignatureTest')
    assert all(not t['skipped'] for t in bundled)
    before=application(ROOT/'qa-samples/work/iteration33-before.jar');after=application(ROOT/'web-api/target/web-api-0.1.5.jar')
    assert before.keys()==after.keys()
    changed=[n for n in after if before[n]!=after[n]]
    assert len(changed)==5 and all('FixedLayoutDocxRenderer' in n and n.endswith('.class') for n in changed),changed
    classes=[n for n in after if n.endswith('.class')];assert len(classes)==232
    static={str(p.relative_to(ROOT/'frontend/dist')):sha(p) for p in (ROOT/'frontend/dist').rglob('*') if p.is_file()}
    packaged={n.removeprefix('BOOT-INF/classes/static/'):s for n,s in after.items() if n.startswith('BOOT-INF/classes/static/')}
    assert static==packaged and len(static)==7
    runtime=ROOT/'qa-samples/work/iteration25-review-app/ocr';manifest=load(runtime/'OCR-RUNTIME.json')
    for f in manifest['files']:assert sha(runtime/f['path'])==f['sha256'] and (runtime/f['path']).stat().st_size==f['size']
    parent=load(ROOT/'docs/cloud-dedup32-validation.json');fonts=dict(source=parent['sourceFont'],bundled=parent['bundledFontsReverified'])
    # Match the exact previously observed font payloads; include current hashes below.
    fontFiles=[Path('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'),ROOT/'task-service/src/main/resources/fonts/LiberationSans-Regular.ttf',ROOT/'task-service/src/main/resources/fonts/DroidSansFallback.ttf']
    expected=['bade59d822652f76e6941aa87b40a87c13d1cc70db98ededb5011127efafd1d3','76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8','21b96a0377f067833a93af3082eb28d4ffab7a8cd46bfd513286f1d64b7b0949']
    for p,s in zip(fontFiles,expected,strict=True):assert sha(p)==s
    receipt=ROOT/'qa-samples/work/iteration33-final-build.receipt.json';r=load(receipt)
    assert r['rootExitCode']==0 and r['waitpidNoChildren'] and all(not matches(p) for p in r['registered'])
    result=dict(tests=counts,skips=skips,bundledTests=bundled,changedClasses=changed,allOther227ApplicationClassesByteExact=True,
        allOtherApplicationResourcesByteExact=True,frontendRebuiltFilesMatchPackaged=static,buildReceipt=dict(path=str(receipt.relative_to(ROOT)),sha256=sha(receipt),rootExitCode=0,ECHILD=True,registeredIdentitiesAbsent=len(r['registered'])),
        provenance={k:v for k,v in load(ROOT/'qa-samples/work/iteration33-final-provenance.json').items() if k!='buildInputs'},
        versionsObservedInAcceptedParent=parent['versionsObservedInAcceptedParent'],pdfbox='3.0.8',ofdrw='2.3.9',
        runtimeManifestSha256=sha(runtime/'OCR-RUNTIME.json'),runtimeFilesReverified=len(manifest['files']),runtimeManifest=manifest,fonts=fonts,
        currentFontHashes={str(p):sha(p) for p in fontFiles},nativeMacWindowsInstallersUnrun=True,MicrosoftWordUnrun=True,helperSha256=sha(Path(__file__)))
    (ROOT/'docs/cloud-mixed-masks33-validation.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(dict(tests=counts,changedClasses=changed,bundledTests=len(bundled),runtimeFiles=len(manifest['files']))))
if __name__=='__main__':main()
