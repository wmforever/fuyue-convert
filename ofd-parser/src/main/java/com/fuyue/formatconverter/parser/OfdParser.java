package com.fuyue.formatconverter.parser;

import com.fuyue.formatconverter.model.DocumentModel;

public interface OfdParser {
    /** Parse every page in source order. Implementations must never silently truncate pages. */
    DocumentModel parse(SafeOfdPackage source, String displayName, ParseLimits limits)
            throws OfdParseException;
    String name();
}
