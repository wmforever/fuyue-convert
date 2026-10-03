#!/usr/bin/env python3
"""Write truth and hashes for the two exported OcrContrastEnhancementTest images.

Export them with -Dformat.converter.ocr-enhancement.qa-directory=<samples>.
This manifest states expected source text, never derives truth from OCR output.
"""
import argparse
import hashlib
import json
from pathlib import Path

ENGLISH = ['Invoice OCR 2026 total amount 12345'] * 8
CHINESE = ['文档转换测试，金额12345元。', '本地识别中文，无需上传文件。',
           '保留原始图像，文字可以编辑。', '扫描页面较暗，自动增强对比。',
           '请核对转换结果，检查数字标点。', '使用简体中文模型处理印刷文档。',
           '转换完成以后，保存并打开文档。', '图片位置保持不变，内容需要复核。']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=Path, required=True)
    args = parser.parse_args()
    cases = [{'file': name, 'expectedLines': lines,
              'sha256': hashlib.sha256((args.samples / name).read_bytes()).hexdigest()}
             for name, lines in [('shaded-source.png', ENGLISH), ('chinese-shaded-source.png', CHINESE)]]
    (args.samples / 'expected.json').write_text(json.dumps({
        'provenance': 'OcrContrastEnhancementTest generated fixtures; repository fonts; no user data',
        'cases': cases}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
