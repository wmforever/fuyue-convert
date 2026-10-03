package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Conservative prose reconstruction for the editable PDF route. */
final class PdfParagraphReconstructor {
    private static final Pattern LIST_START = Pattern.compile(
            "^(?:[•●▪◦◆■□✓☑☐]|[-–—]\\s|[0-9]+[.)、．]|(?:[A-Za-z]|[ivxlcdmIVXLCDM]{1,6})[.)]\\s|"
                    + "[（(](?:[0-9一二三四五六七八九十]+|[A-Za-z]|[ivxlcdmIVXLCDM]{1,6})[）)]|"
                    + "[一二三四五六七八九十]+[、．.]|第[0-9一二三四五六七八九十百]+[章节条]).*");
    private static final Pattern SENTENCE = Pattern.compile("[。！？!?；;]|(?<![0-9])\\.(?=\\s|[\\\"”’）)]|$)");

    DocumentModel reconstructAcrossPages(DocumentModel document) {
        List<PageModel> pages = document.pages();
        if (pages.size() < 2 || pages.stream().anyMatch(page -> !plainFlowPage(page))) return document;
        PageModel firstPage = pages.get(0);
        ParagraphModel first = firstPage.paragraphs().get(0);
        FontStyle font = dominantRun(first).style();
        double leading = first.lineSpacingMm();
        double top = first.box().y() - firstPage.physicalBox().y();
        double bottom = firstPage.physicalBox().bottom() - flowBottom(last(firstPage));
        double em = font.sizePt() * 25.4d / 72d;
        double tolerance = Math.max(0.7d, em * 0.3d);
        if (top < em * 2 || top > firstPage.physicalBox().height() * 0.2d
                || bottom < em || bottom > firstPage.physicalBox().height() * 0.18d) return document;
        for (int index = 0; index < pages.size(); index++) {
            PageModel page = pages.get(index);
            Rect box = page.physicalBox(), original = firstPage.physicalBox();
            if (Math.abs(box.x() - original.x()) > 0.1d || Math.abs(box.y() - original.y()) > 0.1d
                    || Math.abs(box.width() - original.width()) > 0.1d
                    || Math.abs(box.height() - original.height()) > 0.1d
                    || Math.abs(page.paragraphs().get(0).box().y() - box.y() - top) > tolerance) return document;
            if (page.paragraphs().stream().anyMatch(paragraph -> !sameFont(paragraph, font)
                    || Math.abs(paragraph.lineSpacingMm() - leading) > 0.2d
                    || Math.abs(paragraph.box().x() - first.box().x()) > tolerance
                    || Math.abs(paragraph.box().right() - first.box().right()) > em)) return document;
            if (index < pages.size() - 1
                    && Math.abs(box.bottom() - flowBottom(last(page)) - bottom) > tolerance) return document;
            if (index > 0 && !continuesAcross(last(pages.get(index - 1)), page.paragraphs().get(0), em)) {
                return document;
            }
        }
        return new DocumentModel(document.sourceName(), document.parserName(), document.sourcePageCount(),
                pages, document.warnings(), new DocumentModel.ContinuousFlow(top, bottom));
    }

    private boolean plainFlowPage(PageModel page) {
        return !page.paragraphs().isEmpty() && page.tables().isEmpty() && page.images().isEmpty()
                && page.lines().isEmpty() && page.paragraphs().stream().allMatch(paragraph ->
                paragraph.flow() != null && !paragraph.runs().isEmpty()
                        && paragraph.runs().stream().allMatch(run ->
                        Math.abs(run.transform().rotationDegrees()) < 0.5d && !run.transform().hasSkew(0.02d)))
                && page.paragraphs().stream().mapToInt(paragraph -> paragraph.runs().size()).sum()
                == page.textBlocks().size();
    }

    private boolean continuesAcross(ParagraphModel previous, ParagraphModel next, double em) {
        if (next.flow().firstLineIndentMm() > Math.max(0.7d, em * 0.3d)
                || startsList(firstLine(previous)) || startsList(firstLine(next))) return false;
        String end = previous.runs().get(previous.runs().size() - 1).text().stripTrailing();
        // A terminal sentence, short last line or an indented next line is ambiguous.
        if (end.isEmpty() || end.matches("(?s).*[。！？!?；;：:.][\\\"”’）)]*$")) return false;
        double lastBaseline = previous.runs().stream().mapToDouble(TextBlock::baselineY).max().orElseThrow();
        double lastRight = previous.runs().stream().filter(run -> Math.abs(run.baselineY() - lastBaseline) < 0.5d)
                .mapToDouble(run -> run.box().right()).max().orElseThrow();
        return previous.box().right() - lastRight <= wrapSlack(firstLine(next), em, previous.box().width());
    }

