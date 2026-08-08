package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public final class PdfToPngConverter extends PdfToImageConverter {
    public PdfToPngConverter() {
        super(DocumentFormat.PNG, "png", "-png", "将 PDF 按页渲染为 PNG；多页 PDF 自动打包 ZIP。");
    }

    public PdfToPngConverter(Path popplerBinary) {
        super(DocumentFormat.PNG, "png", "-png", "将 PDF 按页渲染为 PNG；多页 PDF 自动打包 ZIP。", popplerBinary);
    }
}
