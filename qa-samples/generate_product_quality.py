#!/usr/bin/env python3
"""Generate public synthetic cross-format controls; outputs remain Git-ignored."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

import fitz

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / 'qa-samples/generated/product-quality')
    args = parser.parse_args()
    native = ROOT / 'qa-samples/generated/native-tables44-fixed'
    if not (native / 'expected.json').is_file():
        subprocess.run([sys.executable, str(ROOT / 'qa-samples/generate_native_tables44.py')], check=True)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)  # Never replace frozen evidence.
    sources, actions = {}, []

    def action(name, source, target):
        if not source.startswith('@'):
            sources[source] = hashlib.sha256((out / source).read_bytes()).hexdigest()
        actions.append(dict(id=name, input=source, target=target))

    for lang in ['en', 'zh']:
        name = lang + '-continuation.pdf'
        shutil.copyfile(native / name, out / name)
        action(lang + '-table-word', name, 'docx')
        action(lang + '-table-office', '@' + lang + '-table-word', 'pdf')

    csv = '编号,说明,金额,原文本\r\n00042,"中文,逗号",-0017.50,=1+1\r\n00084,"含""引号""和\n换行",+0035.00,@sample\r\n'
    (out / 'records.csv').write_bytes(csv.encode('utf-8'))
    action('csv-workbook', 'records.csv', 'xlsx')
    action('csv-roundtrip', '@csv-workbook', 'csv')
    action('csv-office', '@csv-workbook', 'pdf')

    text = '第一页测试 REVIEW 00842\n金额 -0017.50\f第二页审核 REVIEW 00084\n金额 +0035.00\n'
    (out / 'pages.txt').write_bytes(b'\xff\xfe' + text.encode('utf-16le'))
    action('text-word', 'pages.txt', 'docx')
    action('text-office', '@text-word', 'pdf')
    action('text-roundtrip', '@text-word', 'txt')
    action('pdf-text', '@text-office', 'txt')

    with fitz.open() as pdf:
        pdf.new_page()
        pdf.new_page()
        pdf.save(out / 'blank.pdf')
    action('blank-word', 'blank.pdf', 'docx')
    action('blank-office', '@blank-word', 'pdf')
    action('blank-text', 'blank.pdf', 'txt')
    (out / 'broken.pdf').write_bytes(b'%PDF-1.7\nNot a PDF\n')
    action('malformed-pdf', 'broken.pdf', 'docx')

    manifest = dict(sources=sources, actions=actions, truth=dict(pages=text, csv=csv),
                    sourceTables=json.loads((native / 'expected.json').read_text()),
                    generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    pymupdfVersion=fitz.VersionBind,
                    provenance='Original synthetic fixtures only; no private user documents',
                    expectedFailure=dict(action='malformed-pdf', downloadReady=False),
                    acceptance='Inspect actual Office pages, logical cells, literal signed numbers and editable text; HTTP success alone is insufficient')
    (out / 'expected.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(actions=len(actions), manifest=str(out / 'expected.json'))))


if __name__ == '__main__':
    main()
