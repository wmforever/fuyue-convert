#!/usr/bin/env python3
"""Package rejected public synthetic Word experiments separately from the app build."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIOR_SHA = '2528c056a2c4582bfe3b26c8f82ea1f19bab24c074ce18d2c4d7ddb33b67f747'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--prior-bundle', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    git = lambda *options: subprocess.check_output(['git', *options], cwd=ROOT)
    assert not git('status', '--porcelain').strip(), 'Commit evidence before packaging'
    revision = git('rev-parse', 'HEAD').decode().strip()
    assert args.prior_bundle.stat().st_size == 107488113 and sha(args.prior_bundle.read_bytes()) == PRIOR_SHA
    baseline = json.loads((args.baseline / 'report.json').read_text())
    assert 'synthetic' in baseline['manifest']['provenance'].lower()
    report = json.loads((args.report / 'report.json').read_text())
    assert report['adopted'] is False and report['actualOfficeEditVerified']['originalRemoved']
    assert len(report['cases']) == 24 and all(c['xmlTextMasksMediaIdentical'] for c in report['cases'])
    cases = sorted({c['file'] for c in report['cases']})
    truths = {c['file']: c for c in baseline['manifest']['cases']}
    entries = {}

    def put(name, data):
        assert name not in entries and not name.startswith('/') and '..' not in Path(name).parts
        entries[name] = data

    def file(name, path):
        assert not path.is_symlink()
        put(name, path.read_bytes())

    for path in sorted(args.report.iterdir()):
        if path.is_file() and path.suffix in ['.json', '.html', '.docx', '.pdf', '.png']:
            file('review/' + path.name, path)
    for name in cases:
        for suffix in ['.scan.docx', '.scan.pdf']:
            file('baseline/' + name + suffix, args.baseline / (name + suffix))
        sample = args.samples / name
        assert sha(sample.read_bytes()) == truths[name]['sha256']
        file('samples/' + name, sample)
    put('samples/expected.json', json.dumps({'provenance': baseline['manifest']['provenance'],
        'cases': [truths[name] for name in cases], 'fonts': baseline['manifest'].get('fonts', {})},
        ensure_ascii=False, indent=2).encode())
    for name in ['cloud-ocr-iteration5.md', 'cloud-word-line-frame-results-20261003.json']:
        file('evidence/' + name, ROOT / 'docs' / name)
    for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md']:
        file('licenses/' + name, ROOT / name)
    for path in (ROOT / 'task-service/src/main/resources/fonts').glob('*.txt'):
        file('licenses/' + path.name, path)
    put('source/fuyue-convert-' + revision + '.tar.gz', gzip.compress(git('archive', '--format=tar', revision), mtime=0))
    put('README.md', f'''# Rejected Word line-frame experiment review

Evidence/source commit: {revision}
Baseline app commit: 11f6ce779b118c623cebeeccb7bfe3e430f0f39a
Baseline app JAR SHA256: 9173918803d13de7ebde2a8bffea371a6040a2a08517222b25c9339a43e55c5c
No production renderer change was adopted. This packet is separate from the unchanged Linux cloud app bundle, SHA256 {PRIOR_SHA}.

Open review/index.html for baseline/candidate Office renderings and separate extraction/geometry metrics. Actual editable DOCX and PDF files are included. Read evidence/cloud-ocr-iteration5.md for rejection reasons, runtime versions, limitations and reproducible commands. The source archive is the exact committed source; no JAR/runtime/installer is supplied here.

All document/image inputs are public synthetic data. No secrets, server logs, task data or Office profiles are included. Word/PDF ordering and original-source glyph fidelity remain unresolved; native macOS/Windows and Microsoft Word remain unrun. No Library delivery is claimed because its supported connection is blocked.

Verify from the extracted root: sha256sum -c SHA256SUMS.txt
'''.encode())
    put('SHA256SUMS.txt', ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(entries.items())).encode())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, data in sorted(entries.items()):
            item = zipfile.ZipInfo(name, (2026, 10, 3, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.create_system = 3
            item.external_attr = 0o100644 << 16
            archive.writestr(item, data)
    with zipfile.ZipFile(args.out) as archive:
        assert archive.testzip() is None
        for line in archive.read('SHA256SUMS.txt').decode().splitlines():
            expected, name = line.split('  ', 1)
            assert sha(archive.read(name)) == expected
    result = {'path': str(args.out.resolve()), 'bytes': args.out.stat().st_size,
              'sha256': sha(args.out.read_bytes()), 'sourceRevision': revision,
              'entries': len(entries), 'adopted': False, 'priorBundlePreserved': True}
    args.out.with_suffix(args.out.suffix + '.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
