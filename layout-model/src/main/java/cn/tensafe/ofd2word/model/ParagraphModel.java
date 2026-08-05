package cn.tensafe.ofd2word.model;

import java.util.List;

public record ParagraphModel(Rect box, List<TextBlock> runs, Alignment alignment, double lineSpacingMm) {
    public enum Alignment { LEFT, CENTER, RIGHT, JUSTIFY }
    public ParagraphModel {
        runs = runs == null ? List.of() : List.copyOf(runs);
        alignment = alignment == null ? Alignment.LEFT : alignment;
    }
}

