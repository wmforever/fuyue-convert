package cn.tensafe.ofd2word.parser;

/**
 * Extension point for an optional on-premises OCR engine. Implementations must not
 * send page data outside the deployment boundary.
 */
public interface OcrEngine {
    String name();

    boolean available();

    OcrResult recognize(OcrRequest request) throws OcrException;
}
