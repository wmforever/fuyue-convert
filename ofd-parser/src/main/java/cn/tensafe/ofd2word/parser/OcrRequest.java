package cn.tensafe.ofd2word.parser;

import cn.tensafe.ofd2word.model.ImageBlock;
import cn.tensafe.ofd2word.model.Rect;

import java.util.List;

public record OcrRequest(int pageNumber, Rect pageBox, List<ImageBlock> pageImages,
                         String preferredLanguage) {
    public OcrRequest {
        if (pageNumber < 1) throw new IllegalArgumentException("Page number must be positive");
        pageImages = pageImages == null ? List.of() : List.copyOf(pageImages);
        preferredLanguage = preferredLanguage == null ? "zh-CN" : preferredLanguage;
    }
}
