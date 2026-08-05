package cn.tensafe.ofd2word.model;

import java.util.List;

public record DocumentModel(String sourceName, String parserName, int sourcePageCount,
                            List<PageModel> pages, List<ConversionWarning> warnings) {
    public DocumentModel {
        sourceName = sourceName == null ? "document.ofd" : sourceName;
        parserName = parserName == null ? "unknown" : parserName;
        pages = pages == null ? List.of() : List.copyOf(pages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

