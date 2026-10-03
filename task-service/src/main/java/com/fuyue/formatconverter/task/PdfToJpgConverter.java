package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public final class PdfToJpgConverter extends PdfToImageConverter {
    public PdfToJpgConverter() {
        super(DocumentFormat.JPG, "jpg", "-jpeg", "将 PDF 全部或指定页面渲染为 JPEG；选中一页输出图片，多页打包 ZIP。");
    }

    public PdfToJpgConverter(Path popplerBinary) {
        super(DocumentFormat.JPG, "jpg", "-jpeg", "将 PDF 全部或指定页面渲染为 JPEG；选中一页输出图片，多页打包 ZIP。", popplerBinary);
    }

    PdfToJpgConverter(Path popplerBinary, float dpi) {
        super(DocumentFormat.JPG, "jpg", "-jpeg", "将 PDF 全部或指定页面渲染为 JPEG；选中一页输出图片，多页打包 ZIP。",
                popplerBinary, dpi);
    }
}
