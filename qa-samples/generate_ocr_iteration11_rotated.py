#!/usr/bin/env python3
"""Freeze signed-angle controls from the unchanged independent column corpus."""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, __version__ as pillow_version

ROOT=Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,default=ROOT/'qa-samples/generated/cloud-iteration11-rotated')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    assert not (args.out/'expected.json').exists(), 'Never overwrite frozen controls'
    source=ROOT/'qa-samples/generated/cloud-iteration11';manifest=source/'expected.json'
    assert digest(manifest)=='cac258df4f6f80046529d25c85cf039605cab55d89f039f670944e07f6dc1ab1'
    parent=json.loads(manifest.read_text());cases=[]
    for case in parent['cases']:
        if case['file'] not in ['en-columns-aligned.png','zh-columns-aligned.png',
                                'en-staggered-columns.png','zh-staggered-columns.png']:continue
        assert digest(source/case['file'])==case['sha256']
        for angle in [-3,3]:
            path=args.out/(Path(case['file']).stem+f'-angle{angle:+d}.png')
            with Image.open(source/case['file']) as image:
                image.rotate(-angle,resample=Image.Resampling.BICUBIC,expand=False,fillcolor='white').save(path,dpi=(300,300))
            cases.append({k:v for k,v in case.items() if k not in ['file','sha256','ambiguityPair','readingIntent']}
                | {'file':path.name,'sha256':digest(path),'sourceSkewDegrees':angle,
                   'parentFile':case['file'],'parentSha256':case['sha256']})
    out={'seed':110042026,'provenance':'Public synthetic signed-angle controls frozen before a new algorithm change',
        'license':parent['license'],'fonts':parent['fonts'],'versions':{'Pillow':pillow_version},
        'parentManifestSha256':digest(manifest),'generatorSha256':digest(Path(__file__)),
        'cases':cases,'containerCases':[]}
    p=args.out/'expected.json';p.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
    print('Frozen',len(cases),'signed-angle controls; manifest',digest(p))


if __name__=='__main__':main()
