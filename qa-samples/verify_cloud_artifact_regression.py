#!/usr/bin/env python3
"""Audit frozen HTTP image/Word/PDF/OFD artifacts against accepted baselines.

Full text/order, exact source frames/masks/media, every actual Office word box
and rendered pixels are compared. This is a bounded synthetic regression audit,
not completeness or native-platform certification. Outputs may be saved as
public JSON; binary samples, task storage and server logs stay ignored.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

import fitz
from verify_partial_word_preservation import shapes, signature

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return json.loads(path.read_text())


def inventory(text):
    return Counter(c for c in text if not c.isspace())


def lexemes(text):
    return re.findall(r'(?<![\d.])[-+]?(?:\d+(?:\.\d+)?|\.\d+)%?(?![\d.])', text)


def compare_artifacts(before, after, name, suffix):
    a, b, media_a = shapes(before/(name+suffix+'.docx'))
    c, d, media_b = shapes(after/(name+suffix+'.docx'))
    assert Counter(map(signature,a)) == Counter(map(signature,c)), name+' source frames'
    assert Counter(map(signature,b)) == Counter(map(signature,d)), name+' masks'
    assert media_a == media_b, name+' original scan bytes'
    with fitz.open(before/(name+suffix+'.pdf')) as x, fitz.open(after/(name+suffix+'.pdf')) as y:
        assert len(x) == len(y) == 1
        words = 0
        for old, new in zip(x,y):
            # Preserve sequence and all repeated tokens; never select unique
            # numbers only or round boxes until a difference disappears.
            assert old.get_text('words') == new.get_text('words'), name+' actual Office words/boxes/order'
            assert old.get_pixmap(alpha=False).samples == new.get_pixmap(alpha=False).samples, name+' Office pixels'
            words += len(old.get_text('words'))
    return {'file':name, 'variant':suffix or 'direct', 'sourceFrames':len(a), 'masks':len(b),
            'officeWords':words, 'framesMasksStyleRanksExact':True, 'sourceMediaExact':True,
            'officeWordsBoxesOrderExact':True, 'officeRenderedPixelsExact':True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, default=ROOT/'qa-samples/report')
    parser.add_argument('--after-prefix', default='iteration10-b4')
    parser.add_argument('--edit-report', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    manifest_path = ROOT/'qa-samples/generated/cloud-iteration8/expected.json'
    manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    assert manifest_hash == '601446606c22484a02a65e51668796c675049633c66ece508e45b5c725d59b3a'
    manifest = load(manifest_path)
    cases, artifacts, matrices = [], [], {}
    for scope, key in [('word','cases'), ('containers','containerCases')]:
        folders = {'safety':args.reports/('iteration8-safety-'+scope),
                   'previous':args.reports/('iteration8-final-'+scope),
                   'after':args.reports/(args.after_prefix+'-'+scope)}
        reports = {}
        for label, folder in folders.items():
            report = load(folder/'report.json')
            assert report['manifest'] == manifest
            assert len(report['cases']) == len(manifest[key]) and all(c['success'] for c in report['cases'])
            reports[label] = {c['file']:c for c in report['cases']}
            identity = load(folder/'artifact-provenance.json')
            matrices[label+'-'+scope] = {'identity':{k:v for k,v in identity.items() if k!='buildInputs'},
                'resources':load(folder/'resources.json'), 'health':report['health']}
        for truth in manifest[key]:
            name = truth['file']
            assert hashlib.sha256((manifest_path.parent/name).read_bytes()).hexdigest() == truth['sha256']
            a, b, c = (reports[k][name] for k in ['safety','previous','after'])
            assert b.get('text') == c.get('text'), name+' previous TXT order/boundaries'
            assert b.get('metrics') == c.get('metrics'), name+' TXT completeness'
            assert inventory(a.get('text','')) == inventory(c.get('text','')), name+' recognized inventory'
            for field in ['editableText','officeText','officeMetrics','wordMetrics','pages','expectedFailureVerified',
                          'originalScanPixelsPreserved']:
                assert a.get(field) == b.get(field) == c.get(field), (name,field)
            assert b.get('officeEditVerified') == c.get('officeEditVerified'), name+' previously accepted edit'
            if a.get('officeEditVerified') is not None:
                assert a['officeEditVerified'] == c.get('officeEditVerified')
            if 'scan' in c:
                for field in ['editableText','mediaSha256','originalScanPixelsPreserved','metrics','officeMetrics',
                              'sourceMarginRgb','renderedMarginRgb','marginMaxChannelDelta','unrecognizedInkProbes']:
                    assert a['scan'][field] == b['scan'][field] == c['scan'][field], (name,field)
            seconds = c.get('seconds',{})
            if 'scan' in c: seconds = {**seconds, **{'scan-'+k:v for k,v in c['scan']['seconds'].items()}}
            assert all(v < 120 for v in seconds.values()), name+' per-conversion timeout'
            if not c.get('expectedFailureVerified'):
                for label in ['safety','previous']:
                    for suffix in ['', '.scan'] if scope=='word' else ['']:
                        artifacts.append({'baseline':label, **compare_artifacts(folders[label],folders['after'],name,suffix)})
            def measurement(record):
                return {k:v for k,v in record.items() if k not in ['text','editableText','officeText','docxBytes','pdfBytes']}
            cases.append({'file':name,'scope':scope,'sourceSha256':truth['sha256'],
                'safety':measurement(a), 'previous':measurement(b), 'after':measurement(c),
                'previousTxtExact':True, 'originalWordOfficeTextAndNumericBoundariesExact':True,
                'observedTxtNumberLexemes':lexemes(c.get('text','')),
                'observedWordNumberLexemes':lexemes(c.get('editableText','')),
                'expectedLines':truth['expectedLines']})
    edit = load(args.edit_report/'report.json')
    assert edit['success'] and edit['originalMediaExact'] and edit['numericLexemesAndBoxesExact']
    edit_identity = load(args.edit_report/'artifact-provenance.json')
    assert edit_identity['jarSha256'] == matrices['after-word']['identity']['jarSha256']
    result = {'scope':'Frozen Linux bundled OCR/API/Word/PDF/OFD artifact regression; no native acceptance',
        'manifestSha256':manifest_hash, 'fonts':manifest['fonts'], 'versions':manifest['versions'],
        'runtime':load(ROOT/'desktop/.runtime/ocr/OCR-RUNTIME.json'), 'matrices':matrices, 'cases':cases,
        'layoutPreservation':artifacts, 'scanEdit':{'measurements':edit,
            'identity':{k:v for k,v in edit_identity.items() if k!='buildInputs'},
            'resources':load(args.edit_report/'resources.json')},
        'limits':['All text/number equality is regression parity, not equality to truth or completeness.',
            'Resource runs are not isolated benchmarks; successful calls stayed within the120-second conversion budget.',
            'Safety image report did not run bilingual editing; prior final and current reports separately did and passed.',
            'Test-only changes and evidence scripts reuse exactly the accepted JAR bytes and production build inputs.',
            'Native macOS/Windows installers, Microsoft Word, private corpus, arbitrary edits/reflow and Word rotation unrun.']}
    args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'contracts':len(cases),'artifactComparisons':len(artifacts),
                      'scanEdit':edit['success'],'jarSha256':edit_identity['jarSha256']}))


if __name__ == '__main__':
    main()
