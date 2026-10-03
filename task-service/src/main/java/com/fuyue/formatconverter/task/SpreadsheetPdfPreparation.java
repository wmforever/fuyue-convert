package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.*;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;

/** Changes print settings in a private copy, retaining sheet dependencies and opaque Office parts. */
final class SpreadsheetPdfPreparation {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Set<String> AFTER_PAGE_SETUP = Set.of("headerFooter", "rowBreaks", "colBreaks",
            "customProperties", "cellWatches", "ignoredErrors", "smartTags", "drawing", "legacyDrawing",
            "legacyDrawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts", "extLst");

    static List<Integer> select(List<Boolean> visible, ConversionOptions options) throws ConversionFailureException {
        List<Integer> numbers = options.spreadsheetSheetNumbers(visible.size());
        List<Integer> selected = new ArrayList<>();
        for (int number : numbers) {
            if (visible.get(number - 1)) selected.add(number - 1);
            else if (!"all".equals(options.spreadsheetSheets())) {
                throw new ConversionFailureException("SPREADSHEET_SHEET_HIDDEN",
                        "第 " + number + " 张工作表已隐藏；请先在表格软件中取消隐藏后再导出。");
            }
        }
        if (selected.isEmpty()) throw new ConversionFailureException("SPREADSHEET_NO_VISIBLE_SHEETS", "工作簿没有可导出的可见工作表。");
        return List.copyOf(selected);
    }

