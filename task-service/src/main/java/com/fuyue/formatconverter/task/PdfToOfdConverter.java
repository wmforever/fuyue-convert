package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.ofdrw.font.Font;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;
import org.ofdrw.layout.element.canvas.Canvas;
import org.ofdrw.layout.element.canvas.FontSetting;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Creates a real OFD with a fidelity page image and source-coordinate text objects. */
public final class PdfToOfdConverter implements FileConverter {
    private static final float RENDER_DPI = 160f;
    private final PdfLayoutParser parser;
    private final Path popplerBinary;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.OFD,
            "生成真实 OFD 固定版式文件：页面图像层保留视觉，文字型 PDF 同时写入可检索的 OFD 文字对象层。",
            QualityLevel.EXPERIMENTAL, ConversionStrategy.FIDELITY, List.of(),
            List.of("当前以整页保真图像层承载复杂矢量和透明效果，尚未把表格、路径和原始图片全部重建为独立 OFD 对象"));

    public PdfToOfdConverter() {
        this(new PdfLayoutParser(), PdfToImageConverter.discoverPoppler().orElse(null));
    }

    PdfToOfdConverter(PdfLayoutParser parser) {
        this(parser, PdfToImageConverter.discoverPoppler().orElse(null));
    }

    PdfToOfdConverter(PdfLayoutParser parser, Path popplerBinary) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.popplerBinary = popplerBinary == null ? null : popplerBinary.toAbsolutePath().normalize();
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 15);
        DocumentModel model = parser.parseForFixedLayout(input.path(), input.displayName(), limits);
        Files.createDirectories(workDir);
        for (PageModel page : model.pages()) {
            ConversionGuards.requireRenderBounds(
                    page.physicalBox().width() * 72d / 25.4d,
                    page.physicalBox().height() * 72d / 25.4d,
                    RENDER_DPI, limits);
        }
        List<Path> renderedPages = popplerBinary == null
                ? renderWithPdfBox(input.path(), model, workDir, progress)
                : renderWithPoppler(input.path(), model.pages().size(), workDir, progress);
        ConversionGuards.requireTotalSize(renderedPages, limits, "PDF 转 OFD 页面图像");

        try (OFDDoc ofd = new OFDDoc(outputPath)) {
            for (int index = 0; index < model.pages().size(); index++) {
                PageModel page = model.pages().get(index);
                VirtualPage target = new VirtualPage(page.physicalBox().width(), page.physicalBox().height());
                if (!page.textBlocks().isEmpty()) target.add(textLayer(page));
                Img visual = new Img(page.physicalBox().width(), page.physicalBox().height(), renderedPages.get(index));
                visual.setPosition(Position.Absolute).setBox(0d, 0d,
                        page.physicalBox().width(), page.physicalBox().height());
                target.add(visual);
                ofd.addVPage(target);
                progress.update(TaskStage.RENDERING,
                        55 + (int) Math.round((index + 1d) * 35d / model.pages().size()));
            }
        }
        requireOfdPackage(outputPath, limits);
        List<ConversionWarning> warnings = List.of(ConversionWarning.of(WarningCode.FIDELITY_IMAGE_LAYER,
                "PDF 页面使用整页图像层保证固定版式；文字型页面同时包含真实 OFD 文字对象层", null));
        return new ConversionOutput(outputPath, outputFileName(input.displayName()),
                model.sourcePageCount(), warnings);
    }

    private List<Path> renderWithPdfBox(Path input, DocumentModel model, Path workDir,
                                        ConversionProgress progress) throws IOException {
        List<Path> renderedPages = new ArrayList<>(model.pages().size());
        try (PDDocument pdf = Loader.loadPDF(input.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int index = 0; index < model.pages().size(); index++) {
                BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
                Path png = workDir.resolve("pdf-ofd-page-%04d.png".formatted(index + 1));
                try {
                    if (!ImageIO.write(image, "png", png.toFile())) {
                        throw new IOException("无法写入 PDF 页面图像");
                    }
                } finally {
                    image.flush();
                }
                renderedPages.add(png);
                progress.update(TaskStage.RENDERING,
                        20 + (int) Math.round((index + 1d) * 35d / model.pages().size()));
            }
        }
        return renderedPages;
    }

    private List<Path> renderWithPoppler(Path input, int expectedPages, Path workDir,
                                         ConversionProgress progress) throws Exception {
        Path renderDir = Files.createDirectories(workDir.resolve("poppler"));
        Path prefix = renderDir.resolve("page");
        List<String> command = List.of(popplerBinary.toString(), "-r",
                String.format(Locale.ROOT, "%.2f", RENDER_DPI), "-cropbox", "-png",
                input.toString(), prefix.toString());
        progress.update(TaskStage.RENDERING, 25);
        ConversionGuards.runProcess(command, workDir.resolve("pdftoppm.log"), Duration.ofMinutes(2),
                "PDF 转 OFD Poppler 渲染");
        List<Path> renderedPages;
        try (var files = Files.list(renderDir)) {
            renderedPages = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .matches("page-\\d+\\.png"))
                    .sorted(Comparator.comparingInt(this::renderedPageNumber))
                    .toList();
        }
        if (renderedPages.size() != expectedPages) {
            throw new IOException("PDF 转 OFD Poppler 渲染页数不一致：" +
                    renderedPages.size() + " != " + expectedPages);
        }
        progress.update(TaskStage.RENDERING, 55);
        return renderedPages;
    }

    private int renderedPageNumber(Path path) {
        String name = path.getFileName().toString();
        int dash = name.lastIndexOf('-');
        int dot = name.lastIndexOf('.');
        if (dash < 0 || dot <= dash + 1) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(name.substring(dash + 1, dot));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private Canvas textLayer(PageModel page) {
        Canvas canvas = new Canvas(page.physicalBox().width(), page.physicalBox().height());
        canvas.setPosition(Position.Absolute).setBox(0d, 0d,
                page.physicalBox().width(), page.physicalBox().height());
        return canvas.setDrawer(context -> {
            for (TextBlock block : page.textBlocks()) {
                String family = block.style().family() == null || block.style().family().isBlank()
                        ? "SimSun" : block.style().family();
                FontSetting setting = new FontSetting(
                        Math.max(0.5d, block.style().sizePt() * 25.4d / 72d), new Font(family, family));
                setting.setItalic(block.style().italic()).setFontWeight(block.style().bold() ? 700 : 400);
                context.save();
                context.setFont(setting);
                context.setFillColor(block.style().color().red(), block.style().color().green(),
                        block.style().color().blue());
                double x = block.box().x() + block.textOffsetXmm();
                double y = block.baselineY();
                double rotation = block.transform().rotationDegrees();
                if (Math.abs(rotation) > 0.001d) {
                    context.translate(x, y).rotate(Math.toRadians(rotation));
                    context.fillText(block.text(), 0, 0);
                } else {
                    context.fillText(block.text(), x, y);
                }
                context.restore();
            }
        });
    }

    private void requireOfdPackage(Path output, ParseLimits limits) throws IOException {
        ConversionGuards.requireNonEmptyOutputFile(output, limits, "PDF 转 OFD");
        try (ZipFile archive = new ZipFile(output.toFile())) {
            if (archive.getEntry("OFD.xml") == null) throw new IOException("PDF 转 OFD 未生成合法 OFD.xml");
        }
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.pdf$", "") + ".ofd";
    }
}
