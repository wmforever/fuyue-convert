#!/usr/bin/env python3
"""Compare completed frozen public OCR reports without redefining their truth."""
import argparse
import hashlib
import json
from pathlib import Path


def load(path):
    return json.loads(path.read_text())


def record(directory):
    return {'report': load(directory / 'report.json'),
            'resources': load(directory / 'resources.json'),
            'artifact': {k: v for k, v in load(directory / 'artifact-provenance.json').items()
                         if k != 'buildInputs'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--safety', type=Path, nargs='+', help='Additional frozen safety-head reports covering the after matrix')
    parser.add_argument('--columns-safety', type=Path)
    parser.add_argument('--columns-before', type=Path, nargs='+', required=True)
    parser.add_argument('--columns-after', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    before, after = record(args.before), record(args.after)
    old_columns = [record(path) for path in args.columns_before]
    new_columns = record(args.columns_after)

    def compare(old_reports, new_report):
        previous = {c['file']: c for r in old_reports for c in r['report']['cases']}
        old_truth = {c['file']: c for r in old_reports for c in r['report']['manifest']['cases']}
        truth = {c['file']: c for c in new_report['report']['manifest']['cases']}
        rows = []
        for case in new_report['report']['cases']:
            name = case['file']
            assert truth[name]['sha256'] == old_truth[name]['sha256'], 'Different source: ' + name
            assert truth[name].get('expectedLines') == old_truth[name].get('expectedLines'), 'Different truth: ' + name
            fields = ['success', 'metrics', 'seconds', 'warnings', 'editableMatchesTxt',
                      'officeEditVerified', 'officeMetrics', 'pages', 'expectedErrors']
            def measurements(value):
                result = {k: value[k] for k in fields if k in value}
                if 'scan' in value:
                    result['scan'] = {k: value['scan'][k] for k in ['originalScanPixelsPreserved',
                        'mediaSha256', 'marginMaxChannelDelta', 'unrecognizedInkProbes',
                        'metrics', 'officeMetrics', 'seconds'] if k in value['scan']}
                return result
            rows.append({'file': name, 'sourceSha256': truth[name]['sha256'],
                         'before': measurements(previous[name]), 'after': measurements(case)})
        return rows

    def identity(value):
        return {**value['artifact'], 'resources': value['resources'],
                'health': value['report']['health'], 'fonts': value['report']['manifest'].get('fonts'),
                'pillow': value['report']['manifest'].get('pillow')}

    result = {'scope': 'Public synthetic Linux bundled-runtime review; no native installer acceptance',
              'fullCases': compare([before], after), 'txtOnlyColumns': compare(old_columns, new_columns),
              'before': identity(before), 'after': identity(after),
              'columnsBefore': [identity(r) for r in old_columns], 'columnsAfter': identity(new_columns),
              'limitations': ['Higher CER is a regression even when a conversion succeeds.',
                              'Aligned character recall is not proof of semantic completeness.',
                              'Resource counters are whole-run child usage, not isolated per-worker peaks.',
                              'TXT-only column reports do not validate Word or Office.']}
    if args.safety:
        safety = [record(path) for path in args.safety]
        result['safetyCases'] = compare(safety, after)
        result['safety'] = [identity(value) for value in safety]
    if args.columns_safety:
        safety_columns = record(args.columns_safety)
        result['safetyColumns'] = compare([safety_columns], new_columns)
        result['columnsSafety'] = identity(safety_columns)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'fullCases': len(result['fullCases']), 'txtOnlyColumns': len(result['txtOnlyColumns']),
                      'summarySha256': hashlib.sha256(args.out.read_bytes()).hexdigest()}))


if __name__ == '__main__':
    main()
