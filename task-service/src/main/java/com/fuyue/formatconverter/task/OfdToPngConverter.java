package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;

public final class OfdToPngConverter extends OfdToImageConverter {
    public OfdToPngConverter() { this(new SafeOfdExtractor(), new OfdrwParser()); }

    public OfdToPngConverter(SafeOfdExtractor extractor, OfdParser parser) {
        super(DocumentFormat.PNG, "png", "将 OFD 按固定版式逐页渲染为 160 DPI PNG；多页自动打包 ZIP。",
                extractor, parser);
    }
}
