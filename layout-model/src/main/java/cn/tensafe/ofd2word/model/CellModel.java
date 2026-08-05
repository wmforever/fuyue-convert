package cn.tensafe.ofd2word.model;

import java.util.List;

public record CellModel(int row, int column, int rowSpan, int columnSpan, Rect box,
                        List<ParagraphModel> paragraphs, BorderStyle top, BorderStyle right,
                        BorderStyle bottom, BorderStyle left, ColorValue fill,
                        ParagraphModel.Alignment horizontalAlignment, VerticalAlignment verticalAlignment) {
    public enum VerticalAlignment { TOP, CENTER, BOTTOM }
    public CellModel {
        if (row < 0 || column < 0 || rowSpan < 1 || columnSpan < 1) {
            throw new IllegalArgumentException("Invalid cell coordinates or span");
        }
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        top = top == null ? BorderStyle.NONE : top;
        right = right == null ? BorderStyle.NONE : right;
        bottom = bottom == null ? BorderStyle.NONE : bottom;
        left = left == null ? BorderStyle.NONE : left;
        horizontalAlignment = horizontalAlignment == null ? ParagraphModel.Alignment.LEFT : horizontalAlignment;
        verticalAlignment = verticalAlignment == null ? VerticalAlignment.CENTER : verticalAlignment;
    }
}

