package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.Transform2D;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfOcrVisibilityTest {
    private static final double MM = 25.4 / 72;
    private static Rect box(double x, double y, double w, double h) {
        return new Rect(x * MM, (200 - y - h) * MM, w * MM, h * MM);
    }
    private static final ImageBlock IMAGE = new ImageBlock("scan", 1, box(0, 0, 200, 200), "image/png", null, "IMAGE", 0);
    private static TextBlock line(String text, double y) {
        Rect r = box(30, y, 60, 10);
        return new TextBlock("line", 1, r, text, 0, null, 1, 0, 0, List.of(), Transform2D.IDENTITY,
                List.of(new TextBlock.OcrWord(r, text, .99)));
    }
    @FunctionalInterface interface Paint { void draw(PDPageContentStream stream) throws IOException; }
    private PdfOcrVisibility inspect(Paint before, Paint after, int limit) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200)); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                before.draw(stream);
                stream.drawImage(LosslessFactory.createFromImage(document, new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)), 0, 0, 200, 200);
                after.draw(stream);
            }
            return PdfOcrVisibility.inspect(page, List.of(IMAGE), limit);
        }
    }
    private PdfOcrVisibility inspect(Paint after) throws Exception { return inspect(s -> {}, after, 20_000); }
    private static void cover(PDPageContentStream s) throws IOException { s.addRect(29, 39, 62, 12); s.fill(); }
    private static void uncertain(PdfOcrVisibility visibility) {
        var error = assertThrows(ConversionFailureException.class, () -> visibility.filter(List.of(line("-00085.20", 40))));
        assertEquals("OCR_VISIBILITY_UNCERTAIN", error.code());
    }

    @Test void opaqueCoverRemovesOnlyHiddenLineAndPreservesUnrelatedDigitsAndCoordinates() throws Exception {
        var visible = line("Control 26.85", 100);
        var result = inspect(PdfOcrVisibilityTest::cover).filter(List.of(line("-00085.20", 40), visible));
        assertEquals(1, result.hiddenWords()); assertEquals(List.of(visible), result.blocks());
        assertSame(visible, result.blocks().get(0));
    }
    @Test void coverBeforeImageDoesNotHideScan() throws Exception {
        var blocks = List.of(line("127.50", 40));
        assertEquals(blocks, inspect(PdfOcrVisibilityTest::cover, s -> {}, 20_000).filter(blocks).blocks());
    }
    @Test void noCoverKeepsAllSourceNumbers() throws Exception {
        var blocks = List.of(line("-00085.20", 40), line("26.85", 100));
        assertEquals(blocks, inspect(s -> {}).filter(blocks).blocks());
    }
    @Test void nativeGridOrderRequiresActuallyUncoveredRules() throws Exception {
        var grid = new OcrRuledGrid.Grid(box(0, 0, 200, 200), List.of(), List.of(box(30, 40, 60, 1)));
        assertTrue(inspect(s -> {}).unobscuredRules(grid));
        assertFalse(inspect(PdfOcrVisibilityTest::cover).unobscuredRules(grid));
        assertFalse(inspect(s -> { var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(.5f); s.setGraphicsStateParameters(state); cover(s); }).unobscuredRules(grid));
    }
    @Test void adjacentGrayStripsTogetherProveFullCover() throws Exception {
        var result = inspect(s -> { s.setNonStrokingColor(.7f); s.addRect(29, 39, 31, 12); s.fill(); s.setNonStrokingColor(.8f); s.addRect(60, 39, 31, 12); s.fill(); });
        assertTrue(result.filter(List.of(line("127.50", 40))).blocks().isEmpty());
    }
    @Test void partialCoverFailsInsteadOfSplicingNumber() throws Exception {
        uncertain(inspect(s -> { s.addRect(29, 39, 30, 12); s.fill(); }));
    }
    @Test void transparentCoverDoesNotProveOcclusion() throws Exception {
        uncertain(inspect(s -> { var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(.5f); s.setGraphicsStateParameters(state); cover(s); }));
    }
    @Test void nonNormalBlendDoesNotProveOcclusion() throws Exception {
        uncertain(inspect(s -> { var state = new PDExtendedGraphicsState(); state.setBlendMode(BlendMode.MULTIPLY); s.setGraphicsStateParameters(state); cover(s); }));
    }
    @Test void clippingRestrictsActualCover() throws Exception {
        uncertain(inspect(s -> { s.addRect(29, 39, 30, 12); s.clip(); cover(s); }));
    }
    @Test void hiddenNativeTextCannotJustifyDroppingScan() throws Exception {
        uncertain(inspect(s -> { cover(s); s.beginText(); s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); s.setRenderingMode(RenderingMode.NEITHER); s.showText("128.75"); s.endText(); }));
    }
    @Test void partlyHiddenLineFailsWithoutJoiningSeparateWords() throws Exception {
        var first = line("-00085.20", 40); var second = line("26.85", 100);
        var mixed = new TextBlock("mixed", 1, box(30, 40, 60, 70), "-00085.20 26.85", 0, null, 1, 0, 0, List.of(), Transform2D.IDENTITY,
                List.of(first.ocrWords().get(0), second.ocrWords().get(0)));
        assertThrows(ConversionFailureException.class, () -> inspect(PdfOcrVisibilityTest::cover).filter(List.of(mixed)));
    }
    @Test void missingWordGeometryFailsConservatively() throws Exception {
        var wordless = new TextBlock("line", 1, box(30, 40, 60, 10), "127.50", 0, null, 1);
        assertThrows(ConversionFailureException.class, () -> inspect(PdfOcrVisibilityTest::cover).filter(List.of(wordless)));
    }
    @Test void operatorAndCoverLimitsAreEnforced() {
        assertThrows(IOException.class, () -> inspect(s -> {}, PdfOcrVisibilityTest::cover, 1));
        assertThrows(IOException.class, () -> inspect(s -> { for (int i = 0; i < 513; i++) cover(s); }));
    }
    @Test void rotatedPagesAndMultipleImagesRemainOutsideProofScope() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200)); document.addPage(page);
            assertNull(PdfOcrVisibility.inspect(page, List.of(IMAGE, IMAGE), 20_000));
            page.setRotation(90);
            assertNull(PdfOcrVisibility.inspect(page, List.of(IMAGE), 20_000));
        }
    }
}