    private ParagraphModel firstLine(ParagraphModel paragraph) {
        double firstBaseline = paragraph.runs().get(0).baselineY();
        List<TextBlock> runs = paragraph.runs().stream()
                .filter(run -> Math.abs(run.baselineY() - firstBaseline) < 0.5d).toList();
        Rect box = runs.stream().map(TextBlock::box).reduce(Rect::union).orElseThrow();
        return new ParagraphModel(box, runs, paragraph.alignment(), paragraph.lineSpacingMm());
    }

    private ParagraphModel last(PageModel page) { return page.paragraphs().get(page.paragraphs().size() - 1); }
    private double flowBottom(ParagraphModel paragraph) {
        return paragraph.box().y() + paragraph.lineSpacingMm() * paragraph.flow().sourceLineCount();
    }

    PageModel reconstruct(PageModel page) {
        List<ParagraphModel> lines = page.paragraphs().stream()
                .sorted(Comparator.comparingDouble(line -> line.box().y())).toList();
        if (lines.size() < 2 || !page.tables().isEmpty() || !page.images().isEmpty()
                || lines.stream().flatMap(line -> line.runs().stream()).anyMatch(run ->
                Math.abs(run.transform().rotationDegrees()) > 0.5d || run.transform().hasSkew(0.02d))
                || hasSideBySideLines(lines)) return page;

        List<ParagraphModel> longLines = lines.stream()
                .filter(line -> line.box().width() >= page.physicalBox().width() * 0.5d)
                .filter(line -> !text(line).isBlank() && !startsList(line)).toList();
        if (longLines.size() < 2) return page;
        Map<FontStyle, Integer> weights = new HashMap<>();
        longLines.forEach(line -> weights.merge(dominantRun(line).style(), text(line).length(), Integer::sum));
        FontStyle body = weights.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        List<ParagraphModel> bodyLines = longLines.stream().filter(line -> sameFont(line, body)).toList();
        if (bodyLines.size() < 2) return page;
        double left = bodyLines.stream().mapToDouble(line -> line.box().x()).min().orElseThrow();
        double right = bodyLines.stream().mapToDouble(line -> line.box().right()).max().orElseThrow();
        double em = body.sizePt() * 25.4d / 72d;
        double tolerance = Math.max(0.7d, em * 0.3d);
        List<ParagraphModel> result = new ArrayList<>();
        List<ParagraphModel> group = new ArrayList<>();
        for (ParagraphModel line : lines) {
            if (!group.isEmpty() && !continues(group, line, body, left, right, em, tolerance, page.lines())) {
                addGroup(result, group, left, right);
                group.clear();
            }
            group.add(line);
        }
        if (!group.isEmpty()) addGroup(result, group, left, right);
        // A final baseline may fit the PDF while a full Word line box does not.
        // Keep the original line paragraphs in that narrow page-edge case.
        if (result.stream().anyMatch(paragraph -> paragraph.flow() != null && paragraph.box().y()
                + paragraph.lineSpacingMm() * paragraph.flow().sourceLineCount() > page.physicalBox().bottom() - 1d)) {
            return page;
        }
        return page.withLayout(result, page.tables(), List.of());
    }

    private boolean continues(List<ParagraphModel> group, ParagraphModel next, FontStyle font,
                              double left, double right, double em, double tolerance, List<LineElement> rules) {
        ParagraphModel previous = group.get(group.size() - 1);
        if (startsList(previous) || startsList(next) || !sameFont(previous, font) || !sameFont(next, font)) return false;
        if (rules.stream().anyMatch(rule -> rule.horizontal(0.3d)
                && rule.minY() >= previous.box().bottom() - 0.2d
                && rule.maxY() <= next.box().y() + 0.2d
                && Math.min(right, rule.maxX()) - Math.max(left, rule.minX()) >= (right - left) * 0.5d)) return false;
        // An indented line starts a new paragraph; only its following flush-left
        // lines may join it. Short endings and punctuation-only lines stay separate.
        if (Math.abs(next.box().x() - left) > tolerance
                || previous.box().x() < left - tolerance
                || previous.box().x() - left > em * 3d
                || right - previous.box().right() > wrapSlack(next, em, right - left)
                || next.box().right() > right + tolerance
                || text(next).codePointCount(0, text(next).length()) < 3) return false;
        double spacing = baseline(next) - baseline(previous);
        if (spacing < em * 1.05d || spacing > em * 1.85d) return false;
        if (group.size() > 1) {
            double priorSpacing = baseline(previous) - baseline(group.get(group.size() - 2));
            if (Math.abs(spacing - priorSpacing) > Math.max(0.5d, priorSpacing * 0.12d)) return false;
        }
        return true;
    }

