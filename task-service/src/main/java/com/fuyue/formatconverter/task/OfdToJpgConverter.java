package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;

import java.nio.file.Path;

public final class OfdToJpgConverter extends OfdToImageConverter {
    public OfdToJpgConverter() { this(new SafeOfdExtractor(), new OfdrwParser()); }

    public OfdToJpgConverter(SafeOfdExtractor extractor, OfdParser parser) {
        super(DocumentFormat.JPG, "jpeg", "将 OFD 按固定版式逐页渲染为 160 DPI JPEG；多页自动打包 ZIP。",
                extractor, parser);
    }

    OfdToJpgConverter(SafeOfdExtractor extractor, OfdParser parser, Path popplerBinary) {
        super(DocumentFormat.JPG, "jpeg", "将 OFD 按固定版式逐页渲染为 160 DPI JPEG；多页自动打包 ZIP。",
                extractor, parser, popplerBinary);
    }
}
