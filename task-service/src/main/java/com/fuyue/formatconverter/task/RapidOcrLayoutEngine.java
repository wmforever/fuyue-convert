package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit local-only experimental engine. No runtime model downloads or silent engine fallback. */
final class RapidOcrLayoutEngine {
    static final String ENABLED = "formatconverter.ocr.rapid";
    private final Path python, script, models;
    private final TesseractOcrConverter.Settings limits;

    RapidOcrLayoutEngine(Path python, Path script, Path models, TesseractOcrConverter.Settings limits) {
        this.python = python.toAbsolutePath().normalize(); this.script = script.toAbsolutePath().normalize();
        this.models = models.toAbsolutePath().normalize(); this.limits = limits;
        if (!Files.isExecutable(this.python) || !Files.isRegularFile(this.script) || !Files.isDirectory(this.models)) {
            throw new IllegalArgumentException("RapidOCR 本地 Python、脚本或模型目录不可用");
        }
    }

    static RapidOcrLayoutEngine configured(TesseractOcrConverter.Settings limits) {
        if (!Boolean.getBoolean(ENABLED)) return null;
        return new RapidOcrLayoutEngine(property("formatconverter.ocr.rapid.python"),
                property("formatconverter.ocr.rapid.script"), property("formatconverter.ocr.rapid.models"), limits);
    }
    private static Path property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少本地 OCR 配置：" + name);
        return Path.of(value);
    }

    TesseractOcrConverter.RecognitionResult recognize(Path image, Path work, int page, Rect physical,
            ParseLimits parseLimits) throws Exception {
        Files.createDirectories(work);
        ConversionGuards.requireImageBounds(image, parseLimits);
        int width, height;
        try (var input = ImageIO.createImageInputStream(image.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ConversionFailureException("OCR_IMAGE_INVALID", "无法读取 OCR 图片");
            var reader = readers.next();
            try { reader.setInput(input); width = reader.getWidth(0); height = reader.getHeight(0); }
            finally { reader.dispose(); }
        }
        if ((long) width * height > limits.maxImagePixels())
            throw new ConversionFailureException("OCR_RESOURCE_EXHAUSTED", "OCR 图片超过像素上限");
        Path output = work.resolve("rapid-page-%04d.json".formatted(page));
        Files.deleteIfExists(output); // A failed subprocess cannot reuse stale recognition.
        long start = System.nanoTime();
        try (var permit = OcrProcessPermit.acquire(limits.lockDirectory(), limits.maxConcurrency(), limits.timeout())) {
            Duration remaining = limits.timeout().minusNanos(System.nanoTime() - start);
            if (remaining.isNegative() || remaining.isZero()) throw new ConversionFailureException("OCR_TIMEOUT", "RapidOCR 超过页面识别时限");
            ConversionGuards.runProcess(List.of(python.toString(), script.toString(), "--input", image.toAbsolutePath().toString(),
                    "--output", output.toAbsolutePath().toString(), "--models", models.toString()),
                    Map.of("OMP_NUM_THREADS", "2", "OPENBLAS_NUM_THREADS", "2"),
                    work.resolve("rapid-page-%04d.log".formatted(page)), remaining, "RapidOCR 第 " + page + " 页");
        } catch (ExternalProcessException failure) {
            throw new ConversionFailureException(failure.reason() == ExternalProcessException.Reason.TIMEOUT
                    ? "OCR_TIMEOUT" : failure.reason() == ExternalProcessException.Reason.START_FAILED
                    ? "OCR_ENGINE_UNAVAILABLE" : "OCR_ENGINE_FAILED", "本地 RapidOCR 执行失败：" + failure.reason());
        }
        ConversionGuards.requireOutputFile(output, parseLimits, "RapidOCR JSON");
        return parse(output, page, physical, width, height, parseLimits.maxEntries());
    }

    static TesseractOcrConverter.RecognitionResult parse(Path json, int page, Rect physical,
            int width, int height, int maxEntries) throws Exception {
        JsonNode root;
        try { root = new ObjectMapper().readTree(json.toFile()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new ConversionFailureException("OCR_OUTPUT_INVALID", "RapidOCR 输出 JSON 格式无效");
        }
        if (root == null || !root.isArray() || root.size() > maxEntries)
            throw new ConversionFailureException("OCR_OUTPUT_INVALID", "RapidOCR 输出结构或条目数无效");
        List<TextBlock> blocks = new ArrayList<>(); List<String> conflicts = new ArrayList<>();
        double confidence = 0; int skipped = 0, uncertain = 0;
        for (JsonNode item : root) {
            JsonNode quad = item.get("box"), textNode = item.get("txt"), scoreNode = item.get("score");
            if (quad == null || !quad.isArray() || quad.size() != 4 || textNode == null || !textNode.isTextual()
                    || scoreNode == null || !scoreNode.isNumber()) throw new ConversionFailureException("OCR_OUTPUT_INVALID", "RapidOCR 字段无效");
            String text = textNode.textValue(); double score = scoreNode.doubleValue();
            if (text.length() > 8192 || !Double.isFinite(score) || score < 0 || score > 1)
                throw new ConversionFailureException("OCR_OUTPUT_INVALID", "RapidOCR 文字长度或置信度无效");
            double x = Double.POSITIVE_INFINITY, y = x, right = Double.NEGATIVE_INFINITY, bottom = right;
            double[] xx = new double[4], yy = new double[4];
            for (int i = 0; i < 4; i++) {
                var point = quad.get(i);
                if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber())
                    throw new ConversionFailureException("OCR_OUTPUT_INVALID", "RapidOCR 坐标无效");
                xx[i] = point.get(0).doubleValue(); yy[i] = point.get(1).doubleValue();
                if (!Double.isFinite(xx[i]) || !Double.isFinite(yy[i]) || xx[i] < 0 || xx[i] > width || yy[i] < 0 || yy[i] > height)
                    throw new ConversionFailureException("OCR_OUTPUT_INVALID", "RapidOCR 坐标超出原图");
                x = Math.min(x, xx[i]); right = Math.max(right, xx[i]); y = Math.min(y, yy[i]); bottom = Math.max(bottom, yy[i]);
            }
            if (text.isBlank()) continue;
            // RapidOCR's line scores are not Tesseract's word scores. Small
            // diagram annotations with uncertain predictions must stay as ink.
            if (score < .85d) { uncertain++; continue; }
            double angle = Math.toDegrees(Math.atan2(yy[1] - yy[0], xx[1] - xx[0]));
            if (right <= x || bottom <= y || Math.abs(angle) > 2 || Math.abs(xx[3] - xx[0]) > (right - x) * .15) {
                skipped++; continue; // Retain unsupported rotated ink in the original scan.
            }
            Rect box = new Rect(physical.x() + x * physical.width() / width, physical.y() + y * physical.height() / height,
                    (right - x) * physical.width() / width, (bottom - y) * physical.height() / height);
            blocks.add(new TextBlock("rapid-p" + page + "-" + blocks.size(), page, box, text, box.y() + box.height() * .85,
                    FontStyle.defaults(), blocks.size() + 1, 0, 0, List.of(), Transform2D.IDENTITY,
                    List.of(new TextBlock.OcrWord(box, text, score))));
            confidence += score;
        }
        if (skipped > 0) conflicts.add(skipped + " 个旋转或异常区域保留原扫描；该部分不作为可编辑文字叠加。");
        if (uncertain > 0) conflicts.add(uncertain + " 个模型置信度低于 0.85 的区域保留原扫描；该部分不作为可编辑文字叠加。");
        return new TesseractOcrConverter.RecognitionResult(blocks, blocks.isEmpty() ? 0 : confidence / blocks.size(), blocks.size(),
                false, 0, conflicts, skipped > 0 || uncertain > 0);
    }

    List<ConversionWarning> warnings(TesseractOcrConverter.RecognitionResult result, int page, String scope) {
        List<ConversionWarning> warnings = new ArrayList<>();
        warnings.add(ConversionWarning.withConfidence(WarningCode.OCR_APPLIED, scope
                + "使用本地 RapidOCR / PP-OCRv6；模型置信度不等于正确率，文字及数字必须人工复核。", page, null, result.confidence()));
        for (String conflict : result.conflicts()) warnings.add(ConversionWarning.of(WarningCode.OCR_REGION_RETAINED_AS_IMAGE, scope + conflict, page));
        return List.copyOf(warnings);
    }
}
