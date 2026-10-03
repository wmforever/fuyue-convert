package com.fuyue.formatconverter.model;

import java.util.List;

public record ParagraphModel(Rect box, List<TextBlock> runs, Alignment alignment, double lineSpacingMm,
                             Flow flow) {
    public enum Alignment { LEFT, CENTER, RIGHT, JUSTIFY }
    /** Source geometry for a reconstructed paragraph whose lines may reflow in Word. */
    public record Flow(int sourceLineCount, double firstLineIndentMm) { }

    public ParagraphModel(Rect box, List<TextBlock> runs, Alignment alignment, double lineSpacingMm) {
        this(box, runs, alignment, lineSpacingMm, null);
    }
    public ParagraphModel {
        runs = runs == null ? List.of() : List.copyOf(runs);
        alignment = alignment == null ? Alignment.LEFT : alignment;
    }
}
