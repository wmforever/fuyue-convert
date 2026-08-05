package cn.tensafe.ofd2word.docx;

import cn.tensafe.ofd2word.model.DocumentModel;

import java.nio.file.Path;

public interface DocxRenderer {
    void render(DocumentModel document, Path output) throws DocxRenderException;
}