    static Path prepare(Path source, Path workDir, ConversionOptions options, ParseLimits limits) throws Exception {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            byte[] workbookSource = readPart(zip, "xl/workbook.xml", limits);
            Document workbook = parse(workbookSource);
            String workbookNs = workbook.getDocumentElement().getNamespaceURI();
            if (!Set.of(NS, "http://purl.oclc.org/ooxml/spreadsheetml/main").contains(workbookNs)) throw new IOException("工作簿命名空间无效");
            var sheetNodes = workbook.getElementsByTagNameNS(workbookNs, "sheet");
            List<Element> sheets = new ArrayList<>();
            List<Boolean> visible = new ArrayList<>();
            for (int i = 0; i < sheetNodes.getLength(); i++) {
                Element sheet = (Element) sheetNodes.item(i);
                sheets.add(sheet);
                visible.add(!Set.of("hidden", "veryHidden").contains(sheet.getAttribute("state")));
            }
            Set<Integer> selected = new HashSet<>(select(visible, options));
            if ("all".equals(options.spreadsheetSheets()) && !options.spreadsheetFitWidth()) return source;
            for (int i = 0; i < sheets.size(); i++) {
                // Keep unselected sheets in the package so formulas and named ranges can still resolve them.
                if (visible.get(i) && !selected.contains(i)) sheets.get(i).setAttribute("state", "hidden");
            }
            var views = workbook.getElementsByTagNameNS(workbookNs, "workbookView");
            int first = Collections.min(selected);
            for (int i = 0; i < views.getLength(); i++) {
                ((Element) views.item(i)).setAttribute("activeTab", Integer.toString(first));
                ((Element) views.item(i)).setAttribute("firstSheet", Integer.toString(first));
            }
            Set<String> fitParts = new HashSet<>();
            if (options.spreadsheetFitWidth()) {
                Document relationships = parse(readPart(zip, "xl/_rels/workbook.xml.rels", limits));
                Map<String, String> targets = new HashMap<>();
                var relations = relationships.getDocumentElement().getChildNodes();
                for (int i = 0; i < relations.getLength(); i++) {
                    if (!(relations.item(i) instanceof Element relation)) continue;
                    if (!relation.getAttribute("Type").endsWith("/worksheet")) continue;
                    if ("External".equals(relation.getAttribute("TargetMode"))) throw new IOException("工作表关系不能指向外部文件");
                    String target = relation.getAttribute("Target");
                    String part = (target.startsWith("/") ? Path.of(target.substring(1)) : Path.of("xl").resolve(target))
                            .normalize().toString().replace('\\', '/');
                    if (!part.startsWith("xl/") || part.contains("../")) throw new IOException("工作表关系路径无效");
                    targets.put(relation.getAttribute("Id"), part);
                }
                for (int index : selected) {
                    String part = targets.get(relationshipId(sheets.get(index)));
                    // Chart sheets have no worksheet print settings; retain their native export behavior.
                    if (part != null) {
                        if (zip.getEntry(part) == null) throw new IOException("工作表内容缺失");
                        fitParts.add(part);
                    }
                }
            }
            byte[] workbookBytes = serialize(workbook);
            Files.createDirectories(workDir);
            Path destination = workDir.resolve("spreadsheet-print.xlsx");
            long expanded = 0, actualExpanded = 0;
            int entries = 0;
            Set<String> names = new HashSet<>();
            try (var output = new ZipOutputStream(Files.newOutputStream(destination))) {
                var iterator = zip.entries();
                while (iterator.hasMoreElements()) {
                    ZipEntry entry = iterator.nextElement();
                    if (++entries > limits.maxEntries() || !names.add(entry.getName())) throw new IOException("工作簿 ZIP 条目数量或重复名称无效");
                    if (entry.getSize() < 0 || entry.getSize() > limits.maxEntryBytes()) throw new IOException("工作簿条目超过展开大小限制");
                    expanded += entry.getSize();
                    if (expanded > limits.maxExpandedBytes()) throw new IOException("工作簿总展开大小超过限制");
                    ZipEntry copy = new ZipEntry(entry.getName());
                    if (entry.getTime() >= 0) copy.setTime(entry.getTime());
                    output.putNextEntry(copy);
                    if (entry.getName().equals("xl/workbook.xml")) {
                        output.write(workbookBytes);
                        actualExpanded += workbookSource.length;
                    } else {
                        try (var input = bounded(zip.getInputStream(entry), Math.min(limits.maxEntryBytes(), limits.maxExpandedBytes() - actualExpanded))) {
                            if (fitParts.contains(entry.getName())) fitWorksheet(input, output);
                            else input.transferTo(output);
                            actualExpanded += input.bytesRead;
                        }
                    }
                    if (actualExpanded > limits.maxExpandedBytes()) throw new IOException("工作簿总展开大小超过限制");
                    output.closeEntry();
                }
            } catch (Exception error) {
                Files.deleteIfExists(destination);
                throw error;
            }
            ConversionGuards.requireNonEmptyOutputFile(destination, limits, "工作表打印设置");
            return destination;
        }
    }

    private static String relationshipId(Element sheet) {
        String id = sheet.getAttributeNS(REL_NS, "id");
        return id.isEmpty() ? sheet.getAttributeNS("http://purl.oclc.org/ooxml/officeDocument/relationships", "id") : id;
    }

    private static byte[] readPart(ZipFile zip, String name, ParseLimits limits) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("工作簿内容缺失：" + name);
        try (var input = bounded(zip.getInputStream(entry), Math.min(limits.maxEntryBytes(), 2L * 1024 * 1024))) {
            return input.readAllBytes();
        }
    }

    private static BoundedInput bounded(InputStream source, long limit) {
        return new BoundedInput(source, limit);
    }

    private static final class BoundedInput extends FilterInputStream {
        private final long limit;
        private long bytesRead;
        private BoundedInput(InputStream source, long limit) { super(source); this.limit = limit; }
        private void count(long length) throws IOException {
            if (length > 0 && (bytesRead += length) > limit) throw new IOException("工作簿条目超过展开大小限制");
        }
        @Override public int read() throws IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int value = in.read(bytes, offset, length); count(value); return value;
        }
    }

    private static Document parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static byte[] serialize(Document document) throws Exception {
        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var output = new ByteArrayOutputStream();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private static void printElement(XMLStreamWriter writer, String prefix, String namespace, String name) throws XMLStreamException {
        writer.writeStartElement(prefix, name, namespace);
        if (name.equals("pageSetUpPr")) writer.writeAttribute("fitToPage", "1");
        else { writer.writeAttribute("fitToWidth", "1"); writer.writeAttribute("fitToHeight", "0"); }
        writer.writeEndElement();
    }

    /** Stream large cell XML without loading or rewriting cells through POI. */
    private static void fitWorksheet(InputStream input, OutputStream output) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var reader = factory.createXMLStreamReader(input);
        var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
        int depth = 0;
        boolean sheetPr = false, setupPr = false, pageSetup = false;
        String prefix = "", worksheetNs = NS;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (depth == 0) {
                        prefix = reader.getPrefix() == null ? "" : reader.getPrefix();
                        worksheetNs = reader.getNamespaceURI();
                    }
                    boolean sheetElement = worksheetNs.equals(reader.getNamespaceURI());
                    if (depth == 1 && sheetElement) {
                        if (name.equals("sheetPr")) sheetPr = true;
                        else if (!sheetPr) {
                            writer.writeStartElement(prefix, "sheetPr", worksheetNs);
                            printElement(writer, prefix, worksheetNs, "pageSetUpPr"); writer.writeEndElement(); sheetPr = true; setupPr = true;
                        }
                        if (name.equals("pageSetup")) pageSetup = true;
                        else if (!pageSetup && AFTER_PAGE_SETUP.contains(name)) {
                            printElement(writer, prefix, worksheetNs, "pageSetup"); pageSetup = true;
                        }
                    }
                    boolean fitPr = depth == 2 && sheetElement && name.equals("pageSetUpPr");
                    boolean fitSetup = depth == 1 && sheetElement && name.equals("pageSetup");
                    if (fitPr) setupPr = true;
                    writer.writeStartElement(reader.getPrefix() == null ? "" : reader.getPrefix(), name,
                            reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI());
                    for (int i = 0; i < reader.getNamespaceCount(); i++) {
                        String nsPrefix = reader.getNamespacePrefix(i);
                        if (nsPrefix == null) writer.writeDefaultNamespace(reader.getNamespaceURI(i));
                        else writer.writeNamespace(nsPrefix, reader.getNamespaceURI(i));
                    }
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        String attr = reader.getAttributeLocalName(i);
                        String ns = reader.getAttributeNamespace(i);
                        if ((ns == null || ns.isEmpty()) && ((fitPr && attr.equals("fitToPage"))
                                || (fitSetup && Set.of("fitToWidth", "fitToHeight", "scale").contains(attr)))) continue;
                        if (ns == null || ns.isEmpty()) writer.writeAttribute(attr, reader.getAttributeValue(i));
                        else writer.writeAttribute(reader.getAttributePrefix(i), ns, attr, reader.getAttributeValue(i));
                    }
                    if (fitPr) writer.writeAttribute("fitToPage", "1");
                    if (fitSetup) { writer.writeAttribute("fitToWidth", "1"); writer.writeAttribute("fitToHeight", "0"); }
                    depth++;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 2 && worksheetNs.equals(reader.getNamespaceURI()) && reader.getLocalName().equals("sheetPr") && !setupPr) {
                        printElement(writer, prefix, worksheetNs, "pageSetUpPr"); setupPr = true;
                    }
                    if (depth == 1 && !sheetPr) {
                        writer.writeStartElement(prefix, "sheetPr", worksheetNs);
                        printElement(writer, prefix, worksheetNs, "pageSetUpPr"); writer.writeEndElement();
                    }
                    if (depth == 1 && !pageSetup) printElement(writer, prefix, worksheetNs, "pageSetup");
                    writer.writeEndElement(); depth--;
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.SPACE) {
                    writer.writeCharacters(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                } else if (event == XMLStreamConstants.CDATA) writer.writeCData(reader.getText());
                else if (event == XMLStreamConstants.COMMENT) writer.writeComment(reader.getText());
                else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) writer.writeProcessingInstruction(reader.getPITarget(), reader.getPIData());
                else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw new XMLStreamException("工作表不能包含 DTD 或实体引用");
            }
            writer.flush();
        } finally { reader.close(); }
    }
}
