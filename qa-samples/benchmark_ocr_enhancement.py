#!/usr/bin/env python3
"""Compare enhancement pixels and median elapsed time against a Git baseline.

Requires JDK 17 on PATH. Uses temporary directories, no production dependencies.
Run without other CPU-intensive jobs; timings are evidence, not a CI assertion.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'task-service/src/main/java/com/fuyue/formatconverter/task/OcrContrastEnhancer.java'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', default='e3ffde164e90e766b7a9d6e3b1fae3f53b94d7d5')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='ocr-enhancement-') as temporary:
    results = []
    for label in ['before', 'after']:
        path = Path(temporary) / label
        path.mkdir()
        source = path / 'OcrContrastEnhancer.java'
        source.write_bytes(subprocess.check_output(['git', 'show', f'{args.baseline}:{SOURCE}'], cwd=ROOT)
                           if label == 'before' else (ROOT / SOURCE).read_bytes())
        subprocess.run(['javac', '-d', str(path), str(source), str(ROOT / 'qa-samples/OcrEnhancementBenchmark.java')], check=True)
        result = subprocess.check_output(['java', '-Xms256m', '-Xmx512m', '-cp', str(path),
                                          'com.fuyue.formatconverter.task.OcrEnhancementBenchmark'], text=True)
        print(label, result, sep='\n', flush=True)
        results.append([line.split() for line in result.splitlines()])
    assert [(r[0], r[2]) for r in results[0]] == [(r[0], r[2]) for r in results[1]], 'Pixel regression'
    for before, after in zip(*results):
        print(before[0], f'{100 * (1 - float(after[1]) / float(before[1])):.1f}% less elapsed time')
