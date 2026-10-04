package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.Rect;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import java.util.*;

/** Optional author-declared paragraph boundaries for TXT only; never infer missing tags. */
final class PdfTextSectionOrder {
    private final Map<COSDictionary, Integer> pages = new IdentityHashMap<>();
    private final Map<Integer, Map<Integer, Integer>> markedGroups = new HashMap<>();
    private final Map<String, Integer> blockGroups = new HashMap<>();
    private final Set<Integer> disabledPages = new HashSet<>();
    private final Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private COSDictionary roles;
    private int remaining;

    void initialize(PDDocument document, int maximumEntries) {
        remaining = Math.min(maximumEntries, 100_000);
        for (int i = 0; i < document.getNumberOfPages(); i++) pages.put(document.getPage(i).getCOSObject(), i + 1);
        try {
            COSBase base = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
            if (!(base instanceof COSDictionary root)) return;
            roles = dictionary(root.getDictionaryObject(COSName.ROLE_MAP));
            List<COSBase> roots = children(root.getDictionaryObject(COSName.K));
            if (roots.size() != 1 || !(roots.get(0) instanceof COSDictionary owner)
                    || !"Document".equals(role(owner))) return;
            int group = 0;
            for (COSBase child : children(owner.getDictionaryObject(COSName.K))) {
                if (!(child instanceof COSDictionary paragraph) || !"P".equals(role(paragraph))) throw new InvalidTags();
                visit(paragraph, dictionary(owner.getDictionaryObject(COSName.PG)), ++group, 0);
            }
        } catch (InvalidTags ignored) {
            markedGroups.clear();
        }
    }

    boolean enabled() { return !markedGroups.isEmpty(); }
    int group(int page, int mcid) { return markedGroups.getOrDefault(page, Map.of()).getOrDefault(mcid, -1); }
    void record(String blockId, int group) { if (group >= 0) blockGroups.put(blockId, group); }
    void disablePage(int page) { disabledPages.add(page); }

    String text(List<PageModel> source) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < source.size(); i++) {
            if (i > 0) result.append(System.lineSeparator()).append('\f').append(System.lineSeparator());
            PageModel page = source.get(i);
            if (disabledPages.contains(page.pageNumber()) || !page.tables().isEmpty() || !page.images().isEmpty()
                    || page.textBlocks().stream().anyMatch(block -> !blockGroups.containsKey(block.id())
                    || !block.ocrWords().isEmpty() || Math.abs(block.transform().rotationDegrees()) > 0.5
                    || block.transform().hasSkew(0.02))) {
                result.append(OfdToTextConverter.text(List.of(page)));
                continue;
            }
            Map<Integer, List<TextBlock>> groups = new TreeMap<>();
            for (TextBlock block : page.textBlocks()) groups.computeIfAbsent(blockGroups.get(block.id()),
                    ignored -> new ArrayList<>()).add(block);
            if (groups.size() < 2 || !separateMultilineSections(groups.values())) {
                result.append(OfdToTextConverter.text(List.of(page)));
                continue;
            }
            for (List<TextBlock> blocks : groups.values()) {
                var section = new PageModel(page.pageNumber(), page.physicalBox(), blocks,
                        List.of(), List.of(), List.of(), List.of(), List.of());
                result.append(OfdToTextConverter.text(List.of(section)));
            }
        }
        return result.toString();
    }

    private boolean separateMultilineSections(Collection<List<TextBlock>> groups) {
        // This path corrects independent vertical sections, not arbitrary tag
        // traversal or side-by-side paragraph intent. Preserve the old route
        // for single lines, overlapping sections and reversed geometry.
        double bottom = Double.NEGATIVE_INFINITY;
        for (List<TextBlock> blocks : groups) {
            Rect bounds = blocks.stream().map(TextBlock::box).reduce(Rect::union).orElseThrow();
            double lineHeight = blocks.stream().mapToDouble(b -> b.style().sizePt() * 25.4 / 72).max().orElse(0);
            if (bounds.y() < bottom || bounds.height() <= 2 * lineHeight) return false;
            bottom = bounds.bottom();
        }
        return true;
    }

    private void visit(COSBase value, COSDictionary inheritedPage, int group, int depth) {
        if (--remaining < 0 || depth > 64) throw new InvalidTags();
        if (value instanceof COSInteger id) {
            Integer page = pages.get(inheritedPage);
            if (page == null || id.longValue() < 0 || id.longValue() > Integer.MAX_VALUE
                    || markedGroups.computeIfAbsent(page, ignored -> new HashMap<>()).putIfAbsent(id.intValue(), group) != null)
                throw new InvalidTags();
        } else if (value instanceof COSDictionary node) {
            if (!visited.add(node) || node.containsKey(COSName.getPDFName("Stm"))) throw new InvalidTags();
            COSDictionary page = dictionary(node.getDictionaryObject(COSName.PG));
            if (page == null) page = inheritedPage;
            if ("MCR".equals(node.getNameAsString(COSName.TYPE))) {
                visit(node.getDictionaryObject(COSName.MCID), page, group, depth + 1);
            } else {
                if (!Set.of("P", "Span", "Div").contains(role(node))) throw new InvalidTags();
                for (COSBase child : children(node.getDictionaryObject(COSName.K))) visit(child, page, group, depth + 1);
            }
        } else throw new InvalidTags();
    }

    private List<COSBase> children(COSBase value) {
        if (value == null) return List.of();
        if (value instanceof COSArray array) {
            if (array.size() > remaining) throw new InvalidTags();
            List<COSBase> result = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) result.add(array.getObject(i));
            return result;
        }
        return List.of(value);
    }

    private String role(COSDictionary node) {
        String role = node.getNameAsString(COSName.S);
        COSBase mapped = role == null || roles == null ? null : roles.getDictionaryObject(COSName.getPDFName(role));
        return mapped instanceof COSName name ? name.getName() : role == null ? "" : role;
    }
    private static COSDictionary dictionary(COSBase value) { return value instanceof COSDictionary dict ? dict : null; }
    private static final class InvalidTags extends RuntimeException { }
}
