#!/usr/bin/env python3
"""Independently audit frozen-truth reports and unchanged scan layout artifacts."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

import fitz
from verify_partial_word_preservation import shapes, signature

ROOT=Path(__file__).resolve().parents[1]


def load(path):return json.loads(path.read_text())
def inventory(text):return Counter(c for c in text if not c.isspace())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports',type=Path,default=ROOT/'qa-samples/report')
    parser.add_argument('--out',type=Path,default=ROOT/'docs/cloud-ocr-iteration8-results.json')
    args=parser.parse_args();root=args.reports
    manifest=load(ROOT/'docs/cloud-ocr-iteration8-corpus.json')
    assert hashlib.sha256((ROOT/'docs/cloud-ocr-iteration8-corpus.json').read_bytes()).hexdigest()=='601446606c22484a02a65e51668796c675049633c66ece508e45b5c725d59b3a'
    reports={}
    for baseline in ['accuracy','safety','final']:
        for scope in ['word','containers']:
            directory=root/f'iteration8-{baseline}-{scope}'
            r=load(directory/'report.json')
            assert r['manifest']==manifest
            expected=manifest['cases' if scope=='word' else 'containerCases']
            assert len(r['cases'])==len(expected) and all(c['success'] for c in r['cases'])
            reports[baseline+'-'+scope]={'cases':{c['file']:c for c in r['cases']},
                'identity':{k:v for k,v in load(directory/'artifact-provenance.json').items() if k!='buildInputs'},
                'resources':load(directory/'resources.json'),'health':r['health']}
    comparisons=[];layout=[]
    for scope,key in [('word','cases'),('containers','containerCases')]:
        for truth in manifest[key]:
            name=truth['file'];samples={b:reports[b+'-'+scope]['cases'][name] for b in ['accuracy','safety','final']}
            safety,final=samples['safety'],samples['final']
            assert inventory(safety.get('text',''))==inventory(final.get('text','')),name
            if name!='en-three-columns.png':assert safety.get('metrics')==final.get('metrics'),name
            for field in ['editableText','officeMetrics','wordMetrics','pages','expectedFailureVerified','originalScanPixelsPreserved']:
                assert safety.get(field)==final.get(field),(name,field)
            if scope=='word' and 'scan' in final:
                for field in ['editableText','mediaSha256','originalScanPixelsPreserved','metrics','officeMetrics',
                              'sourceMarginRgb','renderedMarginRgb','marginMaxChannelDelta','unrecognizedInkProbes']:
                    assert safety['scan'][field]==final['scan'][field],(name,field)
            def measurements(c):
                fields=['success','metrics','wordMetrics','officeMetrics','pages','seconds','warnings','wordWarnings',
                        'expectedFailureVerified','editableMatchesTxt','officeEditVerified','originalScanPixelsPreserved']
                value={k:c[k] for k in fields if k in c}
                if 'scan' in c:value['scan']={k:v for k,v in c['scan'].items() if k!='editableText'}
                return value
            comparisons.append({'file':name,'sourceSha256':truth['sha256'],'scope':scope,
                **{b:measurements(c) for b,c in samples.items()},'recognizedCharacterInventoryExact':True})
            if scope=='word' and 'scan' in final:
                old=root/'iteration8-safety-word';new=root/'iteration8-final-word'
                a,b,media_a=shapes(old/(name+'.scan.docx'));c,d,media_b=shapes(new/(name+'.scan.docx'))
                assert Counter(map(signature,a))==Counter(map(signature,c)),name+' frames'
                assert Counter(map(signature,b))==Counter(map(signature,d)),name+' masks'
                assert media_a==media_b,name+' media'
                with fitz.open(old/(name+'.scan.pdf')) as x,fitz.open(new/(name+'.scan.pdf')) as y:
                    assert len(x)==len(y)==1
                    # Compare all glyph words, including repeated and missing numeric tokens,
                    # as multisets of exact text and measured boxes; no unique-token shortcut.
                    def words(p):return sorted((w[4],tuple(w[:4])) for w in p.get_text('words'))
                    assert words(x[0])==words(y[0]),name+' Office glyph geometry'
                    old_png=x[0].get_pixmap(matrix=fitz.Matrix(1,1),alpha=False).samples
                    new_png=y[0].get_pixmap(matrix=fitz.Matrix(1,1),alpha=False).samples
                    assert old_png==new_png,name+' Office rendered pixels'
                layout.append({'file':name,'wordFrames':len(a),'masks':len(b),'framesMasksStyleRanksExact':True,
                    'sourceMediaExact':True,'allOfficeWordBoxesExact':True,'officeRenderedPixelsExact':True})
    regressions={}
    for scope,old_name in [('regressions','iteration7-final-acceptance'),('columns','iteration7-final-columns')]:
        old=load(root/old_name/'report.json');new=load(root/f'iteration8-final-{scope}'/'report.json')
        previous={c['file']:c for c in old['cases']};truth={c['file']:c for c in old['manifest']['cases']}
        assert len(new['cases'])==len(old['cases']) and all(c['success'] for c in new['cases'])
        for c in new['cases']:
            name=c['file'];a=previous[name]
            assert a.get('metrics')==c.get('metrics'),name
            assert inventory(a.get('text',''))==inventory(c.get('text','')),name
            assert next(t for t in new['manifest']['cases'] if t['file']==name)==truth[name]
        regressions[scope]={'cases':len(new['cases']),'metricsAndCharactersUnchanged':True,
            'resources':load(root/f'iteration8-final-{scope}'/'resources.json')}
    result={'scope':'Frozen independent public synthetic Linux cloud OCR/API/Word/OFD review; no native acceptance',
        'manifestSha256':'601446606c22484a02a65e51668796c675049633c66ece508e45b5c725d59b3a',
        'seed':manifest['seed'],'fonts':manifest['fonts'],'versions':manifest['versions'],
        'runtime':load(ROOT/'desktop/.runtime/ocr/OCR-RUNTIME.json'),
        'matrices':{k:{f:v for f,v in r.items() if f!='cases'} for k,r in reports.items()},
        'cases':comparisons,'layoutPreservation':layout,'regressions':regressions,
        'limits':['Improved CER/recall on the fragmented English columns reflects order, not new recognized characters.',
            'Twelve missing row-ending digits remain missing; no numbers are manufactured.',
            'New shaded and table cases remain lossy on safety and accuracy baselines.',
            'Concurrent runs make resource counters unsuitable as isolated speed comparisons.',
            'Native macOS/Windows, Microsoft Word, handwriting/private corpus and general Word rotation remain unrun.']}
    args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'cases':len(comparisons),'layoutChecks':len(layout),'regressions':{k:v['cases'] for k,v in regressions.items()}}))


if __name__=='__main__':main()
