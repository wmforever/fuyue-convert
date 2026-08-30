package com.fuyue.formatconverter.parser;

import com.fuyue.formatconverter.model.*;
import org.ofdrw.core.basicStructure.pageObj.layer.CT_Layer;
import org.ofdrw.core.basicStructure.pageObj.layer.PageBlockType;
import org.ofdrw.core.basicStructure.pageObj.layer.block.CT_PageBlock;
import org.ofdrw.core.basicStructure.pageObj.layer.block.ImageObject;
import org.ofdrw.core.basicStructure.pageObj.layer.block.PathObject;
import org.ofdrw.core.basicStructure.pageObj.layer.block.TextObject;
import org.ofdrw.core.basicType.ST_Array;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.graph.pathObj.OptVal;
import org.ofdrw.core.pageDescription.drawParam.CT_DrawParam;
import org.ofdrw.core.pageDescription.color.color.CT_Color;
import org.ofdrw.core.text.TextCode;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.reader.PageInfo;
import org.ofdrw.reader.ResourceManage;
import org.ofdrw.reader.model.StampAnnotEntity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OfdrwParser implements OfdParser {
    private static final int MAX_PAGE_BLOCK_DEPTH = 128;
    private static final int MAX_PATH_OPERATIONS = 100_000;
    private static final int MAX_PATH_SEGMENTS = 100_000;
    private static final int QUADRATIC_SEGMENTS = 8;
    private static final int CUBIC_SEGMENTS = 12;

    @Override
    public DocumentModel parse(SafeOfdPackage source, String displayName, ParseLimits limits)
            throws OfdParseException {
        List<ConversionWarning> documentWarnings = new ArrayList<>();
        try (OFDReader reader = new OFDReader(source.root().toString(), false)) {
            int pageCount = reader.getNumberOfPages();
            if (pageCount < 1) throw new OfdParseException("OFD_NO_PAGES", "OFD 不包含页面");
            if (pageCount > limits.maxPages()) throw new OfdParseException("OFD_TOO_MANY_PAGES", "OFD 页数超过限制");
            ResourceManage resources = reader.getResMgt();
            Map<String, List<StampImage>> stampsByPage = readStampImages(reader, documentWarnings);
            List<PageModel> pages = new ArrayList<>(pageCount);
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                PageInfo info = reader.getPageInfo(pageNumber);
                ST_Box size = info.getSize();
                Rect pageBox = new Rect(size.getTopLeftX(), size.getTopLeftY(), size.getWidth(), size.getHeight());
                List<TextBlock> texts = new ArrayList<>();
                List<LineElement> lines = new ArrayList<>();
                List<ImageBlock> images = new ArrayList<>();
                List<ConversionWarning> pageWarnings = new ArrayList<>();
                ParseTraversal traversal = new ParseTraversal(limits.maxEntries());
                int z = 0;
                for (CT_Layer layer : info.getAllLayer()) {
                    z = parseBlocks(layer.getPageBlocks(), pageNumber, z, resources, texts, lines, images,
                            pageWarnings, traversal, 0);
                }
                String pageRef = info.getId() == null ? String.valueOf(reader.getPageObjectId(pageNumber)) : info.getId().toString();
                for (StampImage stamp : stampsByPage.getOrDefault(pageRef, List.of())) {
                    images.add(new ImageBlock(stamp.id(), pageNumber, stamp.box(), stamp.mimeType(),
                            stamp.data(), "SIGNATURE", z++));
                }
                if (requiresOcr(texts, images, pageBox)) {
                    pageWarnings.add(ConversionWarning.of(WarningCode.OCR_REQUIRED,
                            "页面包含无法可靠提取文字的图像内容，可能是扫描型或混合扫描型 OFD", pageNumber));
                }
                pages.add(new PageModel(pageNumber, pageBox, texts, lines, images,
                        List.of(), List.of(), pageWarnings));
            }
            return new DocumentModel(displayName, name(), pageCount, pages, documentWarnings);
        } catch (OfdParseException e) {
            throw e;
        } catch (Exception e) {
            throw new OfdParseException("OFD_PARSE_FAILED", "OFDRW 无法解析该 OFD：" + safeMessage(e), e);
        }
    }

    @Override public String name() { return "OFDRW 2.3.9"; }

    private boolean requiresOcr(List<TextBlock> texts, List<ImageBlock> images, Rect pageBox) {
        List<ImageBlock> contentImages = images.stream()
                .filter(image -> !"SIGNATURE".equalsIgnoreCase(image.role()))
                .toList();
        if (contentImages.isEmpty()) return false;
        int characters = texts.stream().map(TextBlock::text)
                .mapToInt(value -> (int) value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count())
                .sum();
        if (characters == 0) return true;
        double pageArea = Math.max(1d, pageBox.width() * pageBox.height());
        double largestCoverage = contentImages.stream().map(ImageBlock::box)
                .mapToDouble(box -> box.intersectionArea(pageBox) / pageArea).max().orElse(0d);
        return characters < 20 && largestCoverage >= 0.5d;
    }

    private int parseBlocks(List<PageBlockType> blocks, int page, int z, ResourceManage resources,
                            List<TextBlock> texts, List<LineElement> lines, List<ImageBlock> images,
                            List<ConversionWarning> warnings, ParseTraversal traversal, int depth)
            throws OfdParseException {
        if (blocks == null || blocks.isEmpty()) return z;
        if (depth > MAX_PAGE_BLOCK_DEPTH) {
            warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                    "OFD 页面对象嵌套超过安全限制，已跳过过深内容", page));
            return z;
        }
        for (PageBlockType block : blocks) {
            if (block == null) continue;
            if (!traversal.accept(block)) {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "OFD 页面对象存在循环引用，已跳过重复对象", page));
                continue;
            }
            if (block instanceof TextObject text) parseText(text, page, z++, resources, texts, warnings);
            else if (block instanceof PathObject path) parsePath(path, page, z++, resources, lines, warnings);
            else if (block instanceof ImageObject image) parseImage(image, page, z++, resources, images, warnings);
            else if (block instanceof CT_PageBlock nested) {
                z = parseBlocks(nested.getPageBlocks(), page, z, resources, texts, lines, images, warnings,
                        traversal, depth + 1);
            } else {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "暂不支持的 OFD 页面对象：" + block.getClass().getSimpleName(), page));
            }
        }
        return z;
    }

    private void parseText(TextObject object, int page, int z, ResourceManage resources,
                           List<TextBlock> out, List<ConversionWarning> warnings) {
        ST_Box b = object.getBoundary();
        if (b == null || Boolean.FALSE.equals(object.getVisible())) return;
        Transform2D transform = textTransform(object);
        CT_Font font = object.getFont() == null ? null : resources.getFont(object.getFont().toString());
        String family = font == null ? null : firstNonBlank(font.getFamilyName(), font.getFontName());
        double verticalScale = Math.max(0.01d, transform.scaleY());
        double sizePt = object.getSize() == null ? 10.5 : object.getSize() * verticalScale * 72d / 25.4d;
        boolean bold = object.getWeight() != null && !"400".equals(object.getWeight().toString());
        if (font != null && Boolean.TRUE.equals(font.getBold())) bold = true;
        boolean italic = Boolean.TRUE.equals(object.getItalic()) || (font != null && Boolean.TRUE.equals(font.getItalic()));
        FontStyle style = new FontStyle(family, sizePt, bold, italic, color(object.getFillColor()));
        Rect box = box(b);
        if (transform.hasSkew(0.01d)) {
            warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_TEXT_TRANSFORM,
                    "文字对象包含斜切变换，已按最接近的缩放和旋转保留", page));
        }

        double cursorX = 0;
        double cursorY = object.getSize() == null ? 0 : object.getSize();
        int codeIndex = 0;
        for (TextCode code : object.getTextCodes()) {
            String content = code.getContent() == null ? "" : code.getContent();
            double sourceX = code.getX() == null ? cursorX : code.getX();
            double sourceY = code.getY() == null ? cursorY : code.getY();
            try {
                int[] codePoints = content.codePoints().toArray();
                int gaps = Math.max(0, codePoints.length - 1);
                List<Double> deltaX = expandDeltas(code.getDeltaX(), gaps);
                List<Double> deltaY = expandDeltas(code.getDeltaY(), gaps);
                double glyphX = sourceX;
                double glyphY = sourceY;
                double runX = glyphX;
                double runY = glyphY;
                StringBuilder run = new StringBuilder();
                List<Double> advances = new ArrayList<>();
                int runIndex = 0;
                for (int glyph = 0; glyph < codePoints.length; glyph++) {
                    run.appendCodePoint(codePoints[glyph]);
                    if (glyph >= gaps) continue;
                    double dx = deltaX.isEmpty() ? 0 : deltaX.get(glyph);
                    double dy = deltaY.isEmpty() ? 0 : deltaY.get(glyph);
                    if (Math.abs(dy) > 0.001d) {
                        addTextRun(object, page, z, box, style, transform, codeIndex, runIndex++,
                                runX, runY, run.toString(), advances, out);
                        run.setLength(0);
                        advances = new ArrayList<>();
                        glyphX += dx;
                        glyphY += dy;
                        runX = glyphX;
                        runY = glyphY;
                    } else {
                        if (!deltaX.isEmpty()) {
                            advances.add(signedLength(transform.applyVector(new Point(dx, 0)), dx));
                            glyphX += dx;
                        }
                    }
                }
                if (!run.isEmpty()) {
                    addTextRun(object, page, z, box, style, transform, codeIndex, runIndex,
                            runX, runY, run.toString(), advances, out);
                }
                double lastGlyph = (object.getSize() == null ? 3.7d : object.getSize());
                cursorX = glyphX + lastGlyph;
                cursorY = glyphY;
            } catch (RuntimeException e) {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "文字字距数据无效或超过安全限制，已按默认字距处理", page));
                Point offset = transform.apply(new Point(sourceX, sourceY));
                out.add(new TextBlock(textObjectId(object, page, z) + "-" + codeIndex, page, box, content,
                        box.y() + offset.y(), style, z, offset.x(), offset.y(), List.of(), transform));
                cursorX = sourceX + (object.getSize() == null ? 3.7d : object.getSize());
                cursorY = sourceY;
            }
            codeIndex++;
        }
    }

    private void addTextRun(TextObject object, int page, int z, Rect box, FontStyle style,
                            Transform2D transform, int codeIndex, int runIndex,
                            double sourceX, double sourceY, String text, List<Double> advances,
                            List<TextBlock> out) {
        if (text.isEmpty()) return;
        Point offset = transform.apply(new Point(sourceX, sourceY));
        out.add(new TextBlock(textObjectId(object, page, z) + "-" + codeIndex + "-" + runIndex,
                page, box, text,
                box.y() + offset.y(), style, z, offset.x(), offset.y(), advances, transform));
    }

    private String textObjectId(TextObject object, int page, int z) {
        return object.getID() == null ? "p" + page + "-text-" + z : object.getID().toString();
    }

    private void parsePath(PathObject object, int page, int z, ResourceManage resources, List<LineElement> out,
                           List<ConversionWarning> warnings) {
        ST_Box boundary = object.getBoundary();
        if (boundary == null || object.getAbbreviatedDataEle() == null || Boolean.FALSE.equals(object.getVisible())) return;
        ColorValue fillColor = pathFillColor(object, resources);
        ColorValue strokeColor = pathStrokeColor(object, resources);
        double lineWidth = pathLineWidth(object, resources);
        if (Boolean.FALSE.equals(object.getStroke()) && Boolean.TRUE.equals(object.getFill())) {
            if (boundary.getWidth() >= boundary.getHeight() * 4 && boundary.getHeight() <= 3) {
                Point start = transformLocal(object.getCTM(), boundary.getTopLeftX(), boundary.getTopLeftY(),
                        new Point(0, boundary.getHeight() / 2d));
                Point end = transformLocal(object.getCTM(), boundary.getTopLeftX(), boundary.getTopLeftY(),
                        new Point(boundary.getWidth(), boundary.getHeight() / 2d));
                out.add(new LineElement(object.getID() + "-fill", page,
                        start, end, boundary.getHeight() * transform(object.getCTM()).scaleY(), fillColor, z));
            } else if (boundary.getHeight() >= boundary.getWidth() * 4 && boundary.getWidth() <= 3) {
                Point start = transformLocal(object.getCTM(), boundary.getTopLeftX(), boundary.getTopLeftY(),
                        new Point(boundary.getWidth() / 2d, 0));
                Point end = transformLocal(object.getCTM(), boundary.getTopLeftX(), boundary.getTopLeftY(),
                        new Point(boundary.getWidth() / 2d, boundary.getHeight()));
                out.add(new LineElement(object.getID() + "-fill", page,
                        start, end, boundary.getWidth() * transform(object.getCTM()).scaleX(), fillColor, z));
            } else {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "复杂填充路径暂未展开，已跳过该填充对象", page));
            }
            return;
        }
        if (Boolean.TRUE.equals(object.getFill())) {
            warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                    "路径填充、渐变或透明度暂未完整保留，已保留路径边线", page));
        }
        double ox = boundary.getTopLeftX();
        double oy = boundary.getTopLeftY();
        Point currentLocal = null;
        Point subpathStartLocal = null;
        int segment = 0;
        int operationCount = 0;
        for (OptVal operation : object.getAbbreviatedDataEle().getRawOptVal()) {
            if (++operationCount > MAX_PATH_OPERATIONS) {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "路径操作数量超过安全限制，已截断", page));
                break;
            }
            double[] v = operation.getValues();
            switch (operation.getOpt()) {
                case "S", "M" -> {
                    if (v.length >= 2) {
                        currentLocal = new Point(v[0], v[1]);
                        subpathStartLocal = currentLocal;
                    }
                }
                case "L" -> {
                    if (currentLocal != null && v.length >= 2) {
                        Point nextLocal = new Point(v[0], v[1]);
                        addLine(object, page, z, segment++, transformLocal(object.getCTM(), ox, oy, currentLocal),
                                transformLocal(object.getCTM(), ox, oy, nextLocal), lineWidth, strokeColor, out);
                        currentLocal = nextLocal;
                    }
                }
                case "Q" -> {
                    if (currentLocal != null && v.length >= 4) {
                        Point control = new Point(v[0], v[1]);
                        Point end = new Point(v[2], v[3]);
                        segment = addQuadratic(object, page, z, segment, currentLocal, control, end,
                                object.getCTM(), ox, oy, lineWidth, strokeColor, out);
                        currentLocal = end;
                    }
                }
                case "B" -> {
                    if (currentLocal != null && v.length >= 6) {
                        Point firstControl = new Point(v[0], v[1]);
                        Point secondControl = new Point(v[2], v[3]);
                        Point end = new Point(v[4], v[5]);
                        segment = addCubic(object, page, z, segment, currentLocal, firstControl,
                                secondControl, end, object.getCTM(), ox, oy, lineWidth, strokeColor, out);
                        currentLocal = end;
                    }
                }
                case "A" -> {
                    if (currentLocal != null && v.length >= 7) {
                        Point end = new Point(v[5], v[6]);
                        addLine(object, page, z, segment++, transformLocal(object.getCTM(), ox, oy, currentLocal),
                                transformLocal(object.getCTM(), ox, oy, end), lineWidth, strokeColor, out);
                        currentLocal = end;
                        warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                                "弧线路径暂以端点连线近似，请复核版式", page));
                    }
                }
                case "C" -> {
                    if (currentLocal != null && subpathStartLocal != null) {
                        addLine(object, page, z, segment++, transformLocal(object.getCTM(), ox, oy, currentLocal),
                                transformLocal(object.getCTM(), ox, oy, subpathStartLocal),
                                lineWidth, strokeColor, out);
                        currentLocal = subpathStartLocal;
                    }
                }
                case "CM" -> warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "Path 内部坐标变换 CM 暂未展开", page));
                default -> warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "未知路径操作已跳过：" + operation.getOpt(), page));
            }
            if (segment >= MAX_PATH_SEGMENTS) {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_OFD_ELEMENT,
                        "路径线段数量超过安全限制，已截断", page));
                break;
            }
        }
    }

    private int addQuadratic(PathObject object, int page, int z, int segment,
                             Point start, Point control, Point end, ST_Array ctm,
                             double ox, double oy, double lineWidth, ColorValue color,
                             List<LineElement> out) {
        Point previous = start;
        for (int step = 1; step <= QUADRATIC_SEGMENTS && segment < MAX_PATH_SEGMENTS; step++) {
            double t = step / (double) QUADRATIC_SEGMENTS;
            double inverse = 1d - t;
            Point next = new Point(inverse * inverse * start.x() + 2d * inverse * t * control.x() + t * t * end.x(),
                    inverse * inverse * start.y() + 2d * inverse * t * control.y() + t * t * end.y());
            addLine(object, page, z, segment++, transformLocal(ctm, ox, oy, previous),
                    transformLocal(ctm, ox, oy, next), lineWidth, color, out);
            previous = next;
        }
        return segment;
    }

    private int addCubic(PathObject object, int page, int z, int segment,
                         Point start, Point firstControl, Point secondControl, Point end,
                         ST_Array ctm, double ox, double oy, double lineWidth, ColorValue color,
                         List<LineElement> out) {
        Point previous = start;
        for (int step = 1; step <= CUBIC_SEGMENTS && segment < MAX_PATH_SEGMENTS; step++) {
            double t = step / (double) CUBIC_SEGMENTS;
            double inverse = 1d - t;
            Point next = new Point(
                    inverse * inverse * inverse * start.x()
                            + 3d * inverse * inverse * t * firstControl.x()
                            + 3d * inverse * t * t * secondControl.x() + t * t * t * end.x(),
                    inverse * inverse * inverse * start.y()
                            + 3d * inverse * inverse * t * firstControl.y()
                            + 3d * inverse * t * t * secondControl.y() + t * t * t * end.y());
            addLine(object, page, z, segment++, transformLocal(ctm, ox, oy, previous),
                    transformLocal(ctm, ox, oy, next), lineWidth, color, out);
            previous = next;
        }
        return segment;
    }

    private void addLine(PathObject object, int page, int z, int segment, Point a, Point b,
                         double lineWidth, ColorValue strokeColor, List<LineElement> out) {
        if (Math.hypot(a.x() - b.x(), a.y() - b.y()) <= 0.001d) return;
        out.add(new LineElement(object.getID() + "-" + segment, page, a, b,
                lineWidth, strokeColor, z));
    }

    private void parseImage(ImageObject object, int page, int z, ResourceManage resources,
                            List<ImageBlock> out, List<ConversionWarning> warnings) {
        if (object.getBoundary() == null || object.getResourceID() == null || Boolean.FALSE.equals(object.getVisible())) return;
        try {
            byte[] bytes = resources.getImageByteArray(object.getResourceID().toString());
            out.add(new ImageBlock(object.getID().toString(), page, box(object.getBoundary()),
                    detectMime(bytes), bytes, "IMAGE", z));
        } catch (IOException | RuntimeException e) {
            warnings.add(ConversionWarning.of(WarningCode.IMAGE_EXTRACTION_FAILED,
                    "图片资源提取失败，资源 ID=" + object.getResourceID(), page));
        }
    }

    private Map<String, List<StampImage>> readStampImages(OFDReader reader,
                                                           List<ConversionWarning> warnings) {
        Map<String, List<StampImage>> result = new HashMap<>();
        if (!reader.hasSignature()) return result;
        try {
            int entityIndex = 0;
            for (StampAnnotEntity entity : reader.getStampAnnots()) {
                byte[] source = entity.getImageByte();
                if (isEmbeddedOfdStamp(source, entity.getImgType())) {
                    warnings.add(ConversionWarning.of(WarningCode.SIGNATURE_APPEARANCE_FAILED,
                            "数字签章外观为嵌套 OFD，当前发行版不内置签章渲染器；已保留正文并跳过签章外观", null));
                    entityIndex++;
                    continue;
                }
                NormalizedImage image = normalizeStampImage(source);
                if (image == null) {
                    warnings.add(ConversionWarning.of(WarningCode.SIGNATURE_APPEARANCE_FAILED,
                            "数字签章外观格式暂无法转换：" + entity.getImgType(), null));
                    entityIndex++;
                    continue;
                }
                int annotIndex = 0;
                for (var annot : entity.getStampAnnots()) {
                    if (annot.getPageRef() == null || annot.getBoundary() == null) continue;
                    StampImage stamp = new StampImage("signature-" + entityIndex + "-" + annotIndex++,
                            box(annot.getBoundary()), image.mimeType(), image.data());
                    result.computeIfAbsent(annot.getPageRef().toString(), ignored -> new ArrayList<>()).add(stamp);
                }
                entityIndex++;
            }
        } catch (Exception e) {
            warnings.add(ConversionWarning.of(WarningCode.SIGNATURE_APPEARANCE_FAILED,
                    "数字签章外观提取失败：" + safeMessage(e), null));
        } catch (LinkageError e) {
            warnings.add(ConversionWarning.of(WarningCode.SIGNATURE_APPEARANCE_FAILED,
                    "数字签章外观解析组件不兼容，已保留正文并跳过签章外观：" + e.getClass().getSimpleName(), null));
        }
        return result;
    }

    private NormalizedImage normalizeStampImage(byte[] source) throws IOException {
        if (source == null || source.length == 0) return null;
        String detected = detectMime(source);
        if (!"application/octet-stream".equals(detected)) return new NormalizedImage(detected, source);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
        if (decoded == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(decoded, "png", output)) return null;
        return new NormalizedImage("image/png", output.toByteArray());
    }

    boolean isEmbeddedOfdStamp(byte[] source, String declaredType) {
        String type = declaredType == null ? "" : declaredType.trim().toUpperCase(Locale.ROOT);
        return "OFD".equals(type) || (source != null && isZip(source));
    }

    private Transform2D textTransform(TextObject object) {
        Transform2D matrix = transform(object.getCTM());
        double horizontalScale = object.getHScale() == null ? 1d : object.getHScale();
        if (horizontalScale <= 0) horizontalScale = 1d;
        return new Transform2D(matrix.a() * horizontalScale, matrix.b() * horizontalScale,
                matrix.c(), matrix.d(), matrix.e(), matrix.f());
    }

    private Transform2D transform(ST_Array ctm) {
        if (ctm == null || ctm.size() < 6) return Transform2D.IDENTITY;
        double[] m = ctm.expectArr(6);
        return new Transform2D(m[0], m[1], m[2], m[3], m[4], m[5]);
    }

    private double signedLength(Point vector, double source) {
        return Math.copySign(Math.hypot(vector.x(), vector.y()), source);
    }

    private ColorValue pathFillColor(PathObject object, ResourceManage resources) {
        if (object.getFillColor() != null) return color(object.getFillColor());
        CT_DrawParam inherited = drawParam(object, resources);
        return inherited == null ? ColorValue.BLACK : color(inherited.getFillColor());
    }

    private ColorValue pathStrokeColor(PathObject object, ResourceManage resources) {
        if (object.getStrokeColor() != null) return color(object.getStrokeColor());
        CT_DrawParam inherited = drawParam(object, resources);
        return inherited == null ? ColorValue.BLACK : color(inherited.getStrokeColor());
    }

    private double pathLineWidth(PathObject object, ResourceManage resources) {
        if (object.getLineWidth() != null) return object.getLineWidth();
        CT_DrawParam inherited = drawParam(object, resources);
        return inherited == null || inherited.getLineWidth() == null ? 0.2d : inherited.getLineWidth();
    }

    private CT_DrawParam drawParam(PathObject object, ResourceManage resources) {
        try { return resources.superDrawParam(object); }
        catch (RuntimeException ignored) { return null; }
    }

    private Point transformLocal(ST_Array ctm, double offsetX, double offsetY, Point p) {
        Point transformed = transform(ctm).apply(p);
        return new Point(offsetX + transformed.x(), offsetY + transformed.y());
    }

    private Rect box(ST_Box b) { return new Rect(b.getTopLeftX(), b.getTopLeftY(), b.getWidth(), b.getHeight()); }

    private ColorValue color(CT_Color value) {
        if (value == null || value.getValue() == null || value.getValue().size() < 3) return ColorValue.BLACK;
        Integer[] c = value.getValue().toInt();
        return new ColorValue(clamp(c[0]), clamp(c[1]), clamp(c[2]), value.getAlpha() == null ? 255 : clamp(value.getAlpha()));
    }

    private int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    private List<Double> expandDeltas(ST_Array encoded, int gaps) {
        if (encoded == null || encoded.size() == 0 || gaps <= 0) return List.of();
        List<Double> result = new ArrayList<>();
        List<String> values = encoded.getArray();
        int maxValues = Math.max(1024, gaps * 4);
        for (int i = 0; i < values.size(); i++) {
            String token = values.get(i);
            if ("g".equalsIgnoreCase(token) && i + 2 < values.size()) {
                int count = Integer.parseInt(values.get(++i));
                double repeated = Double.parseDouble(values.get(++i));
                if (count < 0 || result.size() + count > maxValues) {
                    throw new IllegalArgumentException("DeltaX repeat exceeds limit");
                }
                for (int n = 0; n < count; n++) result.add(repeated);
            } else {
                if (result.size() >= maxValues) throw new IllegalArgumentException("DeltaX exceeds limit");
                result.add(Double.parseDouble(token));
            }
        }
        if (!result.isEmpty()) {
            double repeated = result.get(result.size() - 1);
            while (result.size() < gaps) result.add(repeated);
            if (result.size() > gaps) result = new ArrayList<>(result.subList(0, gaps));
        }
        return result;
    }
    private String firstNonBlank(String first, String second) { return first != null && !first.isBlank() ? first : second; }
    private String detectMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') return "image/bmp";
        return "application/octet-stream";
    }
    private boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 3 && bytes[3] == 4;
    }
    private String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private record StampImage(String id, Rect box, String mimeType, byte[] data) { }
    private record NormalizedImage(String mimeType, byte[] data) { }

    private static final class ParseTraversal {
        private final int maxObjects;
        private final java.util.Set<PageBlockType> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private int objects;

        private ParseTraversal(int maxObjects) { this.maxObjects = Math.max(1, maxObjects); }

        private boolean accept(PageBlockType block) throws OfdParseException {
            if (!visited.add(block)) return false;
            if (++objects > maxObjects) {
                throw new OfdParseException("OFD_TOO_MANY_PAGE_OBJECTS", "OFD 页面对象数量超过限制");
            }
            return true;
        }
    }
}
