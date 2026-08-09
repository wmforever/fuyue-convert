package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public final class PdfToJpgConverter extends PdfToImageConverter {
    public PdfToJpgConverter() {
        super(DocumentFormat.JPG, "jpg", "-jpeg", "将 PDF 按页渲染为 JPEG；多页 PDF 自动打包 ZIP。");
    }

    public PdfToJpgConverter(Path popplerBinary) {
        super(DocumentFormat.JPG, "jpg", "-jpeg", "将 PDF 按页渲染为 JPEG；多页 PDF 自动打包 ZIP。", popplerBinary);
    }

    PdfToJpgConverter(Path popplerBinary, float dpi) {
        super(DocumentFormat.JPG, "jpg", "-jpeg", "将 PDF 按页渲染为 JPEG；多页 PDF 自动打包 ZIP。",
                popplerBinary, dpi);
    }
}
