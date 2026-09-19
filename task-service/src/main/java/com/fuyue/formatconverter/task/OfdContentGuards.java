package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.WarningCode;

import java.util.List;

/** Strict completeness gates for OFD routes that promise editable or extracted content. */
final class OfdContentGuards {
    private OfdContentGuards() { }

    static void requireImagesExtracted(DocumentModel document) throws ConversionFailureException {
        List<Integer> failedPages = document.pages().stream()
                .filter(page -> page.warnings().stream()
                        .anyMatch(warning -> warning.code() == WarningCode.IMAGE_EXTRACTION_FAILED))
                .map(PageModel::pageNumber)
                .toList();
        if (!failedPages.isEmpty()) {
            throw new ConversionFailureException("OFD_IMAGE_EXTRACTION_FAILED",
                    "第 " + failedPages.stream().map(String::valueOf)
                            .reduce((left, right) -> left + "、" + right).orElse("")
                            + " 页存在无法提取的图片资源，拒绝生成可能缺失正文的结果");
        }
    }
}
