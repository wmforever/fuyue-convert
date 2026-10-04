#!/usr/bin/env python3
"""Generate a QA-only fixed local-gain variant from exact current enhancer source."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    source=ROOT/'task-service/src/main/java/com/fuyue/formatconverter/task/OcrContrastEnhancer.java'
    text=source.read_text();out=ROOT/'qa-samples/work/iteration41-classes';out.mkdir(exist_ok=False)
    replacements=[
        ('OcrContrastEnhancer','OcrLocalContrastCandidate41'),
        ('long[] contrastHistogram = new long[256];','long[] contrastHistogram = new long[256];\n        int[][] localHistograms = new int[columns * rows][256];'),
        ('contrastHistogram[paper.difference(x, luminance(pixels[x]))]++;','int delta = paper.difference(x, luminance(pixels[x]));\n                contrastHistogram[delta]++;\n                localHistograms[(y / tile) * columns + x / tile][delta]++;'),
        ('contrast = Math.max(12, contrast);','''contrast = Math.max(12, contrast);
        int floor = Math.max(12, (contrast + 3) / 4);
        int[] localContrasts = new int[columns * rows];
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            int cell = row * columns + column;
            int area = (Math.min(width, (column + 1) * tile) - column * tile)
                    * (Math.min(height, (row + 1) * tile) - row * tile);
            localContrasts[cell] = Math.max(floor, Math.min(contrast,
                    percentile(localHistograms[cell], (int) Math.ceil(area * .99))));
        }
        PaperRows localPaper = new PaperRows(localContrasts, columns, rows, tile, width, height);'''),
        ('byte[] output = new byte[width];','byte[] output = new byte[width];'),
        ('for (int y = 0; y < height; y++) {\n            paper.prepare(y);\n            source.getRGB(0, y, width, 1, pixels, 0, width);\n            for (int x = 0; x < width; x++) {\n                int delta = paper.difference(x, luminance(pixels[x]));\n                output[x] = (byte) (255 - Math.min(255, Math.max(0, delta - 2) * 255 / contrast));',
         'for (int y = 0; y < height; y++) {\n            paper.prepare(y);\n            localPaper.prepare(y);\n            source.getRGB(0, y, width, 1, pixels, 0, width);\n            for (int x = 0; x < width; x++) {\n                int delta = paper.difference(x, luminance(pixels[x]));\n                int denominator = Math.max(floor, Math.min(contrast, localPaper.difference(x, 0)));\n                output[x] = (byte) (255 - Math.min(255, Math.max(0, delta - 2) * 255 / denominator));')]
    for old,new in replacements:
        if old=='OcrContrastEnhancer':assert text.count(old)==2
        else:assert text.count(old)==1,(old,text.count(old))
        text=text.replace(old,new)
    path=out/'OcrLocalContrastCandidate41.java';path.write_text(text)
    (out/'candidate-provenance.json').write_text(json.dumps(dict(source=str(source.relative_to(ROOT)),sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),candidateSha256=hashlib.sha256(path.read_bytes()).hexdigest(),productionChanged=False,implementation='fixed local99percentile contrast,global/4 floor,unchanged original background/pixels/bounds;no truth access'),indent=2)+'\n')
    print('QA-only candidate source frozen')
if __name__=='__main__':main()
