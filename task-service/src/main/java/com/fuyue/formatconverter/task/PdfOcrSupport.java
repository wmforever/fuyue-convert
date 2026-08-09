package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class PdfOcrSupport {
    private static final float OCR_DPI = 300f;
    private final TesseractOcrConverter ocr;

    PdfOcrSupport(TesseractOcrConverter.Settings settings) {
        this.ocr = new TesseractOcrConverter(DocumentFormat.PNG, settings);
    }

    DocumentModel recognizeMissingPages(Path source, DocumentModel parsed, Path workDir,
                                        ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        List<PageModel> pages = new ArrayList<>(parsed.pages().size());
        List<ConversionWarning> documentWarnings = new ArrayList<>(parsed.warnings());
        try (var pdf = Loader.loadPDF(source.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int index = 0; index < parsed.pages().size(); index++) {
                PageModel page = parsed.pages().get(index);
                if (!page.textBlocks().isEmpty() || !hasVisibleContent(pdf.getPage(index))) {
                    pages.add(page);
                    continue;
                }
                var pdfPage = pdf.getPage(index);
                var crop = pdfPage.getCropBox();
                double unit = pdfPage.getUserUnit();
                if (!Double.isFinite(unit) || unit <= 0) unit = 1d;
                ConversionGuards.requireRenderBounds(crop.getWidth() * unit, crop.getHeight() * unit,
                        OCR_DPI, limits);
                progress.update(TaskStage.RECOGNIZING,
                        30 + (int) ((index + 1) * 35d / Math.max(1, parsed.pages().size())));
                Path image = workDir.resolve("pdf-ocr-page-%04d.png".formatted(page.pageNumber()));
                if (!ImageIO.write(renderer.renderImageWithDPI(index, OCR_DPI, ImageType.RGB),
                        "png", image.toFile())) {
                    throw new java.io.IOException("无法写入 PDF OCR 页面图片");
                }
                List<com.fuyue.formatconverter.model.TextBlock> recognized = ocr.recognizeLayout(
                        image, workDir.resolve("page-%04d".formatted(page.pageNumber())), page.pageNumber(),
                        page.physicalBox(), limits);
                if (recognized.isEmpty()) {
                    throw new ConversionFailureException("OCR_NO_TEXT",
                            "PDF 第 " + page.pageNumber() + " 页有可见内容，但 OCR 未识别到文字；未生成不完整结果。");
                }
                List<ConversionWarning> warnings = new ArrayList<>(page.warnings());
                ConversionWarning applied = ConversionWarning.of(WarningCode.OCR_APPLIED,
                        "PDF 第 " + page.pageNumber() + " 页已使用本地 Tesseract OCR，结果必须人工复核。",
                        page.pageNumber());
                warnings.add(applied);
                pages.add(new PageModel(page.pageNumber(), page.physicalBox(), recognized, page.lines(),
                        page.images(), List.of(), List.of(), warnings));
            }
        }
        return new DocumentModel(parsed.sourceName(), parsed.parserName() + " + Tesseract",
                parsed.sourcePageCount(), pages, documentWarnings);
    }

    private boolean hasVisibleContent(org.apache.pdfbox.pdmodel.PDPage page) throws Exception {
        if (!page.hasContents()) return !page.getAnnotations().isEmpty();
        try (InputStream input = page.getContents()) {
            int value;
            while ((value = input.read()) >= 0) {
                if (!Character.isWhitespace(value)) return true;
            }
        }
        return !page.getAnnotations().isEmpty();
    }
}