    private double wrapSlack(ParagraphModel next, double em, double bodyWidth) {
        String value = text(next);
        int wordEnd = 0;
        while (wordEnd < value.length() && value.charAt(wordEnd) <= 0x7f
                && !Character.isWhitespace(value.charAt(wordEnd))) wordEnd++;
        // A wrapped Latin line may leave room for several glyphs but not the
        // whole next word. Estimate that word from the source run's measured
        // width; retain a bound so a genuinely short paragraph ending is not joined.
        double nextWordWidth = value.isEmpty() ? 0 : next.box().width() * wordEnd / value.length();
        return Math.max(em * 1.6d, Math.min(bodyWidth * 0.18d, nextWordWidth + em * 0.6d));
    }

    private ParagraphModel join(List<ParagraphModel> lines, double left, double right) {
        if (lines.size() == 1) return lines.get(0);
        ParagraphModel first = lines.get(0), last = lines.get(lines.size() - 1);
        double spacing = (baseline(last) - baseline(first)) / (lines.size() - 1);
        List<TextBlock> runs = lines.stream().flatMap(line -> line.runs().stream()
                .sorted(Comparator.comparingDouble(run -> run.box().x()))).toList();
        Rect box = new Rect(left, first.box().y(), right - left, last.box().bottom() - first.box().y());
        return new ParagraphModel(box, runs, ParagraphModel.Alignment.LEFT, spacing,
                new ParagraphModel.Flow(lines.size(), Math.max(0, first.box().x() - left)));
    }

    private void addGroup(List<ParagraphModel> result, List<ParagraphModel> group, double left, double right) {
        // Uniform spacing alone also describes ledgers and independent records.
        // Require prose punctuation somewhere in the group, without mistaking
        // decimal/version numbers for sentence endings.
        if (group.size() > 1 && !hasCenteredShape(group)
                && group.stream().anyMatch(line -> SENTENCE.matcher(text(line)).find())) {
            result.add(join(group, left, right));
        } else {
            result.addAll(group);
        }
    }

    private boolean hasCenteredShape(List<ParagraphModel> lines) {
        if (lines.stream().anyMatch(line -> line.alignment() != ParagraphModel.Alignment.CENTER)) return false;
        double minCenter = lines.stream().mapToDouble(line -> line.box().x() + line.box().width() / 2d).min().orElseThrow();
        double maxCenter = lines.stream().mapToDouble(line -> line.box().x() + line.box().width() / 2d).max().orElseThrow();
        double minWidth = lines.stream().mapToDouble(line -> line.box().width()).min().orElseThrow();
        double maxWidth = lines.stream().mapToDouble(line -> line.box().width()).max().orElseThrow();
        // Fully justified body lines may also be tagged CENTER by the analyzer.
        // Varying widths with the same center provide stronger layout evidence.
        return maxCenter - minCenter <= 0.5d && maxWidth - minWidth > 1.4d;
    }

    private boolean hasSideBySideLines(List<ParagraphModel> lines) {
        for (int index = 1; index < lines.size(); index++) {
            Rect previous = lines.get(index - 1).box(), current = lines.get(index).box();
            if (current.y() < previous.bottom() - Math.min(previous.height(), current.height()) * 0.2d) return true;
        }
        return false;
    }

    private boolean sameFont(ParagraphModel line, FontStyle body) {
        if (line.runs().isEmpty()) return false;
        FontStyle dominant = dominantRun(line).style();
        return dominant.family().equals(body.family()) && dominant.bold() == body.bold()
                && dominant.italic() == body.italic() && line.runs().stream()
                .allMatch(run -> Math.abs(run.style().sizePt() - body.sizePt()) <= 0.3d);
    }

    private TextBlock dominantRun(ParagraphModel line) {
        return line.runs().stream().max(Comparator.comparingInt(run -> run.text().length())).orElseThrow();
    }

    private double baseline(ParagraphModel line) { return dominantRun(line).baselineY(); }
    private String text(ParagraphModel line) {
        StringBuilder value = new StringBuilder();
        TextBlock previous = null;
        for (TextBlock run : line.runs().stream().sorted(Comparator.comparingDouble(r -> r.box().x())).toList()) {
            if (previous != null && run.box().x() - previous.box().right() > 0.5d) value.append(' ');
            value.append(run.text());
            previous = run;
        }
        return value.toString().strip();
    }
    private boolean startsList(ParagraphModel line) { return LIST_START.matcher(text(line)).matches(); }
}
