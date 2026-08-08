package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromV;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PdfToDocxConverter implements FileConverter {
    private static final double EMU_PER_POINT = 12_700d;
    private static final double RENDER_DPI = 160d;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.DOCX,
            "将 PDF 转换为版式优先 DOCX：每页以保真底图还原版面。",
            QualityLevel.EXPERIMENTAL, ConversionStrategy.FIDELITY, List.of(),
            List.of("生成页面图层 DOCX，正文结构编辑能力有限", "有 Poppler 时优先使用，否则回退 PDFBox"));
    private final Path popplerBinary;

    public PdfToDocxConverter() {
        this(discoverPoppler().orElse(null));
    }

    PdfToDocxConverter(Path popplerBinary) {
        this.popplerBinary = popplerBinary == null ? null : popplerBinary.toAbsolutePath().normalize();
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        progress.update(TaskStage.PARSING, 20);
        List<PageGeometry> geometries = readPageGeometries(input.path(), limits);
        for (PageGeometry geometry : geometries) {
            ConversionGuards.requireRenderBounds(geometry.widthPoints(), geometry.heightPoints(), RENDER_DPI, limits);
        }
        List<RenderedPage> pages = renderPages(input.path(), workDir, geometries, limits, progress);
        if (pages.isEmpty()) throw new IOException("PDF 未渲染出任何页面");
        progress.update(TaskStage.RENDERING, 80);
        try (XWPFDocument document = new XWPFDocument()) {
            for (int i = 0; i < pages.size(); i++) {
                XWPFParagraph paragraph = document.createParagraph();
                configureMarker(paragraph);
                XWPFRun run = paragraph.createRun();
                addPageImage(run, pages.get(i));
                if (i < pages.size() - 1) {
                    CTPPr properties = paragraph.getCTP().isSetPPr()
                            ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
                    configureSection(properties.addNewSectPr(), pages.get(i).geometry(), true);
                }
            }
            configureFinalSection(document, pages.get(pages.size() - 1).geometry());
            try (var out = Files.newOutputStream(outputPath)) { document.write(out); }
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 转 DOCX");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", ".docx"), pages.size(), List.of());
    }

    private List<PageGeometry> readPageGeometries(Path pdf, ParseLimits limits) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw new IOException("PDF 没有可转换页面");
            if (pages > limits.maxPages()) throw new IOException("PDF 页数超过限制：" + pages + " > " + limits.maxPages());
            List<PageGeometry> result = new ArrayList<>(pages);
            for (PDPage page : document.getPages()) {
                PDRectangle box = page.getCropBox();
                int rotation = Math.floorMod(page.getRotation(), 360);
                boolean sideways = rotation == 90 || rotation == 270;
                result.add(new PageGeometry(sideways ? box.getHeight() : box.getWidth(),
                        sideways ? box.getWidth() : box.getHeight()));
            }
            return result;
        }
    }

    private List<RenderedPage> renderPages(Path pdf, Path workDir, List<PageGeometry> geometries, ParseLimits limits,
                                           ConversionProgress progress) throws Exception {
        if (popplerBinary == null) return renderPagesWithPdfBox(pdf, workDir, geometries, limits, progress);
        Path renderDir = Files.createDirectories(workDir.resolve("pdf-pages"));
        Path prefix = renderDir.resolve("page");
        List<String> command = List.of(popplerBinary.toString(), "-r", Integer.toString((int) RENDER_DPI), "-png",
                pdf.toString(), prefix.toString());
        ConversionGuards.runProcess(command, workDir.resolve("pdftoppm.log"), Duration.ofMinutes(2), "PDF 页面渲染");
        try (var files = Files.list(renderDir)) {
            List<Path> images = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("page-\\d+\\.png"))
                    .sorted(Comparator.comparingInt(this::pageNumber))
                    .toList();
            if (images.size() != geometries.size()) throw new IOException("PDF 渲染页数不一致：" + images.size() + " != " + geometries.size());
            ConversionGuards.requireTotalSize(images, limits, "PDF 页面渲染");
            List<RenderedPage> pages = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                ConversionGuards.requireImageBounds(images.get(i), limits);
                pages.add(new RenderedPage(images.get(i), geometries.get(i)));
            }
            progress.update(TaskStage.RENDERING, 60);
            return pages;
        }
    }

    private List<RenderedPage> renderPagesWithPdfBox(Path pdf, Path workDir, List<PageGeometry> geometries,
                                                     ParseLimits limits, ConversionProgress progress) throws IOException {
        Path renderDir = Files.createDirectories(workDir.resolve("pdfbox-pages"));
        List<RenderedPage> pages = new ArrayList<>(geometries.size());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < geometries.size(); i++) {
                Path image = renderDir.resolve("page-%04d.png".formatted(i + 1));
                BufferedImage bitmap = renderer.renderImageWithDPI(i, (float) RENDER_DPI, ImageType.RGB);
                if (!ImageIO.write(bitmap, "png", image.toFile())) throw new IOException("PDFBox 无法写出 PNG 页面");
                ConversionGuards.requireImageBounds(image, limits);
                pages.add(new RenderedPage(image, geometries.get(i)));
                progress.update(TaskStage.RENDERING, 25 + (int) ((i + 1) * 35d / geometries.size()));
            }
        }
        ConversionGuards.requireTotalSize(pages.stream().map(RenderedPage::image).toList(), limits, "PDF 页面渲染");
        return pages;
    }

    private int pageNumber(Path path) {
        String name = path.getFileName().toString();
        int dash = name.indexOf('-');
        int dot = name.lastIndexOf('.');
        if (dash >= 0 && dot > dash) {
            try {
                return Integer.parseInt(name.substring(dash + 1, dot));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void addPageImage(XWPFRun run, RenderedPage page) throws IOException, InvalidFormatException {
        try (var in = Files.newInputStream(page.image())) {
            run.addPicture(in, Document.PICTURE_TYPE_PNG, page.image().getFileName().toString(),
                    (int) Math.round(page.geometry().widthPoints() * EMU_PER_POINT),
                    (int) Math.round(page.geometry().heightPoints() * EMU_PER_POINT));
            anchorLastPicture(run);
        }
    }

    private void anchorLastPicture(XWPFRun run) {
        if (run.getCTR().sizeOfDrawingArray() == 0) return;
        CTDrawing drawing = run.getCTR().getDrawingArray(run.getCTR().sizeOfDrawingArray() - 1);
        if (drawing.sizeOfInlineArray() == 0) return;
        CTInline inline = drawing.getInlineArray(0);
        CTAnchor anchor = drawing.addNewAnchor();
        anchor.setSimplePos2(false);
        anchor.setRelativeHeight(0);
        anchor.setBehindDoc(false);
        anchor.setLocked(false);
        anchor.setLayoutInCell(true);
        anchor.setAllowOverlap(true);
        anchor.setDistT(0);
        anchor.setDistB(0);
        anchor.setDistL(0);
        anchor.setDistR(0);
        anchor.addNewSimplePos().setX(0);
        anchor.getSimplePos().setY(0);
        anchor.addNewPositionH().setRelativeFrom(STRelFromH.PAGE);
        anchor.getPositionH().setPosOffset(0);
        anchor.addNewPositionV().setRelativeFrom(STRelFromV.PAGE);
        anchor.getPositionV().setPosOffset(0);
        anchor.setExtent(inline.getExtent());
        if (inline.isSetEffectExtent()) anchor.setEffectExtent(inline.getEffectExtent());
        anchor.addNewWrapNone();
        anchor.setDocPr(inline.getDocPr());
        if (inline.isSetCNvGraphicFramePr()) anchor.setCNvGraphicFramePr(inline.getCNvGraphicFramePr());
        anchor.setGraphic(inline.getGraphic());
        drawing.removeInline(0);
    }

    private void configureMarker(XWPFParagraph paragraph) {
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setIndentationLeft(0);
        paragraph.setIndentationRight(0);
        CTPPr properties = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTSpacing spacing = properties.isSetSpacing() ? properties.getSpacing() : properties.addNewSpacing();
        spacing.setBefore(BigInteger.ZERO);
        spacing.setAfter(BigInteger.ZERO);
        spacing.setLine(BigInteger.ONE);
        spacing.setLineRule(STLineSpacingRule.EXACT);
    }

    private void configureFinalSection(XWPFDocument document, PageGeometry page) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        configureSection(section, page, false);
    }

    private void configureSection(CTSectPr section, PageGeometry page, boolean nextPage) {
        if (nextPage) {
            CTSectType type = section.isSetType() ? section.getType() : section.addNewType();
            type.setVal(STSectionMark.NEXT_PAGE);
        }
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(twips(page.widthPoints())));
        size.setH(BigInteger.valueOf(twips(page.heightPoints())));
        if (page.widthPoints() > page.heightPoints()) size.setOrient(STPageOrientation.LANDSCAPE);
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margin.setTop(BigInteger.ZERO);
        margin.setBottom(BigInteger.ZERO);
        margin.setLeft(BigInteger.ZERO);
        margin.setRight(BigInteger.ZERO);
        margin.setHeader(BigInteger.ZERO);
        margin.setFooter(BigInteger.ZERO);
        margin.setGutter(BigInteger.ZERO);
    }

    private int twips(double points) { return (int) Math.round(points * 20d); }

    private static Optional<Path> discoverPoppler() {
        List<Path> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) candidates.add(Path.of(dir, "pdftoppm"));
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private record PageGeometry(double widthPoints, double heightPoints) {}
    private record RenderedPage(Path image, PageGeometry geometry) {}
}
