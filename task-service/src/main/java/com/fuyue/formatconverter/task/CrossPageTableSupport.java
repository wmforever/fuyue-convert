package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.TextBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conservative cross-page table matching shared by PDF and OFD exports.
 *
 * <p>A summary is only produced when the recognizer found exactly one table
 * on adjacent pages, the grids have the same shape, and both tables touch a
 * page edge. This deliberately leaves ambiguous layouts in their original
 * page sheets instead of guessing that unrelated tables belong together.</p>
 */
final class CrossPageTableSupport {
    private CrossPageTableSupport() { }

    static List<TableChain> chains(List<PageModel> pages) {
        List<TableChain> result = new ArrayList<>();
        int index = 0;
        while (index < pages.size()) {
            if (index + 1 >= pages.size()) {
                index++;
                continue;
            }
            PageModel firstPage = pages.get(index);
            if (firstPage.tables().size() != 1) {
                index++;
                continue;
            }
            List<TableSegment> segments = new ArrayList<>();
            segments.add(new TableSegment(firstPage, firstPage.tables().get(0), false));
            int next = index;
            while (next + 1 < pages.size()) {
                Continuation continuation = continuation(pages.get(next), pages.get(next + 1));
                if (continuation == null) break;
                PageModel page = pages.get(next + 1);
                segments.add(new TableSegment(page, page.tables().get(0), continuation.repeatedHeader()));
                next++;
            }
            if (segments.size() > 1) {
                result.add(new TableChain(List.copyOf(segments)));
                index = next + 1;
            } else {
                index++;
            }
        }
        return List.copyOf(result);
    }

    static List<Continuation> continuations(List<PageModel> pages) {
        List<Continuation> result = new ArrayList<>();
        for (int i = 0; i + 1 < pages.size(); i++) {
            Continuation continuation = continuation(pages.get(i), pages.get(i + 1));
            if (continuation != null) result.add(continuation);
        }
        return List.copyOf(result);
    }

    private static Continuation continuation(PageModel from, PageModel to) {
        if (to.pageNumber() != from.pageNumber() + 1
                || from.tables().size() != 1 || to.tables().size() != 1) return null;
        TableModel first = from.tables().get(0);
        TableModel second = to.tables().get(0);
        if (!matchingColumns(first, second)
                || !nearPageBoundary(from, first, false)
                || !nearPageBoundary(to, second, true)) return null;
        return new Continuation(from.pageNumber(), to.pageNumber(), sameFirstRow(first, second));
    }

    private static boolean matchingColumns(TableModel first, TableModel second) {
        if (first.columnCount() < 1 || first.columnCount() != second.columnCount()) return false;
        double firstWidth = Math.max(1d, first.box().width());
        double secondWidth = Math.max(1d, second.box().width());
        if (relativeDifference(firstWidth, secondWidth) > 0.08d) return false;
        for (int column = 0; column < first.columnCount(); column++) {
            double a = first.xGrid().get(column + 1) - first.xGrid().get(column);
            double b = second.xGrid().get(column + 1) - second.xGrid().get(column);
            if (relativeDifference(a, b) > 0.12d) return false;
        }
        return true;
    }

    private static boolean nearPageBoundary(PageModel page, TableModel table, boolean top) {
        double pageHeight = Math.max(1d, page.physicalBox().height());
        double gap = top ? table.box().y() - page.physicalBox().y()
                : page.physicalBox().bottom() - table.box().bottom();
        return gap >= -1d && gap <= pageHeight * 0.25d;
    }

    private static double relativeDifference(double first, double second) {
        return Math.abs(first - second) / Math.max(1d, Math.max(Math.abs(first), Math.abs(second)));
    }

    private static boolean sameFirstRow(TableModel first, TableModel second) {
        List<String> left = first.cells().stream().filter(cell -> cell.row() == 0)
                .sorted(Comparator.comparingInt(CellModel::column)).map(CrossPageTableSupport::cellText).toList();
        List<String> right = second.cells().stream().filter(cell -> cell.row() == 0)
                .sorted(Comparator.comparingInt(CellModel::column)).map(CrossPageTableSupport::cellText).toList();
        return !left.isEmpty() && left.equals(right);
    }

    private static String cellText(CellModel cell) {
        String value = cell.paragraphs().stream().flatMap(paragraph -> paragraph.runs().stream())
                .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(block -> block.box().x()))
                .map(TextBlock::text).reduce("", String::concat).strip();
        return SpreadsheetValueParser.normalizeCellText(value);
    }

    record Continuation(int fromPage, int toPage, boolean repeatedHeader) { }
    record TableSegment(PageModel page, TableModel table, boolean repeatedHeader) { }
    record TableChain(List<TableSegment> segments) {
        TableChain { segments = List.copyOf(segments); }
        int firstPage() { return segments.get(0).page().pageNumber(); }
        int lastPage() { return segments.get(segments.size() - 1).page().pageNumber(); }
    }
}
