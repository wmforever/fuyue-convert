package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.DocumentModel;

import java.nio.file.Path;

public interface DocxRenderer {
    void render(DocumentModel document, Path output) throws DocxRenderException;
}

