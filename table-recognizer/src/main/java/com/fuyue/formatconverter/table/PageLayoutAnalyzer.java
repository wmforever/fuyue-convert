package com.fuyue.formatconverter.table;

import com.fuyue.formatconverter.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PageLayoutAnalyzer {
    private final GridTableRecognizer tableRecognizer;

    public PageLayoutAnalyzer() { this(new GridTableRecognizer(TableRecognitionConfig.defaults())); }
    public PageLayoutAnalyzer(GridTableRecognizer tableRecognizer) { this.tableRecognizer = tableRecognizer; }

    public PageModel analyze(PageModel page) {
        List<TableModel> tables = tableRecognizer.recognize(page);
        List<TextBlock> body = page.textBlocks().stream()
                .filter(text -> tables.stream().noneMatch(table -> table.box().contains(text.box().center(), 0.2)))
                .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(t -> t.box().x()))
                .toList();
        List<ParagraphModel> paragraphs = groupVisualLines(body, page.physicalBox());
        List<ConversionWarning> warnings = tables.stream().flatMap(t -> t.warnings().stream()).toList();
        return page.withLayout(paragraphs, tables, warnings);
    }

    private List<ParagraphModel> groupVisualLines(List<TextBlock> blocks, Rect page) {
        List<List<TextBlock>> lines = new ArrayList<>();
        for (TextBlock block : blocks) {
            List<TextBlock> line = lines.stream()
                    .filter(candidate -> sameVisualLine(candidate.get(0), block))
                    .findFirst().orElse(null);
            if (line == null) {
                line = new ArrayList<>();
                lines.add(line);
            }
            line.add(block);
        }
        List<List<TextBlock>> segments = new ArrayList<>();
        double minimumColumnGap = Math.max(12d, page.width() * 0.06d);
        for (List<TextBlock> line : lines) {
            line.sort(Comparator.comparingDouble(text -> text.box().x()));
            List<TextBlock> segment = new ArrayList<>();
            double previousRight = Double.NEGATIVE_INFINITY;
            for (TextBlock block : line) {
                // Equal baselines do not imply one paragraph: two columns often
                // share every baseline. Keep a clear gutter outside the paragraph
                // so the Word renderer can retain each column's source position.
                if (!segment.isEmpty() && block.box().x() - previousRight > minimumColumnGap) {
                    segments.add(segment);
                    segment = new ArrayList<>();
                }
                segment.add(block);
                previousRight = Math.max(previousRight, block.box().right());
            }
            if (!segment.isEmpty()) segments.add(segment);
        }
        return segments.stream().map(line -> {
            Rect lineBox = line.stream().map(TextBlock::box).reduce(Rect::union).orElseThrow();
            return new ParagraphModel(lineBox, line, inferAlignment(lineBox, page), 0);
        }).sorted(Comparator.comparingDouble((ParagraphModel paragraph) -> paragraph.box().y())
                .thenComparingDouble(paragraph -> paragraph.box().x())).toList();
    }

    private boolean sameVisualLine(TextBlock first, TextBlock second) {
        double height = Math.max(1d, Math.min(first.box().height(), second.box().height()));
        double tolerance = Math.max(0.6d, Math.min(1.5d, height * 0.3d));
        return Math.abs(first.baselineY() - second.baselineY()) <= tolerance;
    }

    private ParagraphModel.Alignment inferAlignment(Rect text, Rect page) {
        double left = text.x() - page.x();
        double right = page.right() - text.right();
        if (Math.abs(left - right) <= Math.max(2.0, page.width() * 0.012)) return ParagraphModel.Alignment.CENTER;
        if (right < 3 && left > right * 2) return ParagraphModel.Alignment.RIGHT;
        return ParagraphModel.Alignment.LEFT;
    }
}
