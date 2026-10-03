package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TesseractWordGeometryTest {
    @TempDir Path temp;

    @Test void preservesIndividualWordGeometryConfidenceAndLanguageSeparators() throws Exception {
        Path tsv = temp.resolve("words.tsv");
        Files.writeString(tsv, "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "5\t1\t1\t1\t1\t1\t10\t10\t20\t10\t95\t中文\n"
                + "5\t1\t1\t1\t1\t2\t35\t10\t20\t10\t80\t合同\n"
                + "5\t1\t1\t1\t1\t3\t80\t10\t40\t10\t45\tEnglish\n"
                + "5\t1\t1\t1\t1\t4\t125\t10\t30\t10\t90\twords\n");
        var result = parse(tsv);
        assertEquals(1, result.blocks().size());
        var line = result.blocks().get(0);
        assertEquals("中文合同 English words", line.text());
        assertEquals(4, line.ocrWords().size());
        assertEquals(new Rect(20, 30, 20, 10), line.ocrWords().get(0).box());
        assertEquals(new Rect(90, 30, 40, 10), line.ocrWords().get(2).box());
        assertEquals(.45, line.ocrWords().get(2).confidence(), .001);
        assertEquals(3, line.pageNumber());
    }

    @Test void clipsPartiallyOutOfBoundsWordsBeforeUsingThemAsMasks() throws Exception {
        Path tsv = temp.resolve("clipped.tsv");
        Files.writeString(tsv, "header\n5\t1\t1\t1\t1\t1\t-5\t95\t20\t20\t95\tclip\n");
        var word = parse(tsv).blocks().get(0).ocrWords().get(0);
        assertEquals(new Rect(10, 115, 15, 5), word.box());
    }

    @Test void boundsWordMetadataEvenWhenEveryWordBelongsToTheSameLine() throws Exception {
        Path tsv = temp.resolve("too-many-words.tsv");
        Files.writeString(tsv, "header\n5\t1\t1\t1\t1\t1\t10\t10\t10\t10\t95\tone\n"
                + "5\t1\t1\t1\t1\t2\t30\t10\t10\t10\t95\ttwo\n");
        ParseLimits base = ParseLimits.defaults();
        ParseLimits oneWord = new ParseLimits(base.maxArchiveBytes(), base.maxExpandedBytes(), base.maxEntryBytes(),
                1, base.maxCompressionRatio(), base.maxPages());
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> parse(tsv, oneWord));
        assertInstanceOf(java.io.IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("OCR 文字对象超过限制"));
    }

    private TesseractOcrConverter.RecognitionResult parse(Path tsv) throws Exception {
        return parse(tsv, ParseLimits.defaults());
    }

    private TesseractOcrConverter.RecognitionResult parse(Path tsv, ParseLimits limits) throws Exception {
        var converter = new TesseractOcrConverter(DocumentFormat.PNG,
                new TesseractOcrConverter.Settings(temp.resolve("unused"), "eng", "test", Duration.ofSeconds(5),
                        1, .2, .8, 25_000_000L, temp.resolve("locks")));
        Class<?> dimensions = Class.forName(TesseractOcrConverter.class.getName() + "$ImageDimensions");
        var constructor = dimensions.getDeclaredConstructor(int.class, int.class); constructor.setAccessible(true);
        var method = TesseractOcrConverter.class.getDeclaredMethod("parseTsv", Path.class, int.class,
                Rect.class, dimensions, ParseLimits.class); method.setAccessible(true);
        return (TesseractOcrConverter.RecognitionResult) method.invoke(converter, tsv, 3,
                new Rect(10, 20, 200, 100), constructor.newInstance(200, 100), limits);
    }
}
