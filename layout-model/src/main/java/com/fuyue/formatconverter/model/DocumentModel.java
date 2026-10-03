package com.fuyue.formatconverter.model;

import java.util.List;

public record DocumentModel(String sourceName, String parserName, int sourcePageCount,
                            List<PageModel> pages, List<ConversionWarning> warnings,
                            ContinuousFlow continuousFlow) {
    /** Only verified uniform prose may flow naturally across source page boundaries. */
    public record ContinuousFlow(double topMarginMm, double bottomMarginMm) {
        public ContinuousFlow {
            if (!Double.isFinite(topMarginMm) || !Double.isFinite(bottomMarginMm)
                    || topMarginMm < 0 || bottomMarginMm < 0) {
                throw new IllegalArgumentException("Invalid continuous prose margins");
            }
        }
    }

    public DocumentModel(String sourceName, String parserName, int sourcePageCount,
                         List<PageModel> pages, List<ConversionWarning> warnings) {
        this(sourceName, parserName, sourcePageCount, pages, warnings, null);
    }
    public DocumentModel {
        sourceName = sourceName == null ? "document.ofd" : sourceName;
        parserName = parserName == null ? "unknown" : parserName;
        pages = pages == null ? List.of() : List.copyOf(pages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
