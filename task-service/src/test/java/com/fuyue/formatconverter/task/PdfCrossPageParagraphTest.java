package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfCrossPageParagraphTest {
    private static final float WIDTH = 500, HEIGHT = 400, LEFT = 50, RIGHT = 450;
    private static final float FONT_SIZE = 12, LEADING = 18, TOP_BASELINE = 350;
    private static final int LINES_PER_PAGE = 18;
    @TempDir Path temp;

    @Test void joinsEnglishAcrossPagesIntoOneEditableParagraphWithNaturalPagination() throws Exception {
        Fixture fixture = fixture(false, false, false, false, false);
        DocumentModel model = analyzed(fixture.path());
        assertNotNull(model.continuousFlow(), () -> summary(model));
        try (XWPFDocument word = open(convert(fixture.path()))) {
            assertEquals(List.of(fixture.text()), body(word).stream().map(XWPFParagraph::getText).toList());
            assertFalse(word.getDocument().xmlText().contains("pageBreakBefore"));
            assertFalse(word.getDocument().xmlText().contains("<w:br"));
            assertFalse(word.getDocument().xmlText().contains("txbxContent"));
            assertTrue(word.getAllPictures().isEmpty());
            assertTrue(Integer.parseInt(word.getDocument().getBody().getSectPr().getPgMar().getTop().toString()) > 0);
            assertTrue(Integer.parseInt(word.getDocument().getBody().getSectPr().getPgMar().getBottom().toString()) > 0);
            assertEquals(24 * 20, body(word).get(0).getIndentationFirstLine(), 1);
        }
    }

    @Test void joinsChineseAcrossPagesWithoutSpacesOrLostCharacters() throws Exception {
        Fixture fixture = fixture(true, false, false, false, false);
        DocumentModel model = analyzed(fixture.path());
        assertNotNull(model.continuousFlow(), () -> summary(model));
        try (XWPFDocument word = open(convert(fixture.path()))) {
            assertEquals(List.of(fixture.text()), body(word).stream().map(XWPFParagraph::getText).toList());
            assertFalse(body(word).get(0).getText().contains(" "));
            assertTrue(word.getAllPictures().isEmpty());
        }
    }

    @Test void preservesHeadersAndPageBoundaries() throws Exception {
        Fixture fixture = fixture(false, true, false, false, false);
        assertNull(analyzed(fixture.path()).continuousFlow());
        try (XWPFDocument word = open(convert(fixture.path()))) {
            assertEquals(fixture.pages(), body(word).stream().filter(p -> p.getText().equals("CONFIDENTIAL")).count());
            assertTrue(word.getDocument().xmlText().contains("pageBreakBefore"));
            assertEquals(compact(fixture.text()), compact(
                    body(word).stream().map(XWPFParagraph::getText)
                            .filter(s -> !s.equals("CONFIDENTIAL")).reduce("", String::concat)));
        }
    }

    @Test void refusesSentenceEndIndentationAndDifferentPaperSizes() throws Exception {
        for (int index = 0; index < 3; index++) {
            Fixture fixture = fixture(false, false, index == 0, index == 1, index == 2);
            assertNull(analyzed(fixture.path()).continuousFlow(), "ambiguous boundary case " + index);
            try (XWPFDocument word = open(convert(fixture.path()))) {
                assertTrue(body(word).size() >= fixture.pages());
                try (PDDocument pdf = Loader.loadPDF(fixture.path().toFile())) {
                    assertEquals(compact(new PDFTextStripper().getText(pdf)), compact(body(word).stream()
                            .map(XWPFParagraph::getText).reduce("", String::concat)), "all ambiguous-boundary text retained");
                }
            }
        }
    }

    @Test void realOfficePaginationAndEditingRetainAllTextWithoutBlankPages() throws Exception {
        var office = LibreOfficeConverter.discover("");
        assumeTrue(office.isPresent(), "LibreOffice is not installed");
        Fixture fixture = fixture(false, false, false, false, false);
        Path wordFile = convert(fixture.path());
        Path before = render(office.orElseThrow(), wordFile, "before");
        String addition = (" Additional review records must remain available to all parties throughout the service period.").repeat(40);
        Path edited = temp.resolve("edited.docx");
        try (XWPFDocument word = open(wordFile)) {
            assertEquals(1, body(word).size());
            XWPFRun run = body(word).get(0).createRun();
            run.setFontFamily("Arial"); run.setFontSize(12); run.setText(addition);
            try (var output = Files.newOutputStream(edited)) { word.write(output); }
        }
        Path after = render(office.orElseThrow(), edited, "after");
        try (PDDocument original = Loader.loadPDF(before.toFile()); PDDocument changed = Loader.loadPDF(after.toFile())) {
            assertEquals(fixture.pages(), original.getNumberOfPages(), "natural pagination should retain source page count");
            assertEquals(compact(fixture.text()), compact(new PDFTextStripper().getText(original)));
            assertTrue(changed.getNumberOfPages() > original.getNumberOfPages(), "editing must naturally add pages");
            assertEquals(compact(fixture.text() + addition), compact(new PDFTextStripper().getText(changed)));
            String qaDirectory = System.getProperty("format.converter.crosspage.qa-directory");
            if (qaDirectory != null) {
                Path qa = Path.of(qaDirectory); Files.createDirectories(qa);
                for (var artifact : Map.of("source.pdf", fixture.path(), "converted.docx", wordFile,
                        "before-edit.pdf", before, "edited.docx", edited, "after-edit.pdf", after).entrySet()) {
                    Files.copy(artifact.getValue(), qa.resolve(artifact.getKey()), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(qa.resolve("office-report.txt"), "Source and unedited Word: " + fixture.pages()
                        + " pages\nEdited Word: " + changed.getNumberOfPages()
                        + " pages\nOne editable paragraph, complete character content, no blank pages.\n");
            }
            for (int page = 1; page <= changed.getNumberOfPages(); page++) {
                PDFTextStripper reader = new PDFTextStripper(); reader.setStartPage(page); reader.setEndPage(page);
                assertFalse(reader.getText(changed).isBlank(), "no blank overflow pages");
            }
        }
    }

    @Test void preservesASeparateParagraphInsideAContinuousDocument() throws Exception {
        Fixture fixture = fixture(false, false, false, false, false, true);
        DocumentModel model = analyzed(fixture.path());
        assertNotNull(model.continuousFlow(), () -> summary(model));
        try (XWPFDocument word = open(convert(fixture.path()))) {
            assertEquals(2, body(word).size(), "only the paragraph crossing the page boundary is joined");
            assertEquals(compact(fixture.text()), compact(body(word).stream()
                    .map(XWPFParagraph::getText).reduce("", String::concat)));
            assertEquals(8 * 20, body(word).get(1).getSpacingBefore(), 2, "retain the source paragraph gap");
        }
    }

    @Test void realOfficeChinesePaginationRetainsCharactersAndSourcePageCount() throws Exception {
        var office = LibreOfficeConverter.discover("");
        assumeTrue(office.isPresent(), "LibreOffice is not installed");
        Fixture fixture = fixture(true, false, false, false, false);
        Path result = render(office.orElseThrow(), convert(fixture.path()), "chinese");
        try (PDDocument pdf = Loader.loadPDF(result.toFile())) {
            assertEquals(fixture.pages(), pdf.getNumberOfPages());
            assertEquals(fixture.text(), compact(new PDFTextStripper().getText(pdf)));
        }
    }

    private Fixture fixture(boolean chinese, boolean header, boolean sentenceEnd, boolean indented, boolean geometry) throws Exception {
        return fixture(chinese, header, sentenceEnd, indented, geometry, false);
    }

    private Fixture fixture(boolean chinese, boolean header, boolean sentenceEnd, boolean indented,
                            boolean geometry, boolean paragraphs) throws Exception {
        String content = chinese
                ? "本次工作已经完成。双方应当按照约定及时提交完整的服务记录，并在发生重大变更时通知对方，共同确认后继续执行原定服务计划。".repeat(22)
                : "The work is complete. All participants shall confirm the agreed delivery schedule and keep detailed records of every approved change before continuing the review process. ".repeat(paragraphs ? 11 : 14).strip();
        Path source = temp.resolve("report-" + UUID.randomUUID() + ".pdf");
        int pageCount;
        try (PDDocument pdf = new PDDocument()) {
            PDFont font;
            if (chinese) {
                try (var stream = getClass().getResourceAsStream("/fonts/DroidSansFallback.ttf")) {
                    assertNotNull(stream); font = PDType0Font.load(pdf, stream);
                }
            } else font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<String> lines;
            int paragraphStart = -1;
            if (paragraphs) {
                String firstParagraph = "The initial review is complete. The parties have confirmed the delivery records "
                        + "and agreed the scope of the work. This separate paragraph remains independent "
                        + "when the following longer report continues onto another source page.";
                lines = new ArrayList<>(wrap(font, firstParagraph, false));
                paragraphStart = lines.size();
                lines.addAll(wrap(font, content, false));
                content = firstParagraph + " " + content;
            } else lines = wrap(font, content, chinese);
            for (int offset = 0; offset < lines.size(); offset += LINES_PER_PAGE) {
                int pageNumber = offset / LINES_PER_PAGE;
                PDPage page = new PDPage(new PDRectangle(geometry && pageNumber > 0 ? WIDTH + 40 : WIDTH, HEIGHT));
                pdf.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                    if (header) draw(stream, font, 9, LEFT, HEIGHT - 15, "CONFIDENTIAL");
                    for (int index = 0; index < LINES_PER_PAGE && offset + index < lines.size(); index++) {
                        String line = lines.get(offset + index);
                        if (sentenceEnd && pageNumber == 0 && index == LINES_PER_PAGE - 1) line += ".";
                        float indent = (index == 0 && (pageNumber == 0 || indented))
                                || (paragraphs && pageNumber == 0 && index == paragraphStart) ? 24 : 0;
                        float gap = paragraphs && pageNumber == 0 && index >= paragraphStart ? 8 : 0;
                        draw(stream, font, FONT_SIZE, LEFT + indent, TOP_BASELINE - index * LEADING - gap, line);
                    }
                }
            }
            pageCount = pdf.getNumberOfPages(); assertTrue(pageCount >= 2);
            pdf.save(source.toFile());
        }
        return new Fixture(source, content, pageCount);
    }

    private List<String> wrap(PDFont font, String content, boolean chinese) throws Exception {
        List<String> tokens = chinese ? content.codePoints().mapToObj(c -> new String(Character.toChars(c))).toList()
                : List.of(content.split(" "));
        List<String> lines = new ArrayList<>(); String line = "";
        for (String token : tokens) {
            String candidate = line.isEmpty() ? token : line + (chinese ? "" : " ") + token;
            if (!line.isEmpty() && font.getStringWidth(candidate) * FONT_SIZE / 1000 > RIGHT - LEFT - (lines.isEmpty() ? 24 : 0)) {
                lines.add(line); line = token;
            } else line = candidate;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    private void draw(PDPageContentStream stream, PDFont font, float size, float x, float y, String text) throws Exception {
        stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, y); stream.showText(text); stream.endText();
    }
    private DocumentModel analyzed(Path source) throws Exception {
        DocumentModel raw = new PdfLayoutParser().parse(source, "report.pdf", ParseLimits.defaults());
        var reconstructor = new PdfParagraphReconstructor(); var analyzer = new PageLayoutAnalyzer();
        return reconstructor.reconstructAcrossPages(new DocumentModel(raw.sourceName(), raw.parserName(), raw.sourcePageCount(),
                raw.pages().stream().map(analyzer::analyze).map(reconstructor::reconstruct).toList(), raw.warnings()));
    }
    private Path convert(Path source) throws Exception {
        Path out = temp.resolve(source.getFileName().toString() + ".docx");
        new PdfToDocxConverter().convert(new ConversionInput("report.pdf", "application/pdf", Files.size(source), source),
                temp.resolve("work"), out, ParseLimits.defaults(), (stage, percent) -> {});
        return out;
    }
    private Path render(Path office, Path source, String name) throws Exception {
        Path out = temp.resolve(name + ".pdf");
        new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, office, Duration.ofSeconds(60), "cross-page QA")
                .convert(new ConversionInput(source.getFileName().toString(), DocumentFormat.DOCX.contentType(), Files.size(source), source),
                        temp.resolve(name + "-work"), out, ParseLimits.defaults(), (stage, percent) -> {});
        return out;
    }
    private String summary(DocumentModel model) {
        return model.pages().stream().map(p -> "page=" + p.pageNumber() + " paragraphs=" + p.paragraphs().stream()
                .map(g -> "flow=" + g.flow() + " box=" + g.box() + " leading=" + g.lineSpacingMm()
                        + " end=" + g.runs().get(g.runs().size()-1).text()).toList()).toList().toString();
    }
    private XWPFDocument open(Path path) throws Exception { return new XWPFDocument(Files.newInputStream(path)); }
    private List<XWPFParagraph> body(XWPFDocument word) { return word.getParagraphs().stream().filter(p -> !p.getText().isBlank()).toList(); }
    private String compact(String value) { return value.replaceAll("\\s+", ""); }
    private record Fixture(Path path, String text, int pages) { }
}
