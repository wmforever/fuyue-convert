#!/usr/bin/env python3
"""Package a clean, provenance-verified Linux cloud build and selected synthetic evidence.

No installation, release, network credentials, server logs, task data or unrelated
workspace files are included. Requires a final committed source revision.
"""
import argparse
import gzip
import hashlib
import html
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def digest(data):return hashlib.sha256(data).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT).decode().strip()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provenance',type=Path,required=True)
    parser.add_argument('--samples',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--evidence-doc',default='cloud-ocr-iteration4.md')
    parser.add_argument('--text-samples',type=Path)
    parser.add_argument('--text-report',type=Path)
    parser.add_argument('--container-report',type=Path,help='Final production PDF/OFD acceptance of containerCases')
    parser.add_argument('--regression-report',type=Path,help='Additional final TXT regression report and resources')
    args=parser.parse_args();revision=git('rev-parse','HEAD')
    assert not git('status','--porcelain'),'Commit validated work before packaging'
    provenance=json.loads(args.provenance.read_text())
    assert provenance['verifiedCodeRevision']==revision and provenance['packagedClassesMatchTargets']
    jar=ROOT/'web-api/target/web-api-0.1.5.jar';assert digest(jar.read_bytes())==provenance['jarSha256']
    subprocess.run(['python3',str(ROOT/'qa-samples/record_cloud_provenance.py'),'--record',str(args.provenance.resolve()),
                    '--verify-revision',revision],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
    report=json.loads((args.report/'report.json').read_text());manifest=json.loads((args.samples/'expected.json').read_text())
    assert 'synthetic' in manifest['provenance'].lower()
    assert len(report['cases'])==len(manifest['cases']) and all(c['success'] for c in report['cases'])
    artifact=json.loads((args.report/'artifact-provenance.json').read_text())
    assert artifact['jarSha256']==provenance['jarSha256'] and artifact['verifiedCodeRevision']==revision
    entries={};modes={}
    def put(name,data,executable=False):
        assert name not in entries and not name.startswith('/') and '..' not in Path(name).parts
        entries[name]=data;modes[name]=0o755 if executable else 0o644
    def file(name,path):put(name,path.read_bytes(),bool(path.stat().st_mode&0o111))
    file('app/web-api-0.1.5.jar',jar)
    runtime=ROOT/'desktop/.runtime/ocr'
    for path in sorted(runtime.rglob('*')):
        assert not path.is_symlink(),'Runtime symlinks are not accepted'
        if path.is_file():file('runtime/ocr/'+str(path.relative_to(runtime)),path)
    for path in sorted((ROOT/'desktop/licenses/java').rglob('*')):
        if path.is_file():file('licenses/java/'+str(path.relative_to(ROOT/'desktop/licenses/java')),path)
    for name in ['LICENSE','THIRD_PARTY_NOTICES.md']:file('licenses/'+name,ROOT/name)
    source=subprocess.check_output(['git','archive','--format=tar',revision],cwd=ROOT)
    put('source/fuyue-convert-'+revision+'.tar.gz',gzip.compress(source,mtime=0))
    for name in ['report.json','resources.json','artifact-provenance.json']:file('evidence/'+name,args.report/name)
    file('evidence/build-provenance.json',args.provenance)
    file('samples/expected.json',args.samples/'expected.json')
    for path in sorted((ROOT/'docs').glob('cloud-ocr-*.md')):file('evidence/docs/'+path.name,path)
    assert Path(args.evidence_doc).name==args.evidence_doc
    assert (ROOT/'docs'/args.evidence_doc).is_file()
    for path in sorted((ROOT/'docs').glob('cloud-ocr-iteration*-*.json')):file('evidence/docs/'+path.name,path)
    assert bool(args.text_samples)==bool(args.text_report),'Provide both TXT evidence paths'
    if args.text_report:
        text_report=json.loads((args.text_report/'report.json').read_text())
        text_samples=json.loads((args.text_samples/'expected.json').read_text())
        text_artifact=json.loads((args.text_report/'artifact-provenance.json').read_text())
        assert text_artifact['jarSha256']==provenance['jarSha256'] and text_artifact['verifiedCodeRevision']==revision
        assert 'synthetic' in text_samples['provenance'].lower()
        assert len(text_report['cases'])==len(text_samples['cases'])
        text_cases={c['file']:c for c in text_report['cases']}
        for name in ['report.json','resources.json','artifact-provenance.json']:file('evidence/txt-only/'+name,args.text_report/name)
        file('samples/txt-only/expected.json',args.text_samples/'expected.json')
        for case in text_samples['cases']:
            name=case['file'];assert Path(name).name==name
            result=text_cases[name];assert result['success'] and result['textOnly']
            assert digest((args.text_samples/name).read_bytes())==case['sha256']
            file('samples/txt-only/'+name,args.text_samples/name)
            put('evidence/txt-only/'+name+'.txt',result['text'].encode())
    rows=[]
    by_name={c['file']:c for c in report['cases']}
    for case in manifest['cases']:
        name=case['file'];assert Path(name).name==name
        source_path=args.samples/name;assert digest(source_path.read_bytes())==case['sha256']
        file('samples/'+name,source_path);r=by_name[name]
        for suffix in ['.docx','.scan.docx','.pdf','.scan.pdf','.png','.scan.png','.edited.scan.docx','.edited.scan.pdf','.edited.docx','.edited.pdf']:
            path=args.report/(name+suffix)
            if path.is_file():file('evidence/artifacts/'+path.name,path)
        if r.get('metrics'):
            put('evidence/artifacts/'+name+'.txt',r['text'].encode())
            m=r['metrics'];scan=r['scan'];assert scan['originalScanPixelsPreserved']
            order=scan['officeMetrics']['cer']
            picture='<a href="../evidence/artifacts/'+html.escape(name)+'.scan.png"><img loading="lazy" src="../evidence/artifacts/'+html.escape(name)+'.scan.png" alt="Office reopened scan"></a>'
            numbers=' '.join(m['actualNumbers'])
            score=f'{m["cer"]:.2%} / {m["alignedCharacterRecall"]:.2%}';xml=f'{scan["metrics"]["cer"]:.2%}'
        else:
            picture='Expected no-text failure';score='No text';xml='No text';order=None;numbers=''
        warnings=', '.join(w['code'] for w in r.get('warnings',[]))
        rows.append('<tr><td>'+html.escape(name)+'</td><td><a href="../samples/'+html.escape(name)+'"><img loading="lazy" src="../samples/'+html.escape(name)+'" alt="Synthetic source"></a></td><td>'+picture+'</td><td>'+score+'</td><td>'+xml+'</td><td>'+('—' if order is None else f'{order:.2%}')+'</td><td>'+html.escape(warnings)+'<br>'+html.escape(numbers)+'</td></tr>')
    if args.container_report:
        container=json.loads((args.container_report/'report.json').read_text())
        identity=json.loads((args.container_report/'artifact-provenance.json').read_text())
        assert identity['jarSha256']==provenance['jarSha256'] and identity['verifiedCodeRevision']==revision
        assert container['manifest']==manifest and len(container['cases'])==len(manifest['containerCases'])
        assert all(c['success'] for c in container['cases'])
        for name in ['report.json','resources.json','artifact-provenance.json']:file('evidence/containers/'+name,args.container_report/name)
        by_container={c['file']:c for c in container['cases']}
        for case in manifest['containerCases']:
            name=case['file'];assert Path(name).name==name
            assert digest((args.samples/name).read_bytes())==case['sha256']
            file('samples/'+name,args.samples/name);r=by_container[name]
            for suffix in ['.docx','.pdf','.png']:
                path=args.container_report/(name+suffix)
                if path.is_file():file('evidence/artifacts/'+path.name,path)
            if r.get('metrics'):
                assert r['originalScanPixelsPreserved']
                put('evidence/artifacts/'+name+'.txt',r['text'].encode())
                score=f'{r["metrics"]["cer"]:.2%} / {r["metrics"]["alignedCharacterRecall"]:.2%}'
                picture='<a href="../evidence/artifacts/'+html.escape(name)+'.png"><img src="../evidence/artifacts/'+html.escape(name)+'.png" alt="Office container scan"></a>'
                xml=f'{r["wordMetrics"]["cer"]:.2%}';order=f'{r["officeMetrics"]["cer"]:.2%}'
            else:score=xml=order='No text';picture='Expected no-text failure'
            source='<a href="../samples/'+html.escape(name)+'">'+html.escape(name)+'</a><img src="../samples/'+html.escape(case['rasterSource'])+'" alt="Original container raster">'
            rows.append('<tr><td>'+html.escape(name)+'</td><td>'+source+'</td><td>'+picture+'</td><td>'+score+'</td><td>'+xml+'</td><td>'+order+'</td><td>'+html.escape(', '.join(w['code'] for w in r.get('warnings',[])))+'</td></tr>')
    if args.regression_report:
        extra=json.loads((args.regression_report/'report.json').read_text())
        identity=json.loads((args.regression_report/'artifact-provenance.json').read_text())
        assert identity['jarSha256']==provenance['jarSha256'] and identity['verifiedCodeRevision']==revision
        assert all(c['success'] and (c.get('textOnly') or c.get('expectedFailureVerified')) for c in extra['cases'])
        for name in ['report.json','resources.json','artifact-provenance.json']:file('evidence/txt-regressions/'+name,args.regression_report/name)
    page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fuyue cloud acceptance review</title><style>body{font:15px system-ui;margin:28px;color:#17242e}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccd7df;padding:10px;vertical-align:top}th{background:#edf3f7;position:sticky;top:0}img{width:210px;height:155px;object-fit:contain;background:#f4f6f8}code{overflow-wrap:anywhere}td:last-child{max-width:280px;overflow-wrap:anywhere}</style><h1>Cloud acceptance review</h1><p>Linux x86_64 cloud build; no desktop installer or native-platform acceptance. Word rotation is unresolved. The scan layer can preserve text pixels while OCR omits editable words. Text CER/recall and Office PDF extraction order are separate measurements.</p><p>Source revision: <code>'''+revision+'''</code><br>JAR SHA-256: <code>'''+provenance['jarSha256']+'''</code></p><p>Open source and scan images at full size; inspect the editable DOCX, PDF and text files in evidence/artifacts. Expected blank/noise errors are explicit. Missing warnings do not certify completeness.</p><table><thead><tr><th>Case</th><th>Source</th><th>Office scan view</th><th>TXT CER / recall</th><th>Scan DOCX XML CER</th><th>Office PDF extraction CER</th><th>Warnings / digit runs</th></tr></thead><tbody>'''+''.join(rows)+'''</tbody></table></html>'''
    put('review/index.html',page.encode())
    launcher=r'''#!/usr/bin/env bash
set -euo pipefail
bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
java_version="$(java -version 2>&1)"
if [[ ! "$java_version" =~ version\ \"17\. ]]; then echo "Use JDK 17 for this acceptance build." >&2; exit 1; fi
: "${FORMAT_CONVERTER_API_TOKEN:?Set a new private API token before starting; no token is shipped.}"
export FORMAT_CONVERTER_APP_HOME="$bundle_dir/runtime"
export FORMAT_CONVERTER_DATA_ROOT="${FORMAT_CONVERTER_DATA_ROOT:-$bundle_dir/runtime-data}"
export SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"
export SERVER_PORT="${SERVER_PORT:-8080}"
exec java -Djava.awt.headless=true -jar "$bundle_dir/app/web-api-0.1.5.jar"
'''
    put('run-cloud.sh',launcher.encode(),True)
    readme=f'''# Fuyue Convert cloud acceptance build

Source: {revision}
JAR SHA-256: {provenance['jarSha256']}
Bundled OCR: pinned Linux x86_64 Tesseract 5.5.2, chi_sim+eng; full model/source/license manifest in runtime/ocr.
Validated JDK: Temurin17.0.16+8. JDK is required and is not bundled.
Office is not bundled. DOCX→PDF needs an external compatible LibreOffice. Cloud evidence used LibreOfficeDev26.8.0.0.alpha0, commit2c87e51eeaa2b413ff4ae097b2705eea1995d8e5.

Open review/index.html in a browser for source/scan views and separate text, editability and PDF-order metrics.
Read evidence/docs/{args.evidence_doc} and evidence/report.json for limitations and exact measured results.
Optional evidence/txt-only contains TXT-only tests, not Word/Office acceptance.
All sample/document evidence is synthetic. No API token, server logs, task storage, private uploads or unrelated files are included.

This is a cloud review build, not a release or desktop installer. Windows/macOS, Microsoft Word, other Linux distributions and native release Office are unrun. Arbitrary Word text rotation remains unresolved. Health reports an inherited0.1.4 version string; use SHA/provenance, not that string, for identity.

To run in a compatible Linux x86_64 environment with JDK17, extract the ZIP, set your own FORMAT_CONVERTER_API_TOKEN, and run ./run-cloud.sh. The service binds127.0.0.1 by default; use its authenticated API. Install/configure Office separately if needed. Runtime data is created beside the bundle unless FORMAT_CONVERTER_DATA_ROOT overrides it.

Verify extracted files from the bundle root with: sha256sum -c SHA256SUMS.txt
Source archive is the exact Git commit. Evidence build inputs and packaged application classes were verified against that revision.
'''
    put('README.md',readme.encode())
    put('bundle.json',json.dumps({'sourceRevision':revision,'jarSha256':provenance['jarSha256'],'platform':'Linux x86_64 cloud acceptance only',
        'cases':len(manifest['cases']),'containerCases':len(manifest.get('containerCases',[])) if args.container_report else 0,
        'nativeInstallersAccepted':False,'wordRotationSolved':False},indent=2).encode()+b'\n')
    sums=''.join(digest(data)+'  '+name+'\n' for name,data in sorted(entries.items()))
    put('SHA256SUMS.txt',sums.encode())
    args.out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for name,data in sorted(entries.items()):
            info=zipfile.ZipInfo(name,(2026,10,3,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            info.create_system=3;info.external_attr=(0o100000|modes[name])<<16;archive.writestr(info,data)
    with zipfile.ZipFile(args.out) as archive:
        assert archive.testzip() is None
        for line in archive.read('SHA256SUMS.txt').decode().splitlines():
            expected,name=line.split('  ',1);assert digest(archive.read(name))==expected
    result={'path':str(args.out.resolve()),'sha256':digest(args.out.read_bytes()),'bytes':args.out.stat().st_size,'entries':len(entries),'revision':revision}
    args.out.with_suffix(args.out.suffix+'.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))


if __name__=='__main__':main()
