package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Offline native decision evidence; uses private single-pass OCR only for QA. */
public class OcrDeskewDiagnostic {
    public static void main(String[] args) throws Exception {
        Path image = Path.of(args[0]), work = Path.of(args[1]);
        Files.createDirectories(work);
        var capability = TesseractOcrConverter.detectConfigured();
        if (!capability.available() || !capability.settings().bundled()) throw new IllegalStateException("Bundled runtime required");
        var settings = capability.settings();
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        var dimensionMethod = TesseractOcrConverter.class.getDeclaredMethod("dimensions", Path.class);
        dimensionMethod.setAccessible(true);
        var recognize = Arrays.stream(TesseractOcrConverter.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("recognizeOnce")).findFirst().orElseThrow();
        recognize.setAccessible(true);
        var pixels = ImageIO.read(image.toFile());
        Rect physical = new Rect(0, 0, pixels.getWidth(), pixels.getHeight());
        var original = (TesseractOcrConverter.RecognitionResult) recognize.invoke(converter, image, work, 1,
                physical, dimensionMethod.invoke(converter, image), ParseLimits.defaults(), "original", Duration.ofSeconds(120));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("original", original);
        try (var prepared = OcrDeskew.prepare(pixels, 25_000_000, System.nanoTime() + Duration.ofSeconds(120).toNanos())) {
            if (prepared != null) {
                Path corrected = work.resolve("candidate.png"); ImageIO.write(prepared.image(), "png", corrected.toFile());
                var candidate = (TesseractOcrConverter.RecognitionResult) recognize.invoke(converter, corrected, work, 1,
                        new Rect(0, 0, prepared.image().getWidth(), prepared.image().getHeight()),
                        dimensionMethod.invoke(converter, corrected), ParseLimits.defaults(), "candidate", Duration.ofSeconds(120));
                var selected = OcrDeskewSelection.select(original, candidate, prepared, physical,
                        pixels.getWidth(), pixels.getHeight(), settings.minimumConfidence());
                result.put("angle", prepared.degrees()); result.put("candidate", candidate); result.put("selected", selected);
                List<Object> matches = new ArrayList<>();
                var next = candidate.blocks().stream().flatMap(b -> b.ocrWords().stream()).toList();
                for (var old : original.blocks().stream().flatMap(b -> b.ocrWords().stream()).filter(w -> w.confidence() >= .85).toList()) {
                    int index = -1; double best = 0;
                    for (int i = 0; i < next.size(); i++) {
                        var bounds = prepared.originalBounds(next.get(i).box(), 1, 1, physical);
                        double overlap = old.box().intersectionArea(bounds) / (old.box().width() * old.box().height());
                        if (overlap > best) { best = overlap; index = i; }
                    }
                    Map<String,Object> match = new LinkedHashMap<>();match.put("old",old);
                    match.put("overlap", best);match.put("candidateIndex",index);
                    if (index >= 0) {
                        match.put("candidate", next.get(index));
                        Rect mapped = prepared.originalBounds(next.get(index).box(), 1, 1, physical);
                        match.put("mapped", mapped);
                        match.put("candidateCoverage", old.box().intersectionArea(mapped) / (mapped.width()*mapped.height()));
                        match.put("centerContained", old.box().contains(mapped.center(), 0));
                    }
                    matches.add(match);
                }
                result.put("reliableMatches", matches);
            } else result.put("angle", 0);
        } finally { pixels.flush(); }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(work.resolve("diagnostic.json").toFile(), result);
    }
}
