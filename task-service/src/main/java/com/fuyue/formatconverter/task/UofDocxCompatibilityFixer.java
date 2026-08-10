package com.fuyue.formatconverter.task;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class UofDocxCompatibilityFixer {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final String NUMBERING_XML = "word/numbering.xml";

    private UofDocxCompatibilityFixer() { }

    static int repairContinuedLists(Path docx) throws IOException {
        byte[] documentBytes;
        byte[] numberingBytes;
        try (ZipFile archive = new ZipFile(docx.toFile())) {
            ZipEntry documentEntry = archive.getEntry(DOCUMENT_XML);
            ZipEntry numberingEntry = archive.getEntry(NUMBERING_XML);
            if (documentEntry == null || numberingEntry == null) return 0;
            try (var input = archive.getInputStream(documentEntry)) {
                documentBytes = input.readAllBytes();
            }
            try (var input = archive.getInputStream(numberingEntry)) {
                numberingBytes = input.readAllBytes();
            }
        }

        Document document = parse(documentBytes);
        NumberingDefinitions numbering = readNumbering(parse(numberingBytes));
        int repairs = repairParagraphs(document, numbering);
        if (repairs == 0) return 0;

        rewriteDocumentEntry(docx, serialize(document));
        return repairs;
    }

    private static int repairParagraphs(Document document, NumberingDefinitions numbering) {
        Set<String> seenIds = new HashSet<>();
        ParagraphNumber previous = null;
        int repairs = 0;
        NodeList paragraphs = document.getElementsByTagNameNS(WORD_NS, "p");
        for (int index = 0; index < paragraphs.getLength(); index++) {
            ParagraphNumber current = paragraphNumber((Element) paragraphs.item(index), numbering, seenIds);
            if (current == null) {
                previous = null;
                continue;
            }

            boolean continuedRestart = previous != null
                    && previous.restartRun()
                    && previous.level().equals(current.level())
                    && !previous.numId().equals(current.numId())
                    && current.seenEarlier()
                    && previous.signature().equals(current.signature());
            if (continuedRestart) {
                current.numIdElement().setAttributeNS(WORD_NS, "w:val", previous.numId());
                current = current.repairedAs(previous.numId());
                repairs++;
            }
            previous = current;
        }
        return repairs;
    }

    private static ParagraphNumber paragraphNumber(Element paragraph, NumberingDefinitions numbering,
                                                    Set<String> seenIds) {
        Element properties = directChild(paragraph, "pPr");
        Element numberProperties = properties == null ? null : directChild(properties, "numPr");
        Element numIdElement = numberProperties == null ? null : directChild(numberProperties, "numId");
        if (numIdElement == null) return null;
        String numId = value(numIdElement);
        Element levelElement = directChild(numberProperties, "ilvl");
        String level = levelElement == null || value(levelElement).isBlank() ? "0" : value(levelElement);
        String signature = numbering.signature(numId, level);
        if (numId.isBlank() || signature == null) return null;

        boolean seenEarlier = !seenIds.add(numId);
        boolean restartRun = !seenEarlier && numbering.restartsAtOne(numId, level);
        return new ParagraphNumber(numIdElement, numId, level, signature, seenEarlier, restartRun);
    }

    private static NumberingDefinitions readNumbering(Document numberingDocument) throws IOException {
        Map<String, String> numToAbstract = new HashMap<>();
        Set<String> restartOverrides = new HashSet<>();
        NodeList nums = numberingDocument.getElementsByTagNameNS(WORD_NS, "num");
        for (int index = 0; index < nums.getLength(); index++) {
            Element num = (Element) nums.item(index);
            String numId = value(num, "numId");
            Element abstractId = directChild(num, "abstractNumId");
            if (!numId.isBlank() && abstractId != null) numToAbstract.put(numId, value(abstractId));
            for (Element override : directChildren(num, "lvlOverride")) {
                Element start = directChild(override, "startOverride");
                if (start != null && "1".equals(value(start))) {
                    restartOverrides.add(key(numId, value(override, "ilvl")));
                }
            }
        }

        Map<String, String> signatures = new HashMap<>();
        NodeList abstracts = numberingDocument.getElementsByTagNameNS(WORD_NS, "abstractNum");
        for (int index = 0; index < abstracts.getLength(); index++) {
            Element abstractNum = (Element) abstracts.item(index);
            String abstractId = value(abstractNum, "abstractNumId");
            for (Element level : directChildren(abstractNum, "lvl")) {
                signatures.put(key(abstractId, value(level, "ilvl")), elementSignature(level));
            }
        }
        return new NumberingDefinitions(numToAbstract, restartOverrides, signatures);
    }

    private static String elementSignature(Element element) throws IOException {
        try {
            var transformer = secureTransformerFactory().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(element), new StreamResult(output));
            return output.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll(">\\s+<", "><").trim();
        } catch (TransformerException e) {
            throw new IOException("无法读取 DOCX 编号定义", e);
        }
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("无法解析 LibreOffice 生成的 DOCX XML", e);
        }
    }

    private static byte[] serialize(Document document) throws IOException {
        try {
            var transformer = secureTransformerFactory().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (TransformerException e) {
            throw new IOException("无法写回修复后的 DOCX XML", e);
        }
    }

    private static TransformerFactory secureTransformerFactory() {
        TransformerFactory factory = TransformerFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException | TransformerException ignored) {
            // JDK 默认实现支持这些限制；替代实现不支持时仍不解析外部输入。
        }
        return factory;
    }

    private static void rewriteDocumentEntry(Path docx, byte[] updatedDocument) throws IOException {
        Path parent = docx.toAbsolutePath().normalize().getParent();
        Path temporary = Files.createTempFile(parent, docx.getFileName().toString(), ".repairing");
        try {
            try (ZipInputStream input = new ZipInputStream(Files.newInputStream(docx));
                 ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    ZipEntry replacement = new ZipEntry(entry.getName());
                    if (entry.getTime() >= 0) replacement.setTime(entry.getTime());
                    output.putNextEntry(replacement);
                    if (DOCUMENT_XML.equals(entry.getName())) output.write(updatedDocument);
                    else input.transferTo(output);
                    output.closeEntry();
                    input.closeEntry();
                }
            }
            try {
                Files.move(temporary, docx, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, docx, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Element directChild(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && WORD_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private static java.util.List<Element> directChildren(Element parent, String localName) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && WORD_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private static String value(Element element) { return value(element, "val"); }

    private static String value(Element element, String attribute) {
        String namespaced = element.getAttributeNS(WORD_NS, attribute);
        return namespaced.isBlank() ? element.getAttribute("w:" + attribute) : namespaced;
    }

    private static String key(String left, String right) { return left + ':' + right; }

    private record ParagraphNumber(Element numIdElement, String numId, String level, String signature,
                                   boolean seenEarlier, boolean restartRun) {
        ParagraphNumber repairedAs(String repairedNumId) {
            return new ParagraphNumber(numIdElement, repairedNumId, level, signature, false, true);
        }
    }

    private record NumberingDefinitions(Map<String, String> numToAbstract, Set<String> restartOverrides,
                                        Map<String, String> signatures) {
        boolean restartsAtOne(String numId, String level) {
            return restartOverrides.contains(key(numId, level));
        }

        String signature(String numId, String level) {
            String abstractId = numToAbstract.get(numId);
            return abstractId == null ? null : signatures.get(key(abstractId, level));
        }
    }
}
