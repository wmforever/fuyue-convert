package com.fuyue.formatconverter.parser;

import com.fuyue.formatconverter.model.DocumentModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;
import org.ofdrw.layout.element.canvas.Canvas;

import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfdrwParserMultiPageTest {
    @TempDir Path temp;

    @Test void parsesEveryPageInSourceOrder() throws Exception {
        Path archive = temp.resolve("multi.ofd");
        try (OFDDoc document = new OFDDoc(archive)) {
            document.addVPage(page(210, 297, "第一页文字"));
            document.addVPage(page(297, 210, "第二页文字"));
        }

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                archive, temp.resolve("unpacked"), ParseLimits.defaults());
        DocumentModel parsed = new OfdrwParser().parse(unpacked, "multi.ofd", ParseLimits.defaults());

        assertEquals(2, parsed.sourcePageCount());
        assertEquals(2, parsed.pages().size());
        assertEquals(210d, parsed.pages().get(0).physicalBox().width(), 0.01);
        assertEquals(297d, parsed.pages().get(1).physicalBox().width(), 0.01);
        assertTrue(parsed.pages().get(0).textBlocks().stream().anyMatch(t -> t.text().contains("第一页文字")));
        assertTrue(parsed.pages().get(1).textBlocks().stream().anyMatch(t -> t.text().contains("第二页文字")));
    }

    @Test void preservesObjectTransformForGenericTextRendering() throws Exception {
        Path archive = temp.resolve("transformed.ofd");
        try (OFDDoc document = new OFDDoc(archive)) {
            document.addVPage(page(210, 297, "横向压缩文字"));
        }
        try (FileSystem zip = FileSystems.newFileSystem(archive, Map.of())) {
            Path content = Files.walk(zip.getPath("/"))
                    .filter(path -> path.toString().endsWith("Content.xml"))
                    .findFirst().orElseThrow();
            String xml = Files.readString(content);
            xml = xml.replaceFirst("(<(?:ofd:)?TextObject\\b)", "$1 CTM=\"0.65 0 0 1 0 0\"");
            Files.writeString(content, xml);
        }

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                archive, temp.resolve("transformed-unpacked"), ParseLimits.defaults());
        DocumentModel parsed = new OfdrwParser().parse(unpacked, "transformed.ofd", ParseLimits.defaults());
        var text = parsed.pages().get(0).textBlocks().stream()
                .filter(block -> block.text().contains("横向压缩文字"))
                .findFirst().orElseThrow();

        assertEquals(0.65d, text.transform().scaleX(), 0.001d);
        assertEquals(1d, text.transform().scaleY(), 0.001d);
    }

    @Test void generatesUniqueIdsWhenSourceTextObjectsOmitIds() throws Exception {
        Path archive = temp.resolve("missing-text-ids.ofd");
        Paragraph first = new Paragraph("第一段", 5d);
        first.setPosition(Position.Absolute).setBox(10d, 10d, 80d, 10d);
        Paragraph second = new Paragraph("第二段", 5d);
        second.setPosition(Position.Absolute).setBox(10d, 30d, 80d, 10d);
        try (OFDDoc document = new OFDDoc(archive)) {
            document.addVPage(new VirtualPage(100d, 60d).add(first).add(second));
        }

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                archive, temp.resolve("missing-text-ids-unpacked"), ParseLimits.defaults());
        var texts = new OfdrwParser().parse(unpacked, "missing-text-ids.ofd", ParseLimits.defaults())
                .pages().get(0).textBlocks();

        assertTrue(texts.size() >= 2);
        assertEquals(texts.size(), new HashSet<>(texts.stream().map(text -> text.id()).toList()).size());
        assertTrue(texts.stream().noneMatch(text -> text.id().startsWith("null-")));
    }

    @Test void preservesDiagonalAndFlattensBezierPathSegments() throws Exception {
        Path archive = temp.resolve("paths.ofd");
        Canvas canvas = new Canvas(80d, 50d).setDrawer(context -> context.beginPath()
                .moveTo(2, 2)
                .lineTo(20, 15)
                .quadraticCurveTo(30, 2, 40, 15)
                .bezierCurveTo(50, 28, 60, 2, 75, 20)
                .stroke());
        canvas.setPosition(Position.Absolute).setBox(10d, 20d, 80d, 50d);
        try (OFDDoc document = new OFDDoc(archive)) {
            document.addVPage(new VirtualPage(120d, 90d).add(canvas));
        }

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                archive, temp.resolve("paths-unpacked"), ParseLimits.defaults());
        DocumentModel parsed = new OfdrwParser().parse(unpacked, "paths.ofd", ParseLimits.defaults());
        var lines = parsed.pages().get(0).lines();

        assertTrue(lines.size() >= 21, "直线、二次和三次贝塞尔曲线应转换为可渲染线段");
        assertTrue(lines.stream().anyMatch(line -> !line.horizontal(0.15d) && !line.vertical(0.15d)),
                "斜线不得再被表格识别过滤逻辑静默丢弃");
    }

    @Test void splitsTextCodeByDeltaYWhilePreservingEachRowsDeltaX() throws Exception {
        Path archive = temp.resolve("delta-y.ofd");
        try (OFDDoc document = new OFDDoc(archive)) {
            document.addVPage(page(100, 60, "ABCD"));
        }
        try (FileSystem zip = FileSystems.newFileSystem(archive, Map.of())) {
            Path content = Files.walk(zip.getPath("/"))
                    .filter(path -> path.toString().endsWith("Content.xml"))
                    .findFirst().orElseThrow();
            String xml = Files.readString(content);
            xml = xml.replaceFirst("(<(?:ofd:)?TextCode\\b)[^>]*>ABCD(</(?:ofd:)?TextCode>)",
                    "$1 X=\"0\" Y=\"4\" DeltaX=\"2 -2 2\" DeltaY=\"0 4 0\">ABCD$2");
            Files.writeString(content, xml);
        }

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                archive, temp.resolve("delta-y-unpacked"), ParseLimits.defaults());
        DocumentModel parsed = new OfdrwParser().parse(unpacked, "delta-y.ofd", ParseLimits.defaults());
        var texts = parsed.pages().get(0).textBlocks();

        assertTrue(texts.stream().anyMatch(text -> text.text().equals("AB")));
        assertTrue(texts.stream().anyMatch(text -> text.text().equals("CD")));
        double firstBaseline = texts.stream().filter(text -> text.text().equals("AB"))
                .findFirst().orElseThrow().baselineY();
        double secondBaseline = texts.stream().filter(text -> text.text().equals("CD"))
                .findFirst().orElseThrow().baselineY();
        assertEquals(4d, secondBaseline - firstBaseline, 0.01d);
    }

    private VirtualPage page(double width, double height, String text) {
        Paragraph paragraph = new Paragraph(text, 5d);
        paragraph.setPosition(Position.Absolute).setBox(15d, 15d, width - 30d, 15d);
        return new VirtualPage(width, height).add(paragraph);
    }
}
