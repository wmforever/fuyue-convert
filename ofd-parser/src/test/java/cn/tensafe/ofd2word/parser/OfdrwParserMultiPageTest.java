package cn.tensafe.ofd2word.parser;

import cn.tensafe.ofd2word.model.DocumentModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;

import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Map;

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

    private VirtualPage page(double width, double height, String text) {
        Paragraph paragraph = new Paragraph(text, 5d);
        paragraph.setPosition(Position.Absolute).setBox(15d, 15d, width - 30d, 15d);
        return new VirtualPage(width, height).add(paragraph);
    }
}
