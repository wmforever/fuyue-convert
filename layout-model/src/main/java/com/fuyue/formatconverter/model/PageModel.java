package com.fuyue.formatconverter.model;

import java.util.List;

public record PageModel(int pageNumber, Rect physicalBox, List<TextBlock> textBlocks,
                        List<LineElement> lines, List<ImageBlock> images,
                        List<ParagraphModel> paragraphs, List<TableModel> tables,
                        List<ConversionWarning> warnings) {
    public PageModel {
        textBlocks = copy(textBlocks);
        lines = copy(lines);
        images = copy(images);
        paragraphs = copy(paragraphs);
        tables = copy(tables);
        warnings = copy(warnings);
    }
    private static <T> List<T> copy(List<T> value) { return value == null ? List.of() : List.copyOf(value); }
    public PageModel withLayout(List<ParagraphModel> newParagraphs, List<TableModel> newTables,
                                List<ConversionWarning> extraWarnings) {
        var allWarnings = new java.util.ArrayList<>(warnings);
        if (extraWarnings != null) allWarnings.addAll(extraWarnings);
        return new PageModel(pageNumber, physicalBox, textBlocks, lines, images,
                newParagraphs, newTables, allWarnings);
    }
}

