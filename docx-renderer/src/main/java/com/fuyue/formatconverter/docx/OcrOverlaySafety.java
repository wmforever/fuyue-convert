package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Prepare scan-only pages for visible, editable overlays without damaging uncertain source regions. */
public final class OcrOverlaySafety {
    private OcrOverlaySafety() { }

    public static PageModel prepare(PageModel page) {
        if (page.images().size() != 1 || !page.lines().isEmpty() || !page.tables().isEmpty()
                || !page.paragraphs().isEmpty() || page.textBlocks().isEmpty()
                || page.textBlocks().stream().anyMatch(b -> b.ocrWords().isEmpty()
                        || !Transform2D.IDENTITY.equals(b.transform()))) return page;
        ImageBlock scan = page.images().get(0);
        if (!("OCR_PAGE_BACKGROUND".equals(scan.role()) || "OCR_SCAN_BACKGROUND".equals(scan.role()))) return page;
        List<TextBlock.OcrWord> all = page.textBlocks().stream().flatMap(b -> b.ocrWords().stream()).toList();
        // Bound pairwise collision analysis, just like other optional OCR recovery.
        if (all.size() > 1400) return retain(page, List.of(), all, "识别区域过密，无法安全检查覆盖关系");
        double[] heights = all.stream().filter(w -> w.confidence() >= .85d
                && w.text().codePoints().anyMatch(Character::isLetterOrDigit))
                .mapToDouble(w -> w.box().height()).sorted().toArray();
        double typicalHeight = heights.length < 20 ? Double.POSITIVE_INFINITY : heights[heights.length / 2];
        List<TextBlock> blocks = new ArrayList<>();
        List<TextBlock.OcrWord> rejected = new ArrayList<>();
        try (var sampler = OcrBackgroundMaskSampler.open(scan)) {
            if (sampler == null) return retain(page, List.of(), all, "无法验证扫描背景");
            for (var line : page.textBlocks()) {
                List<TextBlock.OcrWord> kept = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                int cursor = 0;
                for (var word : line.ocrWords()) {
                    int found = line.text().indexOf(word.text(), cursor);
                    int end = found < 0 ? cursor : found + word.text().length();
                    boolean separated = end < line.text().length() && Character.isWhitespace(line.text().charAt(end));
                    cursor = end;
                    boolean oversized = word.confidence() < .85d && word.box().height() > typicalHeight * 2d;
                    boolean collision = all.stream().anyMatch(other -> other != word
                            && (other.confidence() > word.confidence() || other.confidence() == word.confidence()
                                && other.box().width() * other.box().height() < word.box().width() * word.box().height())
                            && overlap(word.box(), other.box()) > .15d);
                    Rect mask = maskBox(word.box(), scan.box());
                    var fills = mask == null ? List.<OcrBackgroundMaskSampler.Fill>of() : sampler.fills(mask);
                    // Dark-paper masks are not reliably reopened by all supported
                    // Office versions. An unverified cover is not an editable copy.
                    boolean lightPaper = !fills.isEmpty() && fills.stream().allMatch(fill -> {
                        int rgb = Integer.parseInt(fill.color(), 16);
                        return ((rgb >> 16 & 255) * .299 + (rgb >> 8 & 255) * .587 + (rgb & 255) * .114) >= 110;
                    });
                    if (word.confidence() < .6d || oversized || collision || !lightPaper) continue;
                    kept.add(word); text.append(word.text()); if (separated) text.append(' ');
                }
                // Mixing new fonts with fragments of the old scan on one line
                // leaves inconsistent baselines and partial glyphs. Retain the
                // whole source line if any of its words cannot be replaced.
                if (kept.size() != line.ocrWords().size()) {
                    rejected.addAll(line.ocrWords());
                } else if (!kept.isEmpty()) {
                    Rect box = kept.stream().map(TextBlock.OcrWord::box).reduce(Rect::union).orElseThrow();
                    blocks.add(new TextBlock(line.id(), line.pageNumber(), box, text.toString().stripTrailing(),
                            line.baselineY(), line.style(), line.zOrder(), line.textOffsetXmm(), line.textOffsetYmm(),
                            line.advancesMm(), line.transform(), kept));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return retain(page, List.of(), all, "无法验证扫描背景");
        }
        return rejected.isEmpty() ? page : retain(page, blocks, rejected, "识别置信度不足、识别框异常或无法安全覆盖扫描文字");
    }

    private static double overlap(Rect a, Rect b) {
        return a.intersectionArea(b) / Math.max(.0001, Math.min(a.width() * a.height(), b.width() * b.height()));
    }

    private static Rect maskBox(Rect word, Rect scan) {
        double x = Math.max(scan.x(), word.x() - .15), y = Math.max(scan.y(), word.y() - .15);
        double right = Math.min(scan.right(), word.right() + .15), bottom = Math.min(scan.bottom(), word.bottom() + .15);
        return right > x && bottom > y ? new Rect(x, y, right - x, bottom - y) : null;
    }

    private static PageModel retain(PageModel page, List<TextBlock> blocks, List<TextBlock.OcrWord> rejected, String reason) {
        List<ConversionWarning> warnings = new ArrayList<>(page.warnings());
        String candidates = rejected.stream().limit(12).map(TextBlock.OcrWord::text)
                .reduce((a, b) -> a + " | " + b).orElse("");
        if (candidates.length() > 160) candidates = candidates.substring(0, 160) + "…";
        warnings.add(ConversionWarning.of(WarningCode.OCR_REGION_RETAINED_AS_IMAGE,
                reason + "；本页 " + rejected.size() + " 个 OCR 片段保留为原始扫描图，不叠加为可编辑文字。"
                        + (blocks.isEmpty() ? "本页未生成可编辑文字，全部保留为扫描图。" : "本页仅部分文字可编辑，请对照原图复核。")
                        + "未采用的识别候选：" + candidates, page.pageNumber()));
        return new PageModel(page.pageNumber(), page.physicalBox(), blocks, page.lines(), page.images(),
                page.paragraphs(), page.tables(), warnings);
    }
}
