package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** On-demand local OCR route. Release bundles auto-enable capability; no cloud or implicit OCR fallback is used. */
public final class TesseractOcrConverter implements FileConverter {
    private static final String ENABLED_ENV = "FORMAT_CONVERTER_OCR_ENABLED";
    private static final String BINARY_ENV = "FORMAT_CONVERTER_TESSERACT_BINARY";
    private static final String LANGUAGES_ENV = "FORMAT_CONVERTER_OCR_LANGUAGES";
    private static final String TIMEOUT_ENV = "FORMAT_CONVERTER_OCR_TIMEOUT_SECONDS";
    private static final String CONCURRENCY_ENV = "FORMAT_CONVERTER_OCR_MAX_CONCURRENCY";
    private static final String MIN_CONFIDENCE_ENV = "FORMAT_CONVERTER_OCR_MIN_CONFIDENCE";
    private static final String WARN_CONFIDENCE_ENV = "FORMAT_CONVERTER_OCR_WARN_CONFIDENCE";
    private static final String MAX_PIXELS_ENV = "FORMAT_CONVERTER_OCR_MAX_PIXELS";
    private static final String LOCK_DIR_ENV = "FORMAT_CONVERTER_OCR_LOCK_DIR";
    private static final String APP_HOME_ENV = "FORMAT_CONVERTER_APP_HOME";
    private static final String APP_HOME_PROPERTY = "format.converter.app.home";
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)?");

    private final DocumentFormat sourceFormat;
    private final ConversionRoute route;
    private final Settings settings;

    public TesseractOcrConverter(DocumentFormat sourceFormat, Settings settings) {
        if (sourceFormat != DocumentFormat.PNG && sourceFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("Tesseract 图片 OCR 仅支持 PNG/JPEG");
        }
        this.sourceFormat = sourceFormat;
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.route = ConversionRoute.of(sourceFormat, DocumentFormat.TXT,
                "明确使用本地 Tesseract OCR 提取图片文字，并返回置信度和页面级警告。",
                QualityLevel.EXPERIMENTAL, ConversionStrategy.EXTRACTION, List.of("tesseract"),
                List.of("OCR 结果可能存在错字和阅读顺序误差，必须人工复核", "不会调用云端 OCR"));
    }

    TesseractOcrConverter(DocumentFormat sourceFormat, Path binary, String languages, Duration timeout) {
        this(sourceFormat, new Settings(binary, languages, version(binary).orElse("unknown"), timeout,
                1, 0.35d, 0.75d, 25_000_000L, defaultLockDirectory()));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        ConversionGuards.requireImageBounds(input.path(), limits);
        requireImageWithinOcrLimit(input.path());
        progress.update(TaskStage.PARSING, 15);
        OcrImageNormalizer.Prepared prepared = OcrImageNormalizer.prepare(input.path(), sourceFormat,
                workDir.resolve("normalized"));
        long started = System.nanoTime();
        RecognitionResult recognized = recognizeLayoutResult(prepared.path(), workDir, 1,
                new Rect(0d, 0d, prepared.width(), prepared.height()), limits, true);
        requireUsableResult(recognized, "图片");
        progress.update(TaskStage.RENDERING, 75);
        var ordered = OcrReadingOrder.arrange(recognized.blocks(), prepared.width(), started + settings.timeout().toNanos());
        String text = ordered.lines().stream()
                .reduce((left, right) -> left + System.lineSeparator() + right).orElse("");
        Files.writeString(outputPath, text + (text.isEmpty() ? "" : System.lineSeparator()), StandardCharsets.UTF_8);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "Tesseract OCR");
        progress.update(TaskStage.PACKAGING, 90);
        List<ConversionWarning> warnings = new ArrayList<>(warningsFor(recognized, 1, "图片"));
        if (ordered.adjusted()) warnings.add(ConversionWarning.of(WarningCode.OCR_READING_ORDER_ADJUSTED,
                "根据稳定空白分栏调整为左栏后右栏的 TXT 阅读顺序；原词、数字和坐标未改变，歧义版面仍需人工复核。", 1));
        if (ordered.multipleColumns()) warnings.add(ConversionWarning.of(WarningCode.OCR_READING_ORDER_UNCERTAIN,
                "检测到多个显著空栏，可能是三栏以上或表格；未执行双栏重排，保留引擎顺序，仍可能混行，请按原图人工复核。", 1));
        if (prepared.orientationApplied()) {
            warnings.add(ConversionWarning.of(WarningCode.EXIF_ORIENTATION_APPLIED,
                    "OCR 前已应用 EXIF Orientation=" + prepared.metadata().orientation() + "。", 1));
        }
        return new ConversionOutput(outputPath, replaceExtension(input.displayName(), "txt"), 1, warnings);
    }

    List<TextBlock> recognizeLayout(Path image, Path workDir, int pageNumber,
                                    Rect physicalBox, ParseLimits limits) throws Exception {
        RecognitionResult result = recognizeLayoutResult(image, workDir, pageNumber, physicalBox, limits);
        requireUsableResult(result, "第 " + pageNumber + " 页");
        return result.blocks();
    }

    RecognitionResult recognizeLayoutResult(Path image, Path workDir, int pageNumber,
                                             Rect physicalBox, ParseLimits limits) throws Exception {
        return recognizeLayoutResult(image, workDir, pageNumber, physicalBox, limits, false);
    }

    // General-angle editable text is not reliably reopened by the cloud Office runtime.
    // Enable optional deskew for text extraction only until Word geometry is accepted.
    RecognitionResult recognizeLayoutResult(Path image, Path workDir, int pageNumber,
                                             Rect physicalBox, ParseLimits limits, boolean allowDeskew) throws Exception {
        Files.createDirectories(workDir);
        ConversionGuards.requireImageBounds(image, limits);
        ImageDimensions dimensions = dimensions(image);
        requireOcrPixelLimit(dimensions);
        Path engineImage = pngForEngine(image, workDir, pageNumber, dimensions);
        long started = System.nanoTime();
        try {
            RecognitionResult result = recognizeOnce(engineImage, workDir, pageNumber, physicalBox, dimensions,
                    limits, "tesseract-page-%04d".formatted(pageNumber), settings.timeout());
            BufferedImage pixels = ImageIO.read(engineImage.toFile());
            if (pixels == null) return result;
            try {
                boolean recoveryEligible = result.blocks().isEmpty() || result.confidence() < settings.warningConfidence();
                boolean uncoveredShadedInk = false;
                if (!recoveryEligible && "3".equals(pageSegmentationMode())
                        && remainingTime(started).compareTo(Duration.ofSeconds(1)) > 0) {
                    uncoveredShadedInk = OcrCoverageProbe.hasUncoveredShadedInk(pixels, result.blocks(), physicalBox,
                            started + settings.timeout().toNanos());
                    // Above 95%, the unchanged five-point gain cannot be met.
                    // Still expose unresolved coverage; confidence is not completeness.
                    recoveryEligible = uncoveredShadedInk && result.confidence() <= .95;
                }
                if (recoveryEligible) {
                    result = retryEnhanced(result, pixels, workDir, pageNumber, physicalBox, dimensions, limits, started);
                }
                if (result.partialRecovery()) return result;
                if (allowDeskew) result = retryDeskew(result, pixels, workDir, pageNumber, physicalBox, dimensions, limits, started);
                if (result.deskewDegrees() != 0 || result.partialRecovery()) return result;
                // The original pixels, rather than enhanced pixels, remain the geometry authority.
                return new RecognitionResult(result.blocks().stream()
                        .map(block -> OcrWordGeometryRefiner.refine(block, pixels, physicalBox)).toList(),
                        result.confidence(), result.wordCount(), result.imageEnhanced(), result.deskewDegrees(),
                        result.conflicts(), uncoveredShadedInk && !result.imageEnhanced());
            } finally { pixels.flush(); }
        } finally {
            if (!engineImage.equals(image)) {
                try { Files.deleteIfExists(engineImage); } catch (IOException ignored) { }
            }
        }
    }

    private RecognitionResult recognizeOnce(Path image, Path workDir, int pageNumber, Rect physicalBox,
                                             ImageDimensions dimensions, ParseLimits limits, String name,
                                             Duration timeout) throws Exception {
        Path base = workDir.resolve(name);
        List<String> command = new ArrayList<>(List.of(settings.binary().toString(), image.toString(), base.toString()));
        if (settings.tessdataDirectory() != null) {
            command.add("--tessdata-dir");
            command.add(settings.tessdataDirectory().toString());
        }
        command.addAll(List.of("-l", settings.languages(), "--psm", pageSegmentationMode(), "tsv"));
        runTesseract(command, workDir.resolve(name + ".log"), "Tesseract OCR 第 " + pageNumber + " 页", timeout);
        Path tsv = Path.of(base + ".tsv");
        ConversionGuards.requireOutputFile(tsv, limits, "Tesseract OCR TSV");
        return parseTsv(tsv, pageNumber, physicalBox, dimensions, limits);
    }

    private RecognitionResult retryEnhanced(RecognitionResult original, BufferedImage pixels, Path workDir,
                                              int pageNumber, Rect physicalBox, ImageDimensions dimensions,
                                              ParseLimits limits, long started) throws Exception {
        if (remainingTime(started).compareTo(Duration.ofSeconds(1)) <= 0) return original;
        BufferedImage enhanced = OcrContrastEnhancer.enhance(pixels);
        if (enhanced == null) return original;
        Path temporary = null;
        try {
            temporary = Files.createTempFile(workDir, "tesseract-enhanced-%04d-".formatted(pageNumber), ".png");
            if (!ImageIO.write(enhanced, "png", temporary.toFile())) return original;
            Duration remaining = remainingTime(started);
            if (remaining.compareTo(Duration.ofSeconds(1)) <= 0) return original;
            RecognitionResult candidate = recognizeOnce(temporary, workDir, pageNumber, physicalBox, dimensions,
                    limits, "tesseract-enhanced-page-%04d".formatted(pageNumber), remaining);
            if (preferEnhanced(original, candidate, settings.minimumConfidence())) {
                return new RecognitionResult(candidate.blocks(), candidate.confidence(), candidate.wordCount(), true);
            }
            long deadline = started + settings.timeout().toNanos();
            var partial = OcrPartialRecovery.select(original, candidate, physicalBox, settings.minimumConfidence(),
                    .05, true, 0, deadline);
            return partial.partialRecovery() && !originalGeometryStable(original, pixels, physicalBox, deadline)
                    ? original : partial;
        } catch (IOException ignored) {
            // Optional recovery must not turn a usable original recognition into a failed task.
            return original;
        } finally {
            enhanced.flush();
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private RecognitionResult retryDeskew(RecognitionResult original, BufferedImage pixels, Path workDir,
                                         int pageNumber, Rect physicalBox, ImageDimensions dimensions,
                                         ParseLimits limits, long started) throws Exception {
        double sx = physicalBox.width() / dimensions.width(), sy = physicalBox.height() / dimensions.height();
        if (!"3".equals(pageSegmentationMode()) || sx <= 0 || sy <= 0 || Math.abs(sx / sy - 1) > .01
                || remainingTime(started).compareTo(Duration.ofSeconds(1)) <= 0) return original;
        long deadline = started + settings.timeout().toNanos();
        try (var prepared = OcrDeskew.prepare(pixels, settings.maxImagePixels(), deadline)) {
            if (prepared == null) return original;
            Path temporary = Files.createTempFile(workDir, "tesseract-deskew-%04d-".formatted(pageNumber), ".png");
            try {
                if (!ImageIO.write(prepared.image(), "png", temporary.toFile())) return original;
                Duration remaining = remainingTime(started);
                if (remaining.compareTo(Duration.ofSeconds(1)) <= 0) return original;
                var size = new ImageDimensions(prepared.image().getWidth(), prepared.image().getHeight());
                var candidate = recognizeOnce(temporary, workDir, pageNumber,
                        new Rect(0, 0, size.width() * sx, size.height() * sy), size, limits,
                        "tesseract-deskew-page-%04d".formatted(pageNumber), remaining);
                var selected = OcrDeskewSelection.select(original, candidate, prepared, physicalBox,
                        dimensions.width(), dimensions.height(), settings.minimumConfidence(), deadline);
                return selected.partialRecovery() && !originalGeometryStable(original, pixels, physicalBox, deadline)
                        ? original : selected;
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException ignored) {
            return original; // Optional recovery cannot discard a usable original result.
        }
    }

    private Duration remainingTime(long started) {
        return settings.timeout().minusNanos(System.nanoTime() - started);
    }

    private static boolean originalGeometryStable(RecognitionResult original, BufferedImage pixels,
                                                   Rect physical, long deadline) {
        // The unchanged fallback normally refines some Chinese word boxes. A partial
        // result must also preserve that API/Word geometry, not merely raw TSV boxes.
        for (var block : original.blocks()) {
            if (System.nanoTime() >= deadline || OcrWordGeometryRefiner.refine(block, pixels, physical) != block) return false;
        }
        return System.nanoTime() < deadline;
    }

    static boolean preferEnhanced(RecognitionResult original, RecognitionResult candidate, double minimumConfidence) {
        if (candidate.blocks().isEmpty() || candidate.confidence() < minimumConfidence
                || candidate.confidence() + 1e-9 < original.confidence() + 0.05) return false;
        String previous = comparisonText(original), next = comparisonText(candidate);
        if (next.isEmpty() || next.codePointCount(0, next.length())
                < previous.codePointCount(0, previous.length()) * 0.90) return false;
        String reliableCandidate = reliableText(joinedText(candidate));
        String numericCandidate = joinedText(candidate).toLowerCase(Locale.ROOT);
        int offset = 0;
        int numericOffset = 0;
        for (TextBlock block : original.blocks()) {
            for (TextBlock.OcrWord word : block.ocrWords()) {
                if (word.confidence() < 0.85) continue;
                if (word.text().codePoints().anyMatch(Character::isDigit)) {
                    // Numeric surfaces retain grouping whitespace and punctuation;
                    // stripping these can merge independent amounts or lose signs/units.
                    String numeric = word.text().toLowerCase(Locale.ROOT);
                    int numberMatch = numericCandidate.indexOf(numeric, numericOffset);
                    while (numberMatch >= 0 && numericBoundaryChanged(numericCandidate, numeric, numberMatch)) {
                        numberMatch = numericCandidate.indexOf(numeric, numberMatch + 1);
                    }
                    if (numberMatch < 0) return false;
                    numericOffset = numberMatch + numeric.length();
                }
                String reliable = reliableText(word.text());
                if (reliable.isEmpty()) continue;
                int match = reliableCandidate.indexOf(reliable, offset);
                while (match >= 0 && numericBoundaryChanged(reliableCandidate, reliable, match)) {
                    match = reliableCandidate.indexOf(reliable, match + 1);
                }
                if (match < 0) return false;
                offset = match + reliable.length();
            }
        }
        return true;
    }

    private static String comparisonText(RecognitionResult result) {
        return comparisonText(joinedText(result));
    }

    private static String joinedText(RecognitionResult result) {
        return result.blocks().stream().map(TextBlock::text).collect(java.util.stream.Collectors.joining());
    }

    private static boolean numericBoundaryChanged(String candidate, String reliable, int start) {
        if (reliable.codePoints().noneMatch(Character::isDigit)) return false;
        int end = start + reliable.length();
        return numericContinuation(reliable.codePointAt(0)) && start > 0
                && numericContinuation(candidate.codePointBefore(start))
                || numericContinuation(reliable.codePointBefore(reliable.length())) && end < candidate.length()
                && numericContinuation(candidate.codePointAt(end));
    }

    private static boolean numericContinuation(int codePoint) {
        return Character.isDigit(codePoint) || Character.getType(codePoint) == Character.CURRENCY_SYMBOL
                || ".,:/−－-+＋，．：／٫٬'’%％‰‱()（）".indexOf(codePoint) >= 0;
    }

    private static String reliableText(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().filter(value -> !Character.isWhitespace(value))
                .forEach(value -> result.appendCodePoint(Character.toLowerCase(value)));
        return result.toString();
    }

    private static String comparisonText(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().filter(Character::isLetterOrDigit)
                .forEach(value -> result.appendCodePoint(Character.toLowerCase(value)));
        return result.toString();
    }

    /** The bundled engine accepts PNG; orientation has already been handled by the caller. */
    private Path pngForEngine(Path image, Path workDir, int pageNumber, ImageDimensions dimensions)
            throws ConversionFailureException {
        BufferedImage decoded = null;
        Path normalized = null;
        boolean complete = false;
        try {
            try (var input = Files.newInputStream(image)) {
                if (java.util.Arrays.equals(input.readNBytes(8),
                        new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return image;
            }
            decoded = ImageIO.read(image.toFile());
            if (decoded == null || decoded.getWidth() != dimensions.width()
                    || decoded.getHeight() != dimensions.height()) {
                throw new IOException("Invalid OCR image");
            }
            normalized = Files.createTempFile(workDir, "tesseract-input-%04d-".formatted(pageNumber), ".png");
            if (!ImageIO.write(decoded, "png", normalized.toFile())) throw new IOException("No PNG writer");
            complete = true;
            return normalized;
        } catch (IOException | RuntimeException e) {
            throw new ConversionFailureException("OCR_IMAGE_NORMALIZATION_FAILED", "无法将 OCR 图片转换为兼容的 PNG 格式");
        } finally {
            if (decoded != null) decoded.flush();
            if (!complete && normalized != null) {
                try { Files.deleteIfExists(normalized); } catch (IOException ignored) { }
            }
        }
    }

    private void runTesseract(List<String> command, Path log, String label, Duration timeout) throws Exception {
        long started = System.nanoTime();
        try (OcrProcessPermit ignored = OcrProcessPermit.acquire(settings.lockDirectory(),
                settings.maxConcurrency(), timeout)) {
            Duration remaining = timeout.minusNanos(System.nanoTime() - started);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new ConversionFailureException("OCR_TIMEOUT", label + "已超过页面识别时限");
            }
            ConversionGuards.runProcess(command, processEnvironment(settings), log, remaining, label);
        } catch (ExternalProcessException e) {
            if (e.reason() == ExternalProcessException.Reason.TIMEOUT) {
                throw new ConversionFailureException("OCR_TIMEOUT", label + "超过 " + settings.timeout().toSeconds() + " 秒");
            }
            if (e.reason() == ExternalProcessException.Reason.START_FAILED) {
                throw new ConversionFailureException("OCR_ENGINE_UNAVAILABLE", "无法启动本地 Tesseract OCR 引擎");
            }
            if (e.exitCode() != null && (e.exitCode() == 137 || e.exitCode() == 9)) {
                throw new ConversionFailureException("OCR_RESOURCE_EXHAUSTED", "Tesseract OCR 被系统终止，可能超过内存限制");
            }
            throw new ConversionFailureException("OCR_ENGINE_FAILED", label + "执行失败");
        }
    }

    private String pageSegmentationMode() {
        return List.of(settings.languages().split("\\+")).stream()
                .anyMatch(language -> language.endsWith("_vert")) ? "5" : "3";
    }

    private void requireOcrPixelLimit(ImageDimensions dimensions) throws ConversionFailureException {
        long pixels;
        try {
            pixels = Math.multiplyExact((long) dimensions.width(), (long) dimensions.height());
        } catch (ArithmeticException e) {
            throw new ConversionFailureException("OCR_RESOURCE_EXHAUSTED", "OCR 图片像素尺寸无效");
        }
        if (pixels > settings.maxImagePixels()) {
            throw new ConversionFailureException("OCR_RESOURCE_EXHAUSTED",
                    "OCR 图片像素超过独立上限：" + pixels + " > " + settings.maxImagePixels());
        }
    }

    void requireImageWithinOcrLimit(Path image) throws Exception {
        requireOcrPixelLimit(dimensions(image));
    }

    void requireImageWithinOcrLimit(byte[] image) throws Exception {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            requireOcrPixelLimit(dimensions(stream));
        }
    }

    void requireRenderedPageWithinOcrLimit(Rect page, double dpi) throws ConversionFailureException {
        double width = Math.ceil(page.width() * dpi / 25.4d);
        double height = Math.ceil(page.height() * dpi / 25.4d);
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0d || height <= 0d
                || width * height > settings.maxImagePixels()) {
            throw new ConversionFailureException("OCR_RESOURCE_EXHAUSTED",
                    "OCR 页面渲染像素超过独立上限 " + settings.maxImagePixels());
        }
    }

    private RecognitionResult parseTsv(Path tsv, int pageNumber, Rect page,
                                       ImageDimensions dimensions, ParseLimits limits) throws Exception {
        LinkedHashMap<String, OcrLine> lines = new LinkedHashMap<>();
        double confidenceTotal = 0d;
        int wordCount = 0;
        try (var input = Files.lines(tsv, StandardCharsets.UTF_8)) {
            for (String row : input.skip(1).toList()) {
                String[] columns = row.split("\\t", 12);
                if (columns.length < 12 || !"5".equals(columns[0])) continue;
                String text = columns[11].strip();
                if (text.isEmpty()) continue;
                double confidence = number(columns[10], -1d);
                if (confidence < 0d || confidence > 100d) continue;
                int left = integer(columns[6]);
                int top = integer(columns[7]);
                int width = integer(columns[8]);
                int height = integer(columns[9]);
                if (width <= 0 || height <= 0) continue;
                int right = (int) Math.min(dimensions.width(), (long) left + width);
                int bottom = (int) Math.min(dimensions.height(), (long) top + height);
                left = Math.max(0, left); top = Math.max(0, top);
                width = right - left; height = bottom - top;
                if (width <= 0 || height <= 0) continue;
                String key = columns[2] + ":" + columns[3] + ":" + columns[4];
                lines.computeIfAbsent(key, ignored -> new OcrLine())
                        .add(text, left, top, width, height, confidence / 100d);
                confidenceTotal += confidence;
                wordCount++;
                if (wordCount > limits.maxEntries()) {
                    throw new java.io.IOException("OCR 文字对象超过限制：" + wordCount + " > " + limits.maxEntries());
                }
            }
        }
        List<TextBlock> result = new ArrayList<>(lines.size());
        int index = 0;
        for (OcrLine line : lines.values()) {
            double x = page.x() + line.left * page.width() / dimensions.width;
            double y = page.y() + line.top * page.height() / dimensions.height;
            double width = Math.max(0.5d, line.width() * page.width() / dimensions.width);
            double height = Math.max(0.5d, line.height() * page.height() / dimensions.height);
            double sizePt = Math.max(5d, Math.min(72d, height * 72d / 25.4d * 0.85d));
            List<TextBlock.OcrWord> words = line.words.stream().map(word -> new TextBlock.OcrWord(
                    new Rect(page.x() + word.x() * page.width() / dimensions.width(),
                            page.y() + word.y() * page.height() / dimensions.height(),
                            word.width() * page.width() / dimensions.width(),
                            word.height() * page.height() / dimensions.height()), word.text(), word.confidence())).toList();
            result.add(new TextBlock("ocr-p" + pageNumber + "-l" + (++index), pageNumber,
                    new Rect(x, y, width, height), line.text(), y + height * 0.85d,
                    new FontStyle("Arial", sizePt, false, false, null), index,
                    0, 0, List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY, words));
        }
        double confidence = wordCount == 0 ? 0d : confidenceTotal / wordCount / 100d;
        return new RecognitionResult(List.copyOf(result), confidence, wordCount);
    }

    void requireUsableResult(RecognitionResult result, String scope) throws ConversionFailureException {
        if (result.blocks().isEmpty()) {
            throw new ConversionFailureException("OCR_NO_TEXT", scope + "有可见内容，但 OCR 未识别到文字");
        }
        if (result.confidence() < settings.minimumConfidence()) {
            throw new ConversionFailureException("OCR_LOW_CONFIDENCE",
                    scope + " OCR 平均置信度 " + percent(result.confidence()) + "，低于最低阈值 "
                            + percent(settings.minimumConfidence()));
        }
    }

    List<ConversionWarning> warningsFor(RecognitionResult result, int pageNumber, String scope) {
        List<ConversionWarning> warnings = new ArrayList<>();
        warnings.add(ConversionWarning.withConfidence(WarningCode.OCR_APPLIED,
                scope + "已使用本地 Tesseract OCR，平均置信度 " + percent(result.confidence()) + "，结果必须人工复核。",
                pageNumber, null, result.confidence()));
        if (result.deskewDegrees() != 0) {
            warnings.add(ConversionWarning.of(WarningCode.OCR_DESKEW_APPLIED,
                    scope + "采用倾斜校正候选（" + String.format(Locale.ROOT, "%.2f", result.deskewDegrees())
                            + (result.partialRecovery()
                            ? "°）中的分离新行；全部原识别行及原词框保留，仅新词框逆变换到原图坐标，仍需复核遗漏。"
                            : "°）；词框已逆变换到原图坐标，仍需复核内容完整性。"), pageNumber));
        }
        for (String conflict : result.conflicts()) {
            warnings.add(ConversionWarning.of(WarningCode.OCR_RECOGNITION_CONFLICT, scope + conflict, pageNumber));
        }
        if (result.imageEnhanced()) {
            warnings.add(ConversionWarning.of(WarningCode.OCR_IMAGE_ENHANCED,
                    scope + (result.partialRecovery()
                            ? "仅补充了增强候选中严格分离的新行，全部原识别行及原词框保留；混合置信度包括原词，不能证明内容完整。"
                            : "采用了灰底/阴影归一化与对比度增强结果；原图和坐标未改变，仍需人工复核内容完整性。"), pageNumber));
        }
        if (result.possibleTextOmission()) {
            warnings.add(ConversionWarning.of(WarningCode.OCR_POSSIBLE_TEXT_OMISSION,
                    scope + (result.partialRecovery()
                            ? "原识别区域保留，未采用其替换候选，仅补充了严格分离的新行；仍可能漏字或保留原误识别，混合置信度不能证明内容完整，请对照原图复核。"
                            : "阴影字迹覆盖探测仍发现多个未覆盖区域，可能漏识别或包含非文字图形；"
                            + "未采用不满足保守条件的候选，平均置信度不能证明内容完整，请对照原图复核。"), pageNumber));
        }
        if (result.confidence() < settings.warningConfidence()) {
            warnings.add(ConversionWarning.withConfidence(WarningCode.OCR_LOW_CONFIDENCE,
                    scope + " OCR 置信度低于复核阈值 " + percent(settings.warningConfidence()) + "。",
                    pageNumber, null, result.confidence()));
        }
        return List.copyOf(warnings);
    }

    private ImageDimensions dimensions(Path image) throws Exception {
        try (ImageInputStream stream = ImageIO.createImageInputStream(image.toFile())) {
            return dimensions(stream);
        }
    }

    private ImageDimensions dimensions(ImageInputStream stream) throws Exception {
        if (stream == null) throw new java.io.IOException("无法读取 OCR 图片尺寸");
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new java.io.IOException("无法识别 OCR 图片格式");
        ImageReader reader = readers.next();
        try {
            reader.setInput(stream, true, true);
            return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
        } finally {
            reader.dispose();
        }
    }

    private int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return 0; }
    }

    private double number(String value, double fallback) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static Capability detectConfigured() {
        return detectConfigured(System.getenv());
    }

    static Capability detectConfigured(Map<String, String> environment) {
        Optional<Path> bundledRoot = bundledRoot(environment);
        String enabledValue = environment.get(ENABLED_ENV);
        boolean explicitlyConfigured = enabledValue != null && !enabledValue.isBlank();
        boolean useOcr = explicitlyConfigured ? enabled(enabledValue) : bundledRoot.isPresent();
        if (!useOcr) {
            return new Capability(false, false, null, null, "本地 OCR 未启用", null,
                    null, Set.of(), null);
        }
        String requested;
        Duration timeout;
        int concurrency;
        double minimumConfidence;
        double warningConfidence;
        long maxImagePixels;
        Path lockDirectory;
        try {
            requested = normalizeLanguages(environment.get(LANGUAGES_ENV));
            timeout = Duration.ofSeconds(positiveInt(environment.get(TIMEOUT_ENV), 120, 1, 3600));
            concurrency = positiveInt(environment.get(CONCURRENCY_ENV), 1, 1, 32);
            minimumConfidence = confidence(environment.get(MIN_CONFIDENCE_ENV), 0.35d);
            warningConfidence = confidence(environment.get(WARN_CONFIDENCE_ENV), 0.75d);
            maxImagePixels = positiveLong(environment.get(MAX_PIXELS_ENV), 25_000_000L, 1L, 80_000_000L);
            String configuredLock = environment.get(LOCK_DIR_ENV);
            lockDirectory = configuredLock == null || configuredLock.isBlank()
                    ? defaultLockDirectory() : Path.of(configuredLock).toAbsolutePath().normalize();
            if (warningConfidence < minimumConfidence) {
                throw new IllegalArgumentException("OCR 复核阈值不能低于最低阈值");
            }
        } catch (IllegalArgumentException e) {
            return new Capability(true, false, null, "OCR_CONFIG_INVALID", e.getMessage(), null,
                    environment.get(LANGUAGES_ENV), Set.of(), null);
        }
        Optional<EngineCandidate> engine = discoverEngine(environment.get(BINARY_ENV), environment.get("PATH"),
                bundledRoot.orElse(null));
        if (engine.isEmpty()) {
            String code = bundledRoot.isPresent() ? "OCR_BUNDLED_RUNTIME_INVALID" : "OCR_ENGINE_UNAVAILABLE";
            return new Capability(true, false, null, code,
                    bundledRoot.isPresent() ? "内置 OCR 运行时缺少可执行文件或 tessdata" : "未找到可执行的 Tesseract",
                    null, requested, Set.of(), null);
        }
        EngineCandidate selected = engine.orElseThrow();
        String configuredTessdata = environment.get("FORMAT_CONVERTER_TESSDATA_DIR");
        if (configuredTessdata != null && !configuredTessdata.isBlank()) {
            try {
                Path tessdata = Path.of(configuredTessdata).toAbsolutePath().normalize();
                if (!Files.isDirectory(tessdata) || !Files.isReadable(tessdata)) {
                    throw new IllegalArgumentException("OCR tessdata 语言包文件夹不存在或不可读");
                }
                selected = new EngineCandidate(selected.binary(), tessdata, selected.libraryDirectory(), false);
            } catch (IllegalArgumentException error) {
                return new Capability(true, false, null, "OCR_CONFIG_INVALID", error.getMessage(), null,
                        requested, Set.of(), null);
            }
        }
        Path executable = selected.binary();
        String detectedVersion = version(selected).orElse("unknown");
        Set<String> available = languages(selected);
        List<String> missing = List.of(requested.split("\\+")).stream()
                .filter(language -> !available.contains(language)).toList();
        if (!missing.isEmpty()) {
            return new Capability(true, false, null, "OCR_LANGUAGE_MISSING",
                    "缺少 OCR 语言包：" + String.join(", ", missing), executable.getFileName().toString(),
                    requested, available, detectedVersion);
        }
        Settings settings = new Settings(executable, requested, detectedVersion, timeout, concurrency,
                minimumConfidence, warningConfidence, maxImagePixels, lockDirectory,
                selected.tessdataDirectory(), selected.libraryDirectory(), selected.bundled());
        return new Capability(true, true, settings, null,
                selected.bundled() ? "内置 Tesseract OCR 可用" : "本地 Tesseract OCR 可用",
                executable.getFileName().toString(), requested, available, detectedVersion);
    }

    public static Optional<Settings> configuredSettings() {
        return Optional.ofNullable(detectConfigured().settings());
    }

    public static boolean configuredEnabled() { return detectConfigured().enabled(); }

    public static Optional<Path> discover(String configuredBinary) {
        return discover(configuredBinary, System.getenv("PATH"));
    }

    static Optional<Path> discover(String configuredBinary, String searchPath) {
        List<Path> candidates = new ArrayList<>();
        if (configuredBinary != null && !configuredBinary.isBlank()) candidates.add(Path.of(configuredBinary));
        if (searchPath != null) {
            for (String directory : searchPath.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) candidates.add(Path.of(directory, isWindows() ? "tesseract.exe" : "tesseract"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).filter(Files::isExecutable)
                .filter(candidate -> version(candidate).isPresent()).findFirst();
    }

    private static Optional<EngineCandidate> discoverEngine(String configuredBinary, String searchPath,
                                                             Path bundledRoot) {
        if (configuredBinary != null && !configuredBinary.isBlank()) {
            Path binary = Path.of(configuredBinary).toAbsolutePath().normalize();
            if (isRunnable(binary)) return Optional.of(new EngineCandidate(binary, null, null, false));
            return Optional.empty();
        }
        if (bundledRoot != null) {
            Path executable = bundledRoot.resolve(isWindows() ? "bin/tesseract.exe" : "bin/tesseract");
            if (!Files.isRegularFile(executable)) {
                executable = bundledRoot.resolve(isWindows() ? "tesseract.exe" : "tesseract");
            }
            Path tessdata = bundledRoot.resolve("tessdata");
            Path library = bundledRoot.resolve("lib");
            if (isRunnable(executable) && Files.isDirectory(tessdata)) {
                return Optional.of(new EngineCandidate(executable.toAbsolutePath().normalize(),
                        tessdata.toAbsolutePath().normalize(), Files.isDirectory(library)
                                ? library.toAbsolutePath().normalize() : null, true));
            }
        }
        return discover(configuredBinary, searchPath)
                .map(binary -> new EngineCandidate(binary.toAbsolutePath().normalize(), null, null, false));
    }

    static Optional<Path> bundledRoot(Map<String, String> environment) {
        LinkedHashSet<Path> homes = new LinkedHashSet<>();
        addHome(homes, environment.get(APP_HOME_ENV));
        addHome(homes, System.getProperty(APP_HOME_PROPERTY));
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) {
            try {
                Path parent = Path.of(launcher).toAbsolutePath().normalize().getParent();
                if (parent != null) homes.add(parent);
            } catch (RuntimeException ignored) {
                // Ignore invalid launcher metadata, just like an invalid app-home.
            }
        }
        try {
            Path location = Path.of(TesseractOcrConverter.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            Path parent = Files.isDirectory(location) ? location : location.getParent();
            if (parent != null) {
                homes.add(parent);
                if (parent.getParent() != null) homes.add(parent.getParent());
            }
        } catch (Exception ignored) {
            // Explicit app-home and system PATH remain available.
        }
        addHome(homes, System.getProperty("user.dir"));
        return homes.stream().flatMap(home -> java.util.stream.Stream.of(home.resolve("ocr"), home.resolve("app/ocr")))
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isDirectory).findFirst();
    }

    private static void addHome(Set<Path> homes, String value) {
        if (value == null || value.isBlank()) return;
        try { homes.add(Path.of(value).toAbsolutePath().normalize()); }
        catch (RuntimeException ignored) { }
    }

    public static Optional<String> version(Path binary) {
        return commandOutput(binary, "--version").map(value -> value.lines().findFirst().orElse(value).trim());
    }

    private static Optional<String> version(EngineCandidate engine) {
        return commandOutput(engine, List.of("--version"))
                .map(value -> value.lines().findFirst().orElse(value).trim());
    }

    public static Set<String> languages(Path binary) {
        return commandOutput(binary, "--list-langs").map(TesseractOcrConverter::parseLanguages).orElseGet(Set::of);
    }

    private static Set<String> languages(EngineCandidate engine) {
        List<String> arguments = new ArrayList<>(List.of("--list-langs"));
        if (engine.tessdataDirectory() != null) {
            arguments.add("--tessdata-dir");
            arguments.add(engine.tessdataDirectory().toString());
        }
        return commandOutput(engine, arguments).map(TesseractOcrConverter::parseLanguages).orElseGet(Set::of);
    }

    private static Optional<String> commandOutput(Path binary, String argument) {
        return commandOutput(new EngineCandidate(binary, null, null, false), List.of(argument));
    }

    private static Optional<String> commandOutput(EngineCandidate engine, List<String> arguments) {
        Path binary = engine.binary();
        if (binary == null || !isRunnable(binary)) return Optional.empty();
        try {
            List<String> command = new ArrayList<>(List.of(binary.toString()));
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putAll(processEnvironment(engine));
            Process process = builder.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && !output.isBlank() ? Optional.of(output) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Set<String> parseLanguages(String value) {
        Set<String> result = new LinkedHashSet<>();
        value.lines().map(String::trim).filter(LANGUAGE.asMatchPredicate()).forEach(result::add);
        return Set.copyOf(result);
    }

    private static Map<String, String> processEnvironment(Settings settings) {
        return processEnvironment(new EngineCandidate(settings.binary(), settings.tessdataDirectory(),
                settings.libraryDirectory(), settings.bundled()));
    }

    private static Map<String, String> processEnvironment(EngineCandidate engine) {
        Map<String, String> result = new LinkedHashMap<>();
        if (engine.tessdataDirectory() != null) {
            result.put("TESSDATA_PREFIX", engine.tessdataDirectory().toString());
        }
        if (engine.libraryDirectory() != null) {
            String key = isWindows() ? "PATH" : isMac() ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
            String current = System.getenv(key);
            String prefix = engine.libraryDirectory().toString();
            if (isWindows() && engine.binary().getParent() != null) {
                prefix = engine.binary().getParent() + java.io.File.pathSeparator + prefix;
            }
            result.put(key, current == null || current.isBlank()
                    ? prefix : prefix + java.io.File.pathSeparator + current);
        }
        return Map.copyOf(result);
    }

    private static String normalizeLanguages(String value) {
        String configured = value == null || value.isBlank() ? "chi_sim+eng" : value.strip();
        String[] parts = configured.split("\\+");
        if (parts.length == 0) throw new IllegalArgumentException("OCR 语言不能为空");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String part : parts) {
            String language = part.strip();
            if (!LANGUAGE.matcher(language).matches()) throw new IllegalArgumentException("OCR 语言标识无效");
            normalized.add(language);
        }
        return String.join("+", normalized);
    }

    private static int positiveInt(String value, int fallback, int minimum, int maximum) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OCR 整数配置必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    private static double confidence(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            double parsed = Double.parseDouble(value.strip());
            if (!Double.isFinite(parsed) || parsed < 0d || parsed > 1d) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OCR 置信度配置必须在 0 到 1 之间");
        }
    }

    private static long positiveLong(String value, long fallback, long minimum, long maximum) {
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OCR 长整数配置必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    private static boolean enabled(String value) {
        if (value == null) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isRunnable(Path candidate) {
        return Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate));
    }

    private static Path defaultLockDirectory() {
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9_.-]", "_");
        return Path.of(System.getProperty("java.io.tmpdir"), "format-converter-ocr-" + user).toAbsolutePath().normalize();
    }

    private static String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100d); }

    private String replaceExtension(String input, String extension) {
        String file = Path.of(input).getFileName().toString();
        int dot = file.lastIndexOf('.');
        return (dot > 0 ? file.substring(0, dot) : file) + "." + extension;
    }

    public record Settings(Path binary, String languages, String version, Duration timeout,
                           int maxConcurrency, double minimumConfidence, double warningConfidence,
                           long maxImagePixels, Path lockDirectory, Path tessdataDirectory,
                           Path libraryDirectory, boolean bundled) {
        public Settings(Path binary, String languages, String version) {
            this(binary, languages, version, Duration.ofMinutes(2), 1, 0.35d, 0.75d,
                    25_000_000L, defaultLockDirectory(), null, null, false);
        }

        public Settings(Path binary, String languages, String version, Duration timeout,
                        int maxConcurrency, double minimumConfidence, double warningConfidence) {
            this(binary, languages, version, timeout, maxConcurrency, minimumConfidence, warningConfidence,
                    25_000_000L, defaultLockDirectory(), null, null, false);
        }

        public Settings(Path binary, String languages, String version, Duration timeout,
                        int maxConcurrency, double minimumConfidence, double warningConfidence,
                        long maxImagePixels, Path lockDirectory) {
            this(binary, languages, version, timeout, maxConcurrency, minimumConfidence, warningConfidence,
                    maxImagePixels, lockDirectory, null, null, false);
        }

        public Settings {
            if (binary == null) throw new IllegalArgumentException("缺少 Tesseract 路径");
            binary = binary.toAbsolutePath().normalize();
            languages = normalizeLanguages(languages);
            version = version == null || version.isBlank() ? "unknown" : version;
            timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofMinutes(2) : timeout;
            if (maxConcurrency < 1 || maxConcurrency > 32) throw new IllegalArgumentException("OCR 并发必须在 1 到 32 之间");
            if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0d || minimumConfidence > 1d) {
                throw new IllegalArgumentException("OCR 最低置信度必须在 0 到 1 之间");
            }
            if (!Double.isFinite(warningConfidence) || warningConfidence < minimumConfidence || warningConfidence > 1d) {
                throw new IllegalArgumentException("OCR 复核置信度必须在最低置信度到 1 之间");
            }
            if (maxImagePixels < 1L || maxImagePixels > 80_000_000L) {
                throw new IllegalArgumentException("OCR 图片像素上限必须在 1 到 80000000 之间");
            }
            lockDirectory = lockDirectory == null ? defaultLockDirectory() : lockDirectory.toAbsolutePath().normalize();
            tessdataDirectory = tessdataDirectory == null ? null : tessdataDirectory.toAbsolutePath().normalize();
            libraryDirectory = libraryDirectory == null ? null : libraryDirectory.toAbsolutePath().normalize();
        }
    }

    public record Capability(boolean enabled, boolean available, Settings settings, String errorCode,
                             String message, String binaryName, String requestedLanguages,
                             Set<String> availableLanguages, String version) {
        public Capability {
            availableLanguages = availableLanguages == null ? Set.of() : Set.copyOf(availableLanguages);
        }
    }

    record RecognitionResult(List<TextBlock> blocks, double confidence, int wordCount, boolean imageEnhanced,
                             double deskewDegrees, List<String> conflicts, boolean possibleTextOmission, boolean partialRecovery) {
        RecognitionResult(List<TextBlock> blocks, double confidence, int wordCount, boolean imageEnhanced,
                          double deskewDegrees, List<String> conflicts, boolean possibleTextOmission) {
            this(blocks, confidence, wordCount, imageEnhanced, deskewDegrees, conflicts, possibleTextOmission, false);
        }
        RecognitionResult(List<TextBlock> blocks, double confidence, int wordCount, boolean imageEnhanced,
                          double deskewDegrees, List<String> conflicts) {
            this(blocks, confidence, wordCount, imageEnhanced, deskewDegrees, conflicts, false);
        }
        RecognitionResult(List<TextBlock> blocks, double confidence, int wordCount) {
            this(blocks, confidence, wordCount, false, 0, List.of());
        }
        RecognitionResult(List<TextBlock> blocks, double confidence, int wordCount, boolean imageEnhanced) {
            this(blocks, confidence, wordCount, imageEnhanced, 0, List.of());
        }
        RecognitionResult {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    private record ImageDimensions(int width, int height) { }
    private record EngineCandidate(Path binary, Path tessdataDirectory, Path libraryDirectory,
                                   boolean bundled) { }

    private static final class OcrLine {
        private final StringBuilder text = new StringBuilder();
        private final List<OcrWordPixels> words = new ArrayList<>();
        private int left = Integer.MAX_VALUE;
        private int top = Integer.MAX_VALUE;
        private int right;
        private int bottom;

        void add(String word, int x, int y, int width, int height, double confidence) {
            if (!text.isEmpty() && wordSeparator(text.codePointBefore(text.length()), word.codePointAt(0))) text.append(' ');
            text.append(word);
            words.add(new OcrWordPixels(word, x, y, width, height, confidence));
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x + width);
            bottom = Math.max(bottom, y + height);
        }

        String text() { return text.toString(); }
        int width() { return Math.max(1, right - left); }
        int height() { return Math.max(1, bottom - top); }
    }

    static boolean wordSeparator(int previous, int next) {
        return !(eastAsian(previous) && eastAsian(next));
    }

    private static boolean eastAsian(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO || codePoint >= 0x3000 && codePoint <= 0x303f
                || codePoint >= 0xff00 && codePoint <= 0xffef;
    }

    private record OcrWordPixels(String text, int x, int y, int width, int height, double confidence) { }
}
