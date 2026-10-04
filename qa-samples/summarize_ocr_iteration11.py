#!/usr/bin/env python3
"""Audit independent table/column truth, actual HTTP regressions and scan artifacts."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from verify_cloud_artifact_regression import compare_artifacts

ROOT=Path(__file__).resolve().parents[1]


def load(path):return json.loads(path.read_text())
def identity(path):return {k:v for k,v in load(path/'artifact-provenance.json').items() if k!='buildInputs'}
def inventory(text):return Counter(c for c in text if not c.isspace())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,default=ROOT/'docs/cloud-ocr-iteration11-results.json')
    args=parser.parse_args();reports=ROOT/'qa-samples/report'
    before=reports/'iteration11-before-word';after=reports/'iteration11-final-word'
    a,b=load(before/'report.json'),load(after/'report.json')
    assert a['manifest']==b['manifest']==load(ROOT/'docs/cloud-ocr-iteration11-corpus.json')
    assert len(a['cases'])==len(b['cases'])==16
    assert all(c['success'] for r in [a,b] for c in r['cases'])
    old={c['file']:c for c in a['cases']};new={c['file']:c for c in b['cases']}
    native=load(ROOT/'qa-samples/work/iteration11-native/results.json');natives={c['file']:c for c in native['cases']}
    cases=[];artifacts=[]
    for truth in a['manifest']['cases']:
        name=truth['file'];x,y=old[name],new[name];n=natives[name]
        assert hashlib.sha256((ROOT/'qa-samples/generated/cloud-iteration11'/name).read_bytes()).hexdigest()==truth['sha256']
        if name=='en-staggered-columns.png':
            assert y['metrics']==n['afterMetrics'], 'Fix must retain the original recognized text, not manufacture corrected digits'
            assert y['metrics']['cer']<x['metrics']['cer']
            assert not any(w['code']=='OCR_DESKEW_APPLIED' for w in y['warnings'])
            assert inventory(y['text'])==inventory('\n'.join(block['text'] for block in n['probe']['original']['blocks']))
        else:
            assert x.get('text')==y.get('text'),name+' TXT text/order/boundaries'
            assert x.get('metrics')==y.get('metrics'),name+' completeness'
        for field in ['editableText','officeText','officeMetrics','pages','expectedFailureVerified']:
            assert x.get(field)==y.get(field),(name,field)
        if 'scan' in y:
            for field in ['editableText','mediaSha256','originalScanPixelsPreserved','metrics','officeMetrics',
                          'sourceMarginRgb','renderedMarginRgb','marginMaxChannelDelta','unrecognizedInkProbes']:
                assert x['scan'][field]==y['scan'][field],(name,field)
            for suffix in ['','.scan']:artifacts.append(compare_artifacts(before,after,name,suffix))
        def measurement(c):return {k:v for k,v in c.items() if k not in ['text','editableText','officeText','docxBytes','pdfBytes']}
        original=n['probe']['original']
        cases.append({'truth':truth,'before':measurement(x),'after':measurement(y),
            'native':{k:v for k,v in n.items() if k not in ['probe','expectedLines']},
            'nativeOriginalText':'\n'.join(block['text'] for block in original['blocks']),
            'nativeArrangedText':'\n'.join(n['probe']['arranged']['lines']),
            'nativeConfidence':original['confidence'],'nativeWordCount':original['wordCount'],
            'nativeBlockCount':len(original['blocks']),'nativeAdjusted':n['probe']['arranged']['adjusted'],
            'nativeSourceObjectsExact':n['probe']['sourceObjectsExact'],
            'nativeOriginalWordEvidence':[w for block in original['blocks'] for w in block['ocrWords']],
            'nativeCoordinateBox':n['probe']['originalCoordinates']})
    for language in ['en','zh']:
        pair=[new[language+'-'+name+'.png'] for name in ['prose-unruled','columns-aligned']]
        assert pair[0]['text']==pair[1]['text'], 'Identical pixels have identical API output despite different truth'
    regressions={}
    for label,old_name,final_name in [('rotated','iteration11-before-rotated','iteration11-final-rotated'),
            ('prior20','iteration8-final-regressions','iteration11-final-prior20'),
            ('prior9','iteration9-overlap-columns','iteration11-final-prior9')]:
        x,y=load(reports/old_name/'report.json'),load(reports/final_name/'report.json')
        assert x['manifest']==y['manifest'];assert len(x['cases'])==len(y['cases'])
        assert all(c['success'] for c in y['cases']);previous={c['file']:c for c in x['cases']}
        for c in y['cases']:
            p=previous[c['file']];assert p.get('text')==c.get('text');assert p.get('metrics')==c.get('metrics')
            assert p.get('expectedFailureVerified')==c.get('expectedFailureVerified')
        regressions[label]={'count':len(y['cases']),'textMetricsNumericBoundariesExact':True,
            'beforeIdentity':identity(reports/old_name),'afterIdentity':identity(reports/final_name),
            'beforeResources':load(reports/old_name/'resources.json'),
            'afterResources':load(reports/final_name/'resources.json'),
            'measurements':[{k:v for k,v in c.items() if k!='text'} for c in y['cases']]}
    dpi=load(ROOT/'qa-samples/work/iteration11-dpi/results.json')
    dpi_compact=[]
    for c in dpi['cases']:
        v=c['selection']['variants']
        if v:assert v['without-dpi']['candidate']==v['source-dpi']['candidate']
        dpi_compact.append({k:v for k,v in c.items() if k!='selection'} | {'originalConfidence':c['selection']['original']['confidence'],
            'variants':{name:{k:v for k,v in result.items() if k not in ['candidate','selected']} for name,result in v.items()},
            'allCandidateTextConfidenceCoordinatesIdentical':True if v else None})
    result={'scope':'Frozen independent Linux native and authenticated HTTP table/column/scan regression; no general reading-intent inference',
        'manifestSha256':'cac258df4f6f80046529d25c85cf039605cab55d89f039f670944e07f6dc1ab1',
        'rotatedManifestSha256':'14eedc97794be2ac779dc9667414b069fa48b3f5dc57103518b23dc530b3d815',
        'seed':a['manifest']['seed'],'fonts':a['manifest']['fonts'],'versions':a['manifest']['versions'],
        'runtime':load(ROOT/'desktop/.runtime/ocr/OCR-RUNTIME.json'),
        'beforeIdentity':identity(before),'afterIdentity':identity(after),
        'beforeResources':load(before/'resources.json'),'afterResources':load(after/'resources.json'),
        'health':b['health'],'nativeUniqueRuns':native['uniqueNativeRuns'],'cases':cases,
        'artifactComparisons':artifacts,'regressions':regressions,'shadowSourceDpiDiagnostic':dpi_compact,
        'limits':['Intentionally identical unruled pairs have different reading intents; they are not independent statistical trials.',
            'The earlier Java row-major counterexample was not reproduced as a correct native-image input becoming wrong.',
            'No general table discriminator or threshold/DPI policy is adopted; incomplete high-confidence shadows remain.',
            'The fix skips global deskew only after the original qualifies under existing strict fragmented-column geometry.',
            'Six original record0N→recordoN errors remain; restoring the original is not numeric correction from truth.',
            'Timing/resources are measured in non-isolated matrices; no broad performance gain is established.',
            'Native installers, Microsoft Word, private corpus/handwriting, arbitrary Word rotation/reflow/editing remain unrun.']}
    args.out.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    print(json.dumps({'contracts':16+sum(v['count'] for v in regressions.values()),
        'artifactComparisons':len(artifacts),'fixedCaseCER':new['en-staggered-columns.png']['metrics']['cer'],
        'nativeUniqueRuns':native['uniqueNativeRuns']}))


if __name__=='__main__':main()
