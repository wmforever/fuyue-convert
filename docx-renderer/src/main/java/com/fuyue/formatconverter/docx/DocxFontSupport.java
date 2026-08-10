package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.TextBlock;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRelation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Embeds the licensed bundled CJK fallback so generated Word files remain portable. */
final class DocxFontSupport {
    static final String CJK_FAMILY = "Droid Sans Fallback";

    private static final String CJK_RESOURCE = "/fonts/DroidSansFallback.ttf";
    private static final String FONT_RELATION =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/font";
    private static final String OBFUSCATED_FONT_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.obfuscatedFont";
    private static final byte[] BUNDLED_CJK_FONT = loadBundledCjkFont();

    private DocxFontSupport() { }

    static String familyFor(TextBlock block) {
        return BUNDLED_CJK_FONT.length > 0 && containsCjk(block.text())
                ? CJK_FAMILY : block.style().family();
    }

    static void embedBundledCjkFont(XWPFDocument document, DocumentModel model) throws IOException {
        if (BUNDLED_CJK_FONT.length == 0 || !containsCjk(model)) return;
        embedFont(document, BUNDLED_CJK_FONT, UUID.randomUUID());
    }

    static void embedFont(XWPFDocument document, byte[] fontBytes, UUID key) throws IOException {
        if (fontBytes == null || fontBytes.length < 32) throw new IOException("嵌入字体数据无效");
        OPCPackage pack = document.getPackage();
        PackagePartName tableName;
        PackagePartName fontName;
        try {
            tableName = PackagingURIHelper.createPartName("/word/fontTable.xml");
            fontName = PackagingURIHelper.createPartName("/word/fonts/DroidSansFallback.odttf");
        } catch (InvalidFormatException e) {
            throw new IOException("无法创建 DOCX 字体部件路径", e);
        }
        if (pack.containPart(tableName) || pack.containPart(fontName)) {
            throw new IOException("DOCX 已包含冲突的字体部件");
        }

        PackagePart table = pack.createPart(tableName, XWPFRelation.FONT_TABLE.getContentType());
        PackagePart font = pack.createPart(fontName, OBFUSCATED_FONT_CONTENT_TYPE);
        document.getPackagePart().addRelationship(tableName, TargetMode.INTERNAL,
                XWPFRelation.FONT_TABLE.getRelation());
        table.addRelationship(fontName, TargetMode.INTERNAL, FONT_RELATION, "rId1");

        try (var output = font.getOutputStream()) {
            output.write(obfuscate(fontBytes, key));
        }
        String guid = "{" + key.toString().toUpperCase(Locale.ROOT) + "}";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:fonts xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<w:font w:name=\"" + CJK_FAMILY + "\"><w:altName w:val=\"DroidSansFallback\"/>" +
                "<w:charset w:val=\"86\"/><w:family w:val=\"swiss\"/><w:pitch w:val=\"variable\"/>" +
                "<w:embedRegular r:id=\"rId1\" w:fontKey=\"" + guid +
                "\" w:subsetted=\"false\"/></w:font></w:fonts>";
        try (var output = table.getOutputStream()) {
            output.write(xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    static byte[] obfuscate(byte[] source, UUID key) {
        byte[] result = source.clone();
        String hex = key.toString().replace("-", "");
        byte[] keyBytes = new byte[16];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        for (int index = 0; index < 32; index++) {
            result[index] ^= keyBytes[15 - index % 16];
        }
        return result;
    }

    private static boolean containsCjk(DocumentModel model) {
        return model.pages().stream().anyMatch(page ->
                page.textBlocks().stream().anyMatch(block -> containsCjk(block.text()))
                        || page.paragraphs().stream().flatMap(paragraph -> paragraph.runs().stream())
                        .anyMatch(block -> containsCjk(block.text()))
                        || page.tables().stream().flatMap(table -> table.cells().stream())
                        .flatMap(cell -> cell.paragraphs().stream())
                        .flatMap(paragraph -> paragraph.runs().stream())
                        .anyMatch(block -> containsCjk(block.text())));
    }

    private static boolean containsCjk(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.BOPOMOFO;
        });
    }

    private static byte[] loadBundledCjkFont() {
        try (InputStream input = DocxFontSupport.class.getResourceAsStream(CJK_RESOURCE)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }
}
