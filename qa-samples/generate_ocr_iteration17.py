#!/usr/bin/env python3
"""Wrap frozen iteration15/handoff scans in single/multiple-image OFD containers.

Requires OcrMultiRasterOfdFixture compiled into --classes and the application's
OFDRW dependency classpath (--dependencies). Refuses to overwrite frozen output.
OFD ZIP metadata may vary; compare decoded scans/geometry, not regenerated ZIPs.
"""
import argparse,hashlib,json,pathlib,shutil,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes',required=True)
    parser.add_argument('--dependencies',required=True)
    parser.add_argument('--out',type=pathlib.Path,default=ROOT/'qa-samples/generated/cloud-iteration17')
    args=parser.parse_args();out=args.out.resolve();assert not out.exists();out.mkdir(parents=True)
    controlled=ROOT/'qa-samples/generated/cloud-iteration15';handoff=ROOT/'qa-samples/generated/cloud-handoff'
    for name in ['accepted-incomplete-engine.py','accepted-complete-engine.py','original.tsv','incomplete.tsv','complete.tsv']:
        shutil.copyfile(controlled/name,out/name)
    for name in ['accepted-incomplete-engine.py','accepted-complete-engine.py']:(out/name).chmod(0o700)
    code=(out/'accepted-incomplete-engine.py').read_text().replace("selected={'accepted-incomplete'",
        "mode='accepted-complete' if base.parent.name=='ocr-2' else mode\nselected={'accepted-incomplete'")
    (out/'mixed-engine.py').write_text(code);(out/'mixed-engine.py').chmod(0o700)
    cp=args.classes+':'+args.dependencies
    for name,images in [('incomplete',[controlled/'accepted-incomplete.png']),('complete',[controlled/'accepted-complete.png']),
            ('mixed',[controlled/'accepted-incomplete.png',controlled/'accepted-complete.png']),
            ('bilingual',[handoff/'english-tilt-+0.png',handoff/'chinese-tilt-+0.png'])]:
        subprocess.run(['java','-cp',cp,'OcrMultiRasterOfdFixture',str(out/(name+'.ofd')),*map(str,images)],check=True)
    manifest={'cases':[{'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
        for p in sorted(out.iterdir()) if p.is_file()]}
    (out/'expected.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(manifest))

if __name__=='__main__':main()
