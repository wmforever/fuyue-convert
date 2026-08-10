package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
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
    private final String unavailableCode;
    private final String unavailableMessage;

    PdfOcrSupport(TesseractOcrConverter.Settings settings) {
        this.ocr = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        this.unavailableCode = null;
        this.unavailableMessage = null;
    }

    PdfOcrSupport(TesseractOcrConverter.Capability capability) {
        this.ocr = capability.available() ? new TesseractOcrConverter(DocumentFormat.PNG, capability.settings()) : null;
        this.unavailableCode = capability.errorCode();
        this.unavailableMessage = capability.message();
    }

    DocumentModel recognizeMissingPages(Path source, DocumentModel parsed, Path workDir,
                                        ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        requireCompletePageModel(parsed);
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
                requireAvailable(page.pageNumber());
                var pdfPage = pdf.getPage(index);
                var crop = pdfPage.getCropBox();
                double unit = pdfPage.getUserUnit();
                if (!Double.isFinite(unit) || unit <= 0) unit = 1d;
                ConversionGuards.requireRenderBounds(crop.getWidth() * unit, crop.getHeight() * unit,
                        OCR_DPI, limits);
                ocr.requireRenderedPageWithinOcrLimit(page.physicalBox(), OCR_DPI);
                progress.update(TaskStage.RECOGNIZING,
                        30 + (int) ((index + 1) * 35d / Math.max(1, parsed.pages().size())));
                Path image = workDir.resolve("pdf-ocr-page-%04d.png".formatted(page.pageNumber()));
                if (!ImageIO.write(renderer.renderImageWithDPI(index, OCR_DPI, ImageType.RGB),
                        "png", image.toFile())) {
                    throw new java.io.IOException("无法写入 PDF OCR 页面图片");
                }
                TesseractOcrConverter.RecognitionResult recognized = ocr.recognizeLayoutResult(
                        image, workDir.resolve("page-%04d".formatted(page.pageNumber())), page.pageNumber(),
                        page.physicalBox(), limits);
                ocr.requireUsableResult(recognized, "PDF 第 " + page.pageNumber() + " 页");
                List<ConversionWarning> warnings = new ArrayList<>(page.warnings());
                warnings.addAll(ocr.warningsFor(recognized, page.pageNumber(),
                        "PDF 第 " + page.pageNumber() + " 页"));
                pages.add(new PageModel(page.pageNumber(), page.physicalBox(), recognized.blocks(), page.lines(),
                        page.images(), List.of(), List.of(), warnings));
            }
        }
        return new DocumentModel(parsed.sourceName(), parsed.parserName() + (ocr == null ? "" : " + Tesseract"),
                parsed.sourcePageCount(), pages, documentWarnings);
    }

    private void requireAvailable(int pageNumber) throws ConversionFailureException {
        if (ocr == null) {
            throw new ConversionFailureException(unavailableCode == null ? "OCR_ENGINE_UNAVAILABLE" : unavailableCode,
                    "PDF 第 " + pageNumber + " 页需要 OCR；" +
                            (unavailableMessage == null ? "本地 OCR 引擎不可用" : unavailableMessage));
        }
    }

    private void requireCompletePageModel(DocumentModel parsed) throws ConversionFailureException {
        if (parsed.pages().size() != parsed.sourcePageCount()) {
            throw new ConversionFailureException("OCR_PAGE_MISSING", "PDF 页面模型不完整，拒绝静默漏页");
        }
        for (int index = 0; index < parsed.pages().size(); index++) {
            if (parsed.pages().get(index).pageNumber() != index + 1) {
                throw new ConversionFailureException("OCR_PAGE_MISSING", "PDF 页面编号不连续，拒绝静默漏页");
            }
        }
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
