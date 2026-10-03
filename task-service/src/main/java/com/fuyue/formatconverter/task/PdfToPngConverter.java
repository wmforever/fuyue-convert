package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public final class PdfToPngConverter extends PdfToImageConverter {
    public PdfToPngConverter() {
        super(DocumentFormat.PNG, "png", "-png", "将 PDF 全部或指定页面渲染为 PNG；选中一页输出图片，多页打包 ZIP。");
    }

    public PdfToPngConverter(Path popplerBinary) {
        super(DocumentFormat.PNG, "png", "-png", "将 PDF 全部或指定页面渲染为 PNG；选中一页输出图片，多页打包 ZIP。", popplerBinary);
    }

    PdfToPngConverter(Path popplerBinary, float dpi) {
        super(DocumentFormat.PNG, "png", "-png", "将 PDF 全部或指定页面渲染为 PNG；选中一页输出图片，多页打包 ZIP。",
                popplerBinary, dpi);
    }
}
