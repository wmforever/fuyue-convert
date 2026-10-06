package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import org.junit.jupiter.api.Test;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import static org.junit.jupiter.api.Assertions.*;

class OcrLatinWordFitTest {
    private final Font measured = new Font("Dialog", Font.PLAIN, 100);
    // A deterministic wider substitute reproduces the host/Office metric
    // disagreement without making a CI test depend on installed font files.
    private final Font compatible = measured.deriveFont(AffineTransform.getScaleInstance(1.2, 1));
    private final FontRenderContext context = new FontRenderContext(null, true, true);
    private TextBlock.OcrWord word(String text, double confidence) {
        return new TextBlock.OcrWord(new Rect(20, 30, 20, 4), text, confidence);
    }
    private double advance(String text, double size) {
        var glyphs=compatible.deriveFont((float)size).createGlyphVector(context,text);
        return glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX();
    }
    @Test void keepsActualEmittedWordInsideExistingBoxWithoutChangingSourceGeometry() {
        var source=word("RESERVE", .98); double size=13.4, original=1.19;
        double availablePt=advance(source.text(),13.5)*1.1;
        assertTrue(advance(source.text(),13.5)*Math.round(original*100)/100d > availablePt);
        double fitted=FixedLayoutDocxRenderer.latinWordScale(source,measured,compatible,size,original,availablePt*25.4/72);
        assertTrue(fitted < original && fitted >= .6);
        assertTrue(advance(source.text(),13.5)*Math.round(fitted*100)/100d <= availablePt);
        assertEquals(new Rect(20,30,20,4),source.box()); assertEquals("RESERVE",source.text());
    }
    @Test void preservesNumericLowConfidenceUnmeasuredAndAlreadyFittingFallbacks() {
        for (String text : new String[]{"048.65","-12.50","00793","2064-09-28","A","中文","CODE007"})
            assertEquals(1.19,FixedLayoutDocxRenderer.latinWordScale(word(text,.98),measured,compatible,13.4,1.19,20));
        assertEquals(1.19,FixedLayoutDocxRenderer.latinWordScale(word("RESERVE",.45),measured,compatible,13.4,1.19,20));
        assertEquals(1.19,FixedLayoutDocxRenderer.latinWordScale(word("RESERVE",.98),measured,null,13.4,1.19,20));
        assertEquals(1.19,FixedLayoutDocxRenderer.latinWordScale(word("RESERVE",.98),measured,compatible,13.4,1.19,100));
        assertEquals(1.19,FixedLayoutDocxRenderer.latinWordScale(word("RESERVE",.98),new Font("Serif",0,100),compatible,13.4,1.19,20));
        assertEquals(1.19,FixedLayoutDocxRenderer.latinWordScale(word("RESERVE",.98),measured,compatible,13.4,1.19,1));
    }

    @Test void detectsNeighborOverlapEvenWhenTransparentWrappingBoxIsWideEnough() {
        var source = word("REVIEW", .98);
        var next = new TextBlock.OcrWord(new Rect(43, 30, 20, 4), "2036", .98);
        double size = 13.4, original = 1.19;
        var positioned = new Rect(19.5, 29, 20, 6);
        double wide = 40;
        assertEquals(original, FixedLayoutDocxRenderer.latinWordScale(source, measured, compatible,
                size, original, wide), "the old wrapping-only check misses the neighbor collision");
        double bounded = FixedLayoutDocxRenderer.latinWordWidthBeforeNext(source, next, measured,
                size, positioned, wide);
        assertTrue(bounded < wide);
        double fitted = FixedLayoutDocxRenderer.latinWordScale(source, measured, compatible,
                size, original, wide, bounded);
        assertTrue(fitted < original && fitted >= .6);
        assertTrue(advance(source.text(), 13.5) * Math.round(fitted * 100d) / 100d <= bounded * 72d / 25.4d);
        assertEquals(new Rect(43, 30, 20, 4), next.box());
        assertEquals("2036", next.text(), "numeric neighbor remains immutable");
    }

    @Test void failedNeighborProofCannotUndoAnAlreadyValidatedWrappingFit() {
        var source = word("REVENUE", .98);
        double size = 13.4, original = 1.19;
        double wrapping = advance(source.text(), 13.5) * 1.1 * 25.4 / 72;
        double established = FixedLayoutDocxRenderer.latinWordScale(source, measured, compatible,
                size, original, wrapping);
        assertTrue(established < original);
        // The conservative neighbor limit cannot contain even the compatible
        // candidate. Rejecting that proof must retain the accepted baseline.
        assertEquals(original, FixedLayoutDocxRenderer.latinWordScale(source, measured, compatible,
                size, original, 1));
        assertEquals(established, FixedLayoutDocxRenderer.latinWordScale(source, measured, compatible,
                size, original, wrapping, 1));
    }

    @Test void keepsUncertainOverlappingAndOtherLineNeighborsOutOfWidthProof() {
        var source = word("REVIEW", .98);
        var positioned = new Rect(19.5, 29, 20, 6);
        for (var next : new TextBlock.OcrWord[]{
                new TextBlock.OcrWord(new Rect(43, 30, 20, 4), "2036", .45),
                new TextBlock.OcrWord(new Rect(39, 30, 20, 4), "2036", .98),
                new TextBlock.OcrWord(new Rect(43, 40, 20, 4), "2036", .98)}) {
            assertEquals(40, FixedLayoutDocxRenderer.latinWordWidthBeforeNext(source, next, measured,
                    13.4, positioned, 40));
        }
    }
}
