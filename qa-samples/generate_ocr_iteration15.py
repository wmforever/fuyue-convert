#!/usr/bin/env python3
"""Freeze controlled engine responses for an omission-warning contract, not OCR accuracy."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/cloud-iteration15'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    assert not OUT.exists(),'Never overwrite frozen controlled responses'
    OUT.mkdir(parents=True)
    source=ROOT/'qa-samples/generated/cloud-iteration11/en-serif-shadow.png'
    source_manifest=json.loads((source.parent/'expected.json').read_text())
    truth=next(c for c in source_manifest['cases'] if c['file']==source.name)
    original=ROOT/'qa-samples/work/iteration11-native/en-serif-shadow.png/original.tsv'
    enhanced=ROOT/'qa-samples/work/iteration11-dpi/en-serif-shadow.png/without-dpi.tsv'
    rows=[line.split('\t') for line in enhanced.read_text().splitlines()[1:] if line.split('\t')[0]=='5']
    line_ids=list(dict.fromkeys(tuple(r[2:5]) for r in rows));assert len(line_ids)==8
    header=enhanced.read_text().splitlines()[0]
    def tsv(name,indices,confidence):
        path=OUT/name
        kept=[]
        for row in rows:
            if tuple(row[2:5]) in [line_ids[i] for i in indices]:
                row=list(row);row[10]=str(confidence);kept.append('\t'.join(row))
        path.write_text(header+'\n'+'\n'.join(kept)+'\n');return path
    initial=tsv('original.tsv',[6,7],60)
    candidates={'accepted-incomplete':tsv('incomplete.tsv',[4,5,6,7],90),
                'accepted-complete':tsv('complete.tsv',range(8),90),
                'rejected':tsv('rejected.tsv',[4,5,6,7],62)}
    for name in candidates:shutil.copyfile(source,OUT/(name+'.png'))
    engine=OUT/'controlled-engine.py'
    engine.write_text('''#!/usr/bin/env python3
import pathlib,sys,shutil
root=pathlib.Path(__file__).resolve().parent
if '--version' in sys.argv:print('tesseract 5.5.2');sys.exit(0)
if '--list-langs' in sys.argv:print('List of available languages (1):\\neng');sys.exit(0)
image=pathlib.Path(sys.argv[1]);base=pathlib.Path(sys.argv[2]);mode=__MODE__
selected={'accepted-incomplete':'incomplete.tsv','accepted-complete':'complete.tsv','rejected':'rejected.tsv'}[mode]
is_retry='tesseract-enhanced-' in image.name
shutil.copyfile(root/(selected if is_retry else 'original.tsv'),str(base)+'.tsv')
''')
    # Each wrapper carries immutable mode, including for HTTP worker input names.
    for name in candidates:
        wrapper=OUT/(name+'-engine.py');wrapper.write_text(engine.read_text().replace('__MODE__',repr(name)));wrapper.chmod(0o700)
    engine.unlink()
    manifest={'license':'Apache-2.0 synthetic text/controlled responses; original fonts SIL-OFL-1.1',
        'scope':'Fault-injection engine contract; no claimed native OCR accuracy/confidence',
        'sourceManifestSha256':sha(source.parent/'expected.json'),'sourceImageSha256':sha(source),
        'nativeTsvSourceSha256':sha(enhanced),'expectedLines':truth['expectedLines'],
        'generatorSha256':sha(Path(__file__)),'files':{p.name:sha(p) for p in sorted(OUT.iterdir())},
        'cases':[{'file':name+'.png','sha256':sha(OUT/(name+'.png')),'engine':name+'-engine.py',
                  'enhancedResponse':path.name,'expectedEnhanced':name!='rejected',
                  'expectedFinalCoverageProbe':name!='accepted-complete'} for name,path in candidates.items()]}
    (OUT/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    shutil.copyfile(OUT/'expected.json',ROOT/'docs/cloud-ocr-iteration15-controlled-corpus.json')
    print(json.dumps({'controlledCases':3,'manifestSha256':sha(OUT/'expected.json')}))
if __name__=='__main__':main()
