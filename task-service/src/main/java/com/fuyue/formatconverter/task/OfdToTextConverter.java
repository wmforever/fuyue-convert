package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.parser.SafeOfdPackage;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class OfdToTextConverter implements FileConverter {
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.TXT,
            "提取文字型 OFD 的可编辑文本，按页面、多栏阅读顺序和表格行列输出 UTF-8 文本。",
            QualityLevel.BETA, ConversionStrategy.EXTRACTION, List.of(),
            List.of("扫描型或含扫描内容页的 OFD 在未配置 OCR 时严格失败"));

    public OfdToTextConverter(SafeOfdExtractor extractor, OfdParser parser, PageLayoutAnalyzer analyzer) {
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        List<ConversionWarning> warnings = new ArrayList<>();
        SafeOfdPackage safe = extractor.extract(input.path(), workDir, limits);
        progress.update(TaskStage.PARSING, 15);
        DocumentModel parsed = parser.parse(safe, input.displayName(), limits);
        warnings.addAll(parsed.warnings());
        List<Integer> ocrPages = parsed.pages().stream()
                .filter(page -> page.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_REQUIRED))
                .map(PageModel::pageNumber)
                .toList();
        if (!ocrPages.isEmpty()) {
            throw new ConversionFailureException("OCR_REQUIRED",
                    "第 " + ocrPages.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("")
                            + " 页包含无法提取的扫描内容；当前未配置 OCR，未生成不完整 TXT");
        }
        progress.update(TaskStage.RECOGNIZING, 55);
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze).toList();
        pages.forEach(page -> warnings.addAll(page.warnings()));
        progress.update(TaskStage.RENDERING, 80);
        Files.writeString(outputPath, text(pages), StandardCharsets.UTF_8);
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.pages().size(), warnings);
    }

    static String text(List<PageModel> pages) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            PageModel page = pages.get(i);
            if (i > 0) out.append(System.lineSeparator()).append(System.lineSeparator());
            if (pages.size() > 1) out.append("第 ").append(page.pageNumber()).append(" 页").append(System.lineSeparator());
            readingUnits(page).stream().flatMap(unit -> unit.lines().stream())
                    .map(String::stripTrailing)
                    .filter(line -> !line.isBlank())
                    .forEach(line -> out.append(line).append(System.lineSeparator()));
        }
        return out.toString();
    }

    private static List<ReadingUnit> readingUnits(PageModel page) {
        Set<TextBlock> tableTexts = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ReadingUnit> units = new ArrayList<>();
        for (TableModel table : page.tables()) {
            List<String> rows = tableRows(table, tableTexts);
            if (!rows.isEmpty()) units.add(new ReadingUnit(table.box(), rows));
        }
        List<TextBlock> body = page.textBlocks().stream()
                .filter(block -> !tableTexts.contains(block))
                .toList();
        for (List<TextBlock> line : visualLines(body, page.physicalBox())) {
            String value = line.stream().map(TextBlock::text).reduce("", String::concat).strip();
            if (!value.isEmpty()) {
                Rect box = line.stream().map(OfdToTextConverter::displayedBox).reduce(Rect::union).orElseThrow();
                units.add(new ReadingUnit(box, List.of(value)));
            }
        }
        return spatialOrder(units, page.physicalBox());
    }

    private static List<String> tableRows(TableModel table, Set<TextBlock> emittedTexts) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < table.rowCount(); row++) {
            String[] columns = new String[table.columnCount()];
            java.util.Arrays.fill(columns, "");
            final int rowIndex = row;
            table.cells().stream().filter(cell -> cell.row() == rowIndex)
                    .sorted(Comparator.comparingInt(CellModel::column))
                    .forEach(cell -> columns[cell.column()] = cellText(cell, emittedTexts));
            int last = columns.length - 1;
            while (last >= 0 && columns[last].isBlank()) last--;
            if (last >= 0) rows.add(String.join("\t", java.util.Arrays.copyOf(columns, last + 1)));
        }
        return rows;
    }

    private static String cellText(CellModel cell, Set<TextBlock> emittedTexts) {
        return cell.paragraphs().stream()
                .map(paragraph -> paragraph.runs().stream()
                        .sorted(Comparator.comparingDouble(OfdToTextConverter::left))
                        .filter(emittedTexts::add)
                        .map(TextBlock::text).reduce("", String::concat).strip())
                .filter(value -> !value.isEmpty())
                .reduce((first, second) -> first + " " + second)
                .orElse("");
    }

    private static List<List<TextBlock>> visualLines(List<TextBlock> blocks, Rect page) {
        List<TextBlock> ordered = blocks.stream()
                .filter(block -> !block.text().isBlank())
                .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(OfdToTextConverter::left))
                .toList();
        List<List<TextBlock>> lines = new ArrayList<>();
        List<TextBlock> current = null;
        for (TextBlock block : ordered) {
            if (current == null || !sameVisualLine(current.get(0), block)) {
                current = new ArrayList<>();
                lines.add(current);
            }
            current.add(block);
        }
        List<List<TextBlock>> split = new ArrayList<>();
        double minimumColumnGap = Math.max(15d, page.width() * 0.10d);
        for (List<TextBlock> line : lines) {
            line.sort(Comparator.comparingDouble(OfdToTextConverter::left));
            List<TextBlock> segment = new ArrayList<>();
            double previousRight = Double.NEGATIVE_INFINITY;
            for (TextBlock block : line) {
                Rect displayed = displayedBox(block);
                if (!segment.isEmpty() && displayed.x() - previousRight > minimumColumnGap) {
                    split.add(segment);
                    segment = new ArrayList<>();
                }
                segment.add(block);
                previousRight = Math.max(previousRight, displayed.right());
            }
            if (!segment.isEmpty()) split.add(segment);
        }
        return split;
    }

    private static boolean sameVisualLine(TextBlock first, TextBlock second) {
        double height = Math.max(1d, Math.min(first.box().height(), second.box().height()));
        double tolerance = Math.max(0.6d, Math.min(1.5d, height * 0.3d));
        return Math.abs(first.baselineY() - second.baselineY()) <= tolerance;
    }

    private static List<ReadingUnit> spatialOrder(List<ReadingUnit> source, Rect page) {
        if (source.size() < 2) return List.copyOf(source);
        List<ReadingUnit> vertical = splitAtLargestGap(source, true, Math.max(12d, page.width() * 0.06d));
        if (vertical != null) return vertical;
        double medianHeight = source.stream().mapToDouble(unit -> unit.box().height()).sorted()
                .skip((source.size() - 1L) / 2L).findFirst().orElse(4d);
        List<ReadingUnit> horizontal = splitAtLargestGap(source, false, Math.max(5d, medianHeight * 1.8d));
        if (horizontal != null) return horizontal;
        return source.stream().sorted(Comparator.comparingDouble((ReadingUnit unit) -> unit.box().y())
                .thenComparingDouble(unit -> unit.box().x())).toList();
    }

    private static List<ReadingUnit> splitAtLargestGap(List<ReadingUnit> source, boolean byX, double threshold) {
        List<ReadingUnit> sorted = source.stream().sorted(Comparator.comparingDouble(unit -> start(unit, byX))).toList();
        double furthestEnd = end(sorted.get(0), byX);
        int split = -1;
        double largest = threshold;
        for (int index = 1; index < sorted.size(); index++) {
            double gap = start(sorted.get(index), byX) - furthestEnd;
            if (gap > largest) {
                largest = gap;
                split = index;
            }
            furthestEnd = Math.max(furthestEnd, end(sorted.get(index), byX));
        }
        if (split < 1) return null;
        List<ReadingUnit> result = new ArrayList<>(source.size());
        Rect bounds = source.stream().map(ReadingUnit::box).reduce(Rect::union).orElseThrow();
        result.addAll(spatialOrder(sorted.subList(0, split), bounds));
        result.addAll(spatialOrder(sorted.subList(split, sorted.size()), bounds));
        return result;
    }

    private static double start(ReadingUnit unit, boolean byX) {
        return byX ? unit.box().x() : unit.box().y();
    }

    private static double end(ReadingUnit unit, boolean byX) {
        return byX ? unit.box().right() : unit.box().bottom();
    }

    private static Rect displayedBox(TextBlock block) {
        double height = Math.max(1d, block.style().sizePt() * 25.4d / 72d);
        int glyphs = Math.max(1, block.text().codePointCount(0, block.text().length()));
        double width = block.advancesMm().isEmpty()
                ? Math.max(1d, Math.min(block.box().width(), glyphs * height))
                : Math.max(1d, block.advancesMm().stream().mapToDouble(Math::abs).sum() + height);
        return new Rect(left(block), block.baselineY() - height, width, height);
    }

    private static double left(TextBlock block) {
        return block.box().x() + block.textOffsetXmm();
    }

    private record ReadingUnit(Rect box, List<String> lines) { }

    private String outputFileName(String input) {
        String base = input.replaceFirst("(?i)\\.ofd$", "");
        return base + "." + route.targetFormat().extension();
    }
}
