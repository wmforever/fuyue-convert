package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrWordOverlayTest {
    @TempDir Path temp;
    private static final String VML = "urn:schemas-microsoft-com:vml";
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void preservesSourceBytesAndUnknownWordGapsWhileRenderingOneEditableCopy() throws Exception {
        byte[] original = png();
        List<TextBlock.OcrWord> words = List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "LEFT", .98),
                new TextBlock.OcrWord(new Rect(60, 30, 15, 4), "RIGHT", .45));
        TextBlock ocr = ocr(words, "LEFT RIGHT");
        TextBlock nativeText = new TextBlock("native", 1, new Rect(20, 70, 30, 4), "Native body", 74,
                new FontStyle("Arial", 12, false, false, null), 5);
        ImageBlock background = new ImageBlock("source", 1, new Rect(0, 0, 100, 100),
                "image/png", original, "OCR_SCAN_BACKGROUND", 0);
        Path result = render(List.of(ocr, nativeText), List.of(background));

        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(result))) {
            assertEquals(1, docx.getAllPictures().size());
            assertArrayEquals(original, docx.getAllPictures().get(0).getData(), "原始扫描图字节必须完整保留");
            var xml = xml(docx);
            var masks = masks(xml);
            assertEquals(2, masks.size(), "每个识别词有独立遮罩，不能使用整行联合框");
            for (int index = 0; index < masks.size(); index++) {
                String style = masks.get(index).getAttribute("style");
                Rect sourceBox = words.get(index).box();
                assertEquals(sourceBox.x() - .15, mm(style, "margin-left"), .001);
                assertEquals(sourceBox.width() + .3, mm(style, "width"), .001);
                assertTrue(style.contains(index == 0 ? "z-index:1;" : "z-index:-251658751;"),
                        "与原生正文分离的可靠词可覆盖扫描；低置信度词保留原层级");
                assertEquals("#FFFFFF", masks.get(index).getAttribute("fillcolor"));
                double maskLeft = mm(style, "margin-left"), maskRight = maskLeft + mm(style, "width");
                assertTrue(maskRight <= 32.151 || maskLeft >= 59.849,
                        "仅允许0.15mm抗锯齿边缘，词间32.15到59.85mm的未识别区域必须可见");
            }
            assertEquals("LEFT RIGHTNative body", elements(xml, WORD, "t").stream()
                    .map(Element::getTextContent).reduce("", String::concat));
            assertEquals(2, elements(xml, WORD, "txbxContent").size(), "OCR为逐词可编辑文本框，不得重复输出整行");
            assertEquals(List.of("Native body"), docx.getParagraphs().stream()
                    .filter(p -> !p.getCTP().xmlText().contains("txbxContent"))
                    .map(p -> p.getText()).filter(s -> !s.isBlank()).toList());
        }
    }

    @Test void scanOnlyMasksAreInFrontOfDrawingMlBackgroundAndBelowEditableText() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        ImageBlock background = new ImageBlock("scan", 1, new Rect(0, 0, 100, 100),
                "image/png", png(false), "OCR_SCAN_BACKGROUND", 0);
        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(List.of(line), List.of(background))))) {
            var xml = xml(docx);
            assertEquals(1, masks(xml).size());
            assertTrue(masks(xml).get(0).getAttribute("style").contains("z-index:1;"));
            assertEquals(1, elements(xml,
                    "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "anchor").size());
            assertTrue(elements(xml, VML, "rect").stream()
                    .filter(element -> element.getElementsByTagNameNS(WORD, "txbxContent").getLength() > 0)
                    .allMatch(element -> element.getAttribute("style").contains("z-index:3;")));
            assertArrayEquals(background.data(), docx.getAllPictures().get(0).getData());
        }
    }

    @Test void clipsMasksToDeclaredOcrBackgroundAndNeverMasksOrdinaryPhotos() throws Exception {
        TextBlock ocr = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 20, 4), "TEXT", .9)), "TEXT");
        ImageBlock background = new ImageBlock("scan", 1, new Rect(25, 0, 10, 100),
                "image/png", png(false), "OCR_PAGE_BACKGROUND", 0);
        ImageBlock photo = new ImageBlock("photo", 1, new Rect(0, 0, 100, 100),
                "image/png", png(), "PDF_IMAGE", 1);
        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(List.of(ocr), List.of(background, photo))))) {
            var masks = masks(xml(docx));
            assertEquals(1, masks.size());
            assertEquals(25, mm(masks.get(0).getAttribute("style"), "margin-left"), .001);
            assertEquals(10, mm(masks.get(0).getAttribute("style"), "width"), .001);
        }
        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(List.of(ocr), List.of(photo))))) {
            assertTrue(masks(xml(docx)).isEmpty());
        }
    }

    @Test void usesStandardRectTextBoxesAndOneLineFontSizeForWordsAndPunctuation() throws Exception {
        TextBlock line = ocr(List.of(
                new TextBlock.OcrWord(new Rect(20, 30, 14, 4), "Service", .95),
                new TextBlock.OcrWord(new Rect(38, 30, 9, 4), "2026", .95),
                new TextBlock.OcrWord(new Rect(50, 30, 1, 1), "/", .95)), "Service 2026 /");
        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(List.of(line), List.of())))) {
            var xml = xml(docx);
            var textBoxes = elements(xml, VML, "rect").stream()
                    .filter(element -> element.getElementsByTagNameNS(WORD, "txbxContent").getLength() > 0).toList();
            assertEquals(3, textBoxes.size(), "标准v:rect应避免LibreOffice对无类型v:shape额外增加默认内边距");
            assertEquals(1, textBoxes.stream().map(element -> mm(element.getAttribute("style"), "margin-top"))
                    .distinct().count(), "同一行所有词和标点必须共用稳定基线");
            assertEquals(1, elements(xml, WORD, "sz").stream()
                    .map(element -> element.getAttributeNS(WORD, "val")).distinct().count(),
                    "不能根据狭小标点框单独放大或缩小字号");
            assertEquals("Service 2026 /", elements(xml, WORD, "t").stream()
                    .map(Element::getTextContent).reduce("", String::concat));
        }
    }

    @Test void usesGrayPaperMasksAndKeepsReadableEditableTextOnDarkPaper() throws Exception {
        for (int gray : new int[]{160, 65, 0}) {
            BufferedImage pixels = new BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB);
            var graphics = pixels.createGraphics(); graphics.setColor(new java.awt.Color(gray, gray, gray));
            graphics.fillRect(0, 0, 500, 500); graphics.dispose();
            var data = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", data); pixels.flush();
            byte[] original = data.toByteArray();
            ImageBlock background = new ImageBlock("scan", 1, new Rect(0, 0, 100, 100), "image/png", original,
                    "OCR_SCAN_BACKGROUND", 0);
            TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 30, 4), "GRAY PAPER", .9)), "GRAY PAPER");
            try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(List.of(line), List.of(background))))) {
                var xml = xml(docx);
                var masks = masks(xml);
                assertEquals(1, masks.size());
                assertEquals("#%02X%02X%02X".formatted(gray, gray, gray), masks.get(0).getAttribute("fillcolor"));
                assertTrue(masks.get(0).getAttribute("style").contains(gray < 110
                        ? "z-index:-251658751;" : "z-index:1;"),
                        "需要白字的暗纸页必须保留跨Office版本已验证的旧层级");
                assertEquals("GRAY PAPER", elements(xml, WORD, "t").stream().map(Element::getTextContent).reduce("", String::concat));
                assertTrue(elements(xml, WORD, "color").stream().anyMatch(color ->
                        (gray < 110 ? "FFFFFF" : "000000").equals(color.getAttributeNS(WORD, "val"))));
                assertEquals(1, docx.getAllPictures().size());
                assertArrayEquals(original, docx.getAllPictures().get(0).getData());
            }
        }
    }

    @Test void doesNotEraseAStampInsideRecognizedWords() throws Exception {
        byte[] source = png();
        ImageBlock background = new ImageBlock("stamp", 1, new Rect(0, 0, 100, 100), "image/png", source,
                "OCR_SCAN_BACKGROUND", 0);
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(35, 29, 20, 6), "STAMP", .9)), "STAMP");
        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(List.of(line), List.of(background))))) {
            var xml = xml(docx);
            assertTrue(masks(xml).isEmpty());
            assertEquals("STAMP", elements(xml, WORD, "t").stream().map(Element::getTextContent).reduce("", String::concat));
            assertArrayEquals(source, docx.getAllPictures().get(0).getData());
        }
    }

    @Test void preservesConflictingNativeValueEvenWhenItOnlyExistsInBodyParagraphs() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        TextBlock nativeText = new TextBlock("native", 1, line.box(), "127.51", 34, FontStyle.defaults(), 4);
        var paragraph = new ParagraphModel(nativeText.box(), List.of(nativeText), ParagraphModel.Alignment.LEFT, 0);
        // The native run is absent from both textBlocks and the overlay list.
        assertMixedLayer(line, List.of(), List.of(paragraph), List.of(), List.of(mixedBackground(255, 255, 255)), false);
    }

    @Test void protectsNativeParagraphExtentAndFontHeightMargin() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        for (double y : new double[]{37, 70}) {
            TextBlock nativeText = new TextBlock("native", 1, new Rect(20, y, 30, 4), "Native", y + 4,
                    new FontStyle("Arial", 12, false, false, null), 4);
            // Neither run touches the word; the near run's body margin and the
            // far run's declared paragraph extent must independently protect it.
            Rect paragraphBox = y == 70 ? new Rect(20, 30, 30, 44) : nativeText.box();
            assertMixedLayer(line, List.of(nativeText), List.of(new ParagraphModel(paragraphBox,
                    List.of(nativeText), ParagraphModel.Alignment.LEFT, 0)), List.of(),
                    List.of(mixedBackground(255, 255, 255)), false);
        }
    }

    @Test void retainsLayeringForReflowAndTransformedNativeOcrCombinations() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        for (int variant = 0; variant < 3; variant++) {
            TextBlock nativeText = new TextBlock("native", 1, new Rect(20, 70, 30, 4), "Native", 74,
                    FontStyle.defaults(), 4, 0, 0, List.of(), variant == 1
                    ? new Transform2D(1, 0, 0, 1, 0, 3) : Transform2D.IDENTITY);
            TextBlock overlay = variant != 2 ? line : new TextBlock(line.id(), 1, line.box(), line.text(),
                    line.baselineY(), line.style(), 1, 0, 0, List.of(), new Transform2D(1, 0, 0, 1, 0, 3), line.ocrWords());
            var paragraph = new ParagraphModel(nativeText.box(), List.of(nativeText), ParagraphModel.Alignment.LEFT, 0,
                    variant == 0 ? new ParagraphModel.Flow(2, 0) : null);
            assertMixedLayer(overlay, List.of(nativeText), List.of(paragraph), List.of(),
                    List.of(mixedBackground(255, 255, 255)), false);
        }
    }

    @Test void mixedMasksRequireNeutralLightPaperAndNoIndependentGraphics() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        TextBlock nativeText = new TextBlock("native", 1, new Rect(20, 70, 30, 4), "Native", 74, FontStyle.defaults(), 4);
        for (int[] rgb : new int[][]{{225,225,225}, {65,65,65}, {255,225,190}}) {
            assertMixedLayer(line, List.of(nativeText), List.of(), List.of(),
                    List.of(mixedBackground(rgb[0], rgb[1], rgb[2])), rgb[0] == 225);
        }
        ImageBlock background = mixedBackground(255, 255, 255);
        assertMixedLayer(line, List.of(nativeText), List.of(), List.of(new LineElement("line", 1,
                new Point(70, 80), new Point(80, 80), .2, ColorValue.BLACK, 3)), List.of(background), false);
        assertMixedLayer(line, List.of(nativeText), List.of(), List.of(), List.of(background,
                new ImageBlock("photo", 1, new Rect(70, 80, 10, 10), "image/png", png(), "PDF_IMAGE", 3)), false);
    }

    @Test void denseMixedPageSkipsOptionalPromotion() throws Exception {
        TextBlock line = ocr(java.util.stream.IntStream.range(0, 513)
                .mapToObj(i -> new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "X", .98)).toList(), "X ".repeat(513));
        TextBlock nativeText = new TextBlock("native", 1, new Rect(20, 70, 30, 4), "Native", 74, FontStyle.defaults(), 4);
        assertMixedLayer(line, List.of(nativeText), List.of(), List.of(), List.of(mixedBackground(255, 255, 255)), false);
    }

    @Test void supportsFirstOfdAdditionAndBaselineOffsetInsideNativeBoundary() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        line = new TextBlock(line.id(), 1, line.box(), line.text(), line.baselineY(), line.style(), 1,
                0, 0, List.of(), Transform2D.IDENTITY, line.ocrWords());
        TextBlock nativeText = new TextBlock("native", 1, new Rect(20, 70, 30, 4), "Native", 74,
                FontStyle.defaults(), 4, 0, 4, List.of(), Transform2D.IDENTITY);
        assertMixedLayer(line, List.of(nativeText), List.of(), List.of(), List.of(mixedBackground(255, 255, 255)), true);
    }

    @Test void uncertainNativeOffsetOutsideBoundaryRetainsScanLayer() throws Exception {
        TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "127.50", .98)), "127.50");
        TextBlock nativeText = new TextBlock("native", 1, new Rect(20, 70, 30, 4), "Native", 82,
                FontStyle.defaults(), 4, 0, 12, List.of(), Transform2D.IDENTITY);
        assertMixedLayer(line, List.of(nativeText), List.of(), List.of(), List.of(mixedBackground(255, 255, 255)), false);
    }

    private ImageBlock mixedBackground(int r, int g, int b) throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new java.awt.Color(r, g, b));
        graphics.fillRect(0, 0, 100, 100); graphics.dispose();
        var data = new ByteArrayOutputStream(); ImageIO.write(image, "png", data); image.flush();
        return new ImageBlock("scan", 1, new Rect(0, 0, 100, 100), "image/png", data.toByteArray(), "OCR_SCAN_BACKGROUND", 0);
    }

    private void assertMixedLayer(TextBlock line, List<TextBlock> nativeText, List<ParagraphModel> paragraphs,
                                  List<LineElement> lines, List<ImageBlock> images, boolean promoted) throws Exception {
        List<TextBlock> all = new ArrayList<>(nativeText); all.add(line);
        var page = new PageModel(1, new Rect(0, 0, 100, 100), all, lines, images, paragraphs, List.of(), List.of());
        try (var docx = new XWPFDocument()) {
            new FixedLayoutDocxRenderer().renderOverlays(docx, docx.createParagraph(), page, List.of(line));
            var masks = masks(xml(docx)); assertFalse(masks.isEmpty());
            assertTrue(masks.stream().allMatch(mask -> mask.getAttribute("style").contains(
                    promoted ? "z-index:1;" : "z-index:-251658751;")));
            assertEquals(line.text(), elements(xml(docx), WORD, "t").stream().map(Element::getTextContent).reduce("", String::concat));
            assertArrayEquals(images.get(0).data(), docx.getAllPictures().get(0).getData());
        }
    }

    private TextBlock ocr(List<TextBlock.OcrWord> words, String text) {
        Rect box = words.stream().map(TextBlock.OcrWord::box).reduce(Rect::union).orElseThrow();
        return new TextBlock("ocr-line", 1, box, text, box.bottom(),
                new FontStyle("Arial", 10, false, false, null), 2,
                0, 0, List.of(), Transform2D.IDENTITY, words);
    }

    @Test void numericEditReserveKeepsMasksAndPositionButRejectsUnknownInkAndLowConfidence() throws Exception {
        List<Double> widths = new ArrayList<>();
        for (int variant = 0; variant < 4; variant++) {
            TextBlock line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "7.50", variant == 2 ? .45 : .98)), "7.50");
            ImageBlock background = new ImageBlock("scan", 1, new Rect(0, 0, 100, 100),
                    "image/png", png(variant == 1), "OCR_SCAN_BACKGROUND", 0);
            List<TextBlock> lines = new ArrayList<>(List.of(line));
            if (variant == 3) lines.add(ocr(java.util.stream.IntStream.range(0, 512)
                    .mapToObj(i -> new TextBlock.OcrWord(new Rect(70, 70, 2, 2), "X", .98)).toList(), "X ".repeat(512)));
            try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(render(lines, List.of(background))))) {
                var xml = xml(docx);
                var shape = elements(xml, VML, "rect").stream().filter(e -> e.getElementsByTagNameNS(WORD, "txbxContent").getLength() > 0).findFirst().orElseThrow();
                widths.add(mm(shape.getAttribute("style"), "width"));
                assertEquals(19.85, mm(masks(xml).get(0).getAttribute("style"), "margin-left"), .001);
                assertEquals(12.3, mm(masks(xml).get(0).getAttribute("style"), "width"), .001);
                assertEquals("7.50", elements(xml, WORD, "t").get(0).getTextContent());
                assertArrayEquals(background.data(), docx.getAllPictures().get(0).getData());
            }
        }
        assertTrue(widths.get(0) > widths.get(1) + 2d, widths.toString());
        assertEquals(widths.get(1), widths.get(2));
        assertEquals(widths.get(1), widths.get(3), "Dense pages skip the optional reserve before neighbor scans");
    }

    @Test void longNumericReserveIsBoundedAndRetainsShortReserveAroundUnknownInkOrNeighbor() throws Exception {
        List<Double> widths = new ArrayList<>();
        List<String> maskStyles = new ArrayList<>();
        List<Double> lefts = new ArrayList<>();
        for (int variant = 0; variant < 3; variant++) {
            var pixels = ImageIO.read(new ByteArrayInputStream(png(false)));
            if (variant == 1) pixels.setRGB(50, 30, 0xff0000); // Beyond old reserve, inside proposed extension.
            var bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(pixels, "png", bytes)); pixels.flush();
            var background = new ImageBlock("scan", 1, new Rect(0, 0, 100, 100), "image/png", bytes.toByteArray(), "OCR_SCAN_BACKGROUND", 0);
            var line = ocr(List.of(new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "7.50", .98)), "7.50");
            var lines = new ArrayList<>(List.of(line));
            if (variant == 2) lines.add(ocr(List.of(new TextBlock.OcrWord(new Rect(50, 30, 4, 4), "NOTE", .98)), "NOTE"));
            try (var docx = new XWPFDocument(Files.newInputStream(render(lines, List.of(background))))) {
                var xml = xml(docx);
                var shape = elements(xml, VML, "rect").stream().filter(e -> e.getElementsByTagNameNS(WORD, "t").getLength() > 0
                        && e.getElementsByTagNameNS(WORD, "t").item(0).getTextContent().equals("7.50")).findFirst().orElseThrow();
                widths.add(mm(shape.getAttribute("style"), "width"));lefts.add(mm(shape.getAttribute("style"), "margin-left"));
                maskStyles.add(masks(xml).get(0).getAttribute("style"));
                assertTrue(lefts.get(variant) + widths.get(variant) <= 57.001, "Only the checked 25mm region may grow");
                assertArrayEquals(background.data(), docx.getAllPictures().get(0).getData());
            }
        }
        assertTrue(widths.get(0) > widths.get(1) + 8d);
        assertEquals(widths.get(1), widths.get(2), .001, "Unknown ink and known neighbor preserve the short reserve");
        assertEquals(lefts.get(0), lefts.get(1));assertEquals(lefts.get(0), lefts.get(2));
        assertEquals(maskStyles.get(0), maskStyles.get(1));assertEquals(maskStyles.get(0), maskStyles.get(2));
    }

    private Path render(List<TextBlock> text, List<ImageBlock> images) throws Exception {
        var paragraphs = text.stream().map(block -> new ParagraphModel(block.box(), List.of(block),
                ParagraphModel.Alignment.LEFT, 0)).toList();
        PageModel page = new PageModel(1, new Rect(0, 0, 100, 100), text, List.of(), images,
                paragraphs, List.of(), List.of());
        Path result = Files.createTempFile(temp, "ocr-words-", ".docx");
        new PoiDocxRenderer().render(new DocumentModel("scan.pdf", "test", 1, List.of(page), List.of()), result);
        return result;
    }

    private byte[] png() throws Exception { return png(true); }

    private byte[] png(boolean stamp) throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) {
                image.setRGB(x, y, stamp && x >= 40 && x <= 50 && y >= 30 && y <= 34 ? 0xff0000 : 0xffffff);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } finally { image.flush(); }
    }

    private org.w3c.dom.Document xml(XWPFDocument word) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(word.getDocument().xmlText()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private List<Element> elements(org.w3c.dom.Document xml, String namespace, String localName) {
        var nodes = xml.getElementsByTagNameNS(namespace, localName);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }

    private List<Element> masks(org.w3c.dom.Document xml) {
        return elements(xml, VML, "rect").stream()
                .filter(element -> element.getAttribute("id").startsWith("ocr-mask-")).toList();
    }

    private double mm(String style, String name) {
        String value = Arrays.stream(style.split(";")).filter(s -> s.startsWith(name + ":")).findFirst().orElseThrow();
        return Double.parseDouble(value.substring(name.length() + 1).replace("pt", "")) * 25.4 / 72;
    }
}
