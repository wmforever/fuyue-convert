package cn.tensafe.ofd2word.parser;

import cn.tensafe.ofd2word.model.ConversionWarning;
import cn.tensafe.ofd2word.model.TextBlock;

import java.util.List;

public record OcrResult(List<TextBlock> textBlocks, double confidence,
                        List<ConversionWarning> warnings) {
    public OcrResult {
        textBlocks = textBlocks == null ? List.of() : List.copyOf(textBlocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
