package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DefaultConverterRegistry {
    private DefaultConverterRegistry() { }

    public static List<FileConverter> create(Path officeBinary, Duration officeTimeout) {
        SafeOfdExtractor extractor = new SafeOfdExtractor();
        OfdrwParser parser = new OfdrwParser();
        PageLayoutAnalyzer analyzer = new PageLayoutAnalyzer();
        PoiDocxRenderer renderer = new PoiDocxRenderer();
        var ocrCapability = TesseractOcrConverter.detectConfigured();
        PdfOcrSupport pdfOcr = ocrCapability.enabled() ? new PdfOcrSupport(ocrCapability) : null;
        OfdOcrSupport ofdOcr = ocrCapability.enabled() ? new OfdOcrSupport(ocrCapability) : null;
        List<FileConverter> converters = new ArrayList<>();
        converters.add(new OfdToDocxConverter(extractor, parser, analyzer, renderer, ofdOcr));
        converters.add(new OfdToTextConverter(extractor, parser, analyzer, ofdOcr));
        converters.add(new OfdToPdfConverter(extractor, parser, analyzer));
        converters.add(new OfdToPngConverter(extractor, parser));
        converters.add(new OfdToJpgConverter(extractor, parser));
        converters.add(new OfdToXlsxConverter(extractor, parser, analyzer));
        converters.add(new CsvToXlsxConverter());
        converters.add(new XlsxToCsvConverter());
        converters.add(new TextToDocxConverter());
        converters.add(new DocxToTextConverter());
        converters.add(new TextToPdfConverter());
        converters.add(new PdfToTextConverter(new PdfLayoutParser(), analyzer, pdfOcr));
        converters.add(new PdfToDocxConverter(new PdfLayoutParser(), analyzer, renderer, pdfOcr));
        converters.add(new PdfToOfdConverter());
        converters.add(new PdfToPngConverter());
        converters.add(new PdfToJpgConverter());
        converters.add(new ImageToPdfConverter(DocumentFormat.PNG));
        converters.add(new ImageToPdfConverter(DocumentFormat.JPG));
        if (ocrCapability.available()) {
            converters.add(new TesseractOcrConverter(DocumentFormat.PNG, ocrCapability.settings()));
            converters.add(new TesseractOcrConverter(DocumentFormat.JPG, ocrCapability.settings()));
            converters.add(new ImageOcrToDocxConverter(DocumentFormat.PNG, ocrCapability.settings(), analyzer, renderer));
            converters.add(new ImageOcrToDocxConverter(DocumentFormat.JPG, ocrCapability.settings(), analyzer, renderer));
        } else if (ocrCapability.enabled()) {
            converters.add(new UnavailableOcrConverter(DocumentFormat.PNG, DocumentFormat.TXT, ocrCapability));
            converters.add(new UnavailableOcrConverter(DocumentFormat.JPG, DocumentFormat.TXT, ocrCapability));
            converters.add(new UnavailableOcrConverter(DocumentFormat.PNG, DocumentFormat.DOCX, ocrCapability));
            converters.add(new UnavailableOcrConverter(DocumentFormat.JPG, DocumentFormat.DOCX, ocrCapability));
        }

        if (officeBinary != null) {
            Path binary = officeBinary.toAbsolutePath().normalize();
            Duration timeout = officeTimeout == null ? Duration.ofMinutes(2) : officeTimeout;
            converters.add(new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, binary, timeout,
                    "使用 LibreOffice headless 将 DOCX 高保真导出为 PDF。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.XLSX, DocumentFormat.PDF, binary, timeout,
                    "使用 LibreOffice headless 将 XLSX 高保真导出为 PDF。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.PPTX, DocumentFormat.PDF, binary, timeout,
                    "使用 LibreOffice headless 将 PPTX 高保真导出为 PDF。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.WPS, DocumentFormat.DOCX, binary, timeout,
                    "使用 LibreOffice headless 将 WPS 文字文档转换为 DOCX。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.ET, DocumentFormat.XLSX, binary, timeout,
                    "使用 LibreOffice headless 将 WPS 表格 ET 转换为 XLSX。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.DPS, DocumentFormat.PPTX, binary, timeout,
                    "使用 LibreOffice headless 将 WPS 演示 DPS 转换为 PPTX。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.UOF, DocumentFormat.DOCX, binary, timeout,
                    "使用 LibreOffice headless 将 UOF 直接转换为可编辑 DOCX。"));
            converters.add(new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.UOF, binary, timeout,
                    "使用 LibreOffice UOF text 导出过滤器将 DOCX 写入真实 UOF XML。", "uof:UOF text"));
        } else {
            converters.add(new XlsxToPdfConverter());
            converters.add(new DocxToPdfConverter());
        }
        return List.copyOf(converters);
    }
}
