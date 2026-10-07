package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.util.*;

/** Append-only recovery: source lines are immutable, and ambiguous geometry rejects the page. */
final class OcrPartialRecovery {
    private OcrPartialRecovery() { }

    static TesseractOcrConverter.RecognitionResult select(TesseractOcrConverter.RecognitionResult original,
            TesseractOcrConverter.RecognitionResult candidate, Rect physical,
            double minimumConfidence, double minimumGain, boolean enhanced, double degrees, long deadline) {
        if (System.nanoTime() >= deadline || original.blocks().isEmpty() || candidate.blocks().isEmpty()
                || original.partialRecovery() || original.conflicts().size() >= 32
                || original.blocks().size() > 500 || candidate.blocks().size() > 500
                || original.wordCount() > 5000 || candidate.wordCount() > 5000
                || (long) original.wordCount() * candidate.wordCount() > 2_000_000
                || candidate.confidence() < Math.max(minimumConfidence, original.confidence() + minimumGain)
                || original.blocks().stream().noneMatch(b -> b.text().codePoints().anyMatch(Character::isDigit))) return original;
        List<Rect> boxes = new ArrayList<>();
        List<TextBlock.OcrWord> words = new ArrayList<>();
        for (var block : candidate.blocks()) {
            if (System.nanoTime() >= deadline || block.text().isBlank() || block.ocrWords().isEmpty()) return original;
            Rect box = block.box();
            for (var word : block.ocrWords()) {
                if (words.size() >= 5000 || System.nanoTime() >= deadline) return original;
                box = box.union(word.box()); words.add(word);
            }
            if (!inside(box, physical) || box.height() <= 0 || box.width() <= 0) return original;
            boxes.add(box);
        }
        if (words.size() > 5000) return original;
        double[] heights = boxes.stream().mapToDouble(Rect::height).sorted().toArray();
        double height = heights[heights.length / 2], margin = height * .25;
        // A global column gutter or overlapping/spanning candidate lines cannot establish new independent rows.
        for (var block : candidate.blocks()) {
            var row = new ArrayList<>(block.ocrWords());
            row.sort(Comparator.comparingDouble(w -> w.box().x()));
            double rowRight = row.get(0).box().right();
            for (var word : row) {
                if (word.box().x() - rowRight > height * 4 || System.nanoTime() >= deadline) return original;
                rowRight = Math.max(rowRight, word.box().right());
            }
        }
        words.sort(Comparator.comparingDouble(w -> w.box().x()));
        double right = words.get(0).box().right();
        for (var word : words) {
            if (word.box().x() - right > height * 4 || System.nanoTime() >= deadline) return original;
            right = Math.max(right, word.box().right());
        }
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).height() > height * 2) return original;
            if (i > 0 && boxes.get(i).y() - boxes.get(i - 1).bottom() < margin) return original;
        }
        Map<Integer, TextBlock> anchors = new HashMap<>();
        List<Rect> sourceBoxes = new ArrayList<>();
        int previousAnchor = -1;
        int sourceWords = 0;
        int previousZ = Integer.MIN_VALUE;
        for (var block : original.blocks()) {
            if (System.nanoTime() >= deadline || block.text().isBlank() || block.ocrWords().isEmpty()) return original;
            Rect source = block.box();
            for (var word : block.ocrWords()) {
                if (++sourceWords > 5000 || System.nanoTime() >= deadline) return original;
                source = source.union(word.box());
            }
            if (!inside(source, physical) || source.height() > height * 2) return original;
            int anchor = -1;
            for (int i = 0; i < boxes.size(); i++) {
                Rect next = boxes.get(i);
                if (source.intersectionArea(next) > 0) {
                    if (anchor >= 0) return original;
                    anchor = i;
                }
            }
            if (anchor <= previousAnchor || anchors.containsKey(anchor) || block.zOrder() < previousZ) return original;
            previousZ = block.zOrder();
            anchors.put(anchor, block); previousAnchor = anchor; sourceBoxes.add(source);
        }
        List<TextBlock> merged = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        original.blocks().forEach(b -> ids.add(b.id()));
        int recovered = 0;
        int recoveredZ = original.blocks().get(0).zOrder();
        for (int i = 0; i < boxes.size(); i++) {
            if (System.nanoTime() >= deadline) return original;
            if (anchors.containsKey(i)) {
                merged.add(anchors.get(i)); recoveredZ = anchors.get(i).zOrder(); continue;
            }
            Rect box = boxes.get(i);
            // Vertical separation forbids side-by-side cells or words from extending numeric context.
            for (var source : sourceBoxes) {
                if (!(box.bottom() + margin < source.y() || source.bottom() + margin < box.y())) return original;
            }
            var block = candidate.blocks().get(i);
            if (block.text().codePoints().filter(Character::isLetter).count() < 6
                    || block.ocrWords().stream().anyMatch(w -> w.confidence() < minimumConfidence)
                    || block.ocrWords().stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0)
                            < Math.max(minimumConfidence, original.confidence() + minimumGain)) return original;
            String id = "ocr-partial-p" + block.pageNumber() + "-l" + i;
            while (!ids.add(id)) {
                if (System.nanoTime() >= deadline) return original;
                id = "recovered-" + id;
            }
            merged.add(new TextBlock(id, block.pageNumber(), block.box(), block.text(), block.baselineY(),
                    block.style(), recoveredZ, block.textOffsetXmm(), block.textOffsetYmm(),
                    block.advancesMm(), block.transform(), block.ocrWords()));
            recovered++;
        }
        if (recovered == 0 || merged.size() > 500 || merged.size() != original.blocks().size() + recovered) return original;
        var mergedWords = merged.stream().flatMap(b -> b.ocrWords().stream()).toList();
        if (mergedWords.size() > 5000) return original;
        double confidence = mergedWords.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0);
        // The candidate and every added line pass the unchanged quality gain gate.
        // Preserving all source words dilutes that gain; report their honest mixed confidence.
        if (confidence < Math.max(minimumConfidence, original.confidence() + Math.min(0, minimumGain))
                || System.nanoTime() >= deadline) return original;
        List<String> conflicts = new ArrayList<>(original.conflicts());
        conflicts.add("保留全部原识别行、原词及原坐标，仅补充严格纵向分离的新行；原数字及遗漏仍需人工复核。");
        return new TesseractOcrConverter.RecognitionResult(List.copyOf(merged), confidence, mergedWords.size(),
                enhanced || original.imageEnhanced(), degrees, List.copyOf(conflicts), true, true);
    }

    private static boolean inside(Rect box, Rect physical) {
        return physical.contains(new Point(box.x(), box.y()), .01)
                && physical.contains(new Point(box.right(), box.bottom()), .01);
    }
}
