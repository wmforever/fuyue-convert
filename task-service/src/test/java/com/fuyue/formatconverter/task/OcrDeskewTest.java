package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrDeskewTest {
    @TempDir Path temp;
    private static final String[] LINES = {
            "Invoice OCR 2026 total amount 12345", "Please verify customer number 80421",
            "Document conversion preserves editable text", "Local processing keeps source files private",
            "Table fields include date quantity and price", "Scan quality improves with straight text lines",
            "The final amount is 9876 and invoice is 2026", "Review each converted paragraph before sharing"};

    @Test void detectsBothDirectionsAndInverseMapsExpandedCanvasWithoutChangingSource() throws Exception {
        for (int angle : new int[]{-6, 6}) {
            BufferedImage source = page(angle);
            int[] original = source.getRGB(0, 0, 1400, 1000, null, 0, 1400);
            try (var prepared = OcrDeskew.prepare(source, 25_000_000, System.nanoTime() + Duration.ofSeconds(3).toNanos())) {
                assertNotNull(prepared);
                assertEquals(angle, prepared.degrees(), .5);
                assertTrue(prepared.image().getWidth() > source.getWidth());
                assertTrue(prepared.image().getHeight() > source.getHeight());
                var forward = prepared.inverse().createInverse();
                var center = forward.transform(new java.awt.geom.Point2D.Double(300, 400), null);
                Rect corrected = new Rect(center.getX() * .1 - 2, center.getY() * .1 - .5, 4, 1);
                Rect mapped = prepared.originalBounds(corrected, .1, .1, new Rect(10, 20, 140, 100));
                assertEquals(40, mapped.center().x(), 1e-8);
                assertEquals(60, mapped.center().y(), 1e-8);
                assertTrue(mapped.width() >= 4);
                assertArrayEquals(original, source.getRGB(0, 0, 1400, 1000, null, 0, 1400));
            }
            source.flush();
        }
    }

    @Test void boundsProjectionRowsForInkAtTheBottomRightOfWideImages() {
        var image = new BufferedImage(700, 170, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 700, 170);
        graphics.setColor(Color.BLACK); graphics.fillRect(500, 155, 180, 12);
        graphics.dispose();
        assertDoesNotThrow(() -> OcrDeskew.detect(image, System.nanoTime() + Duration.ofSeconds(3).toNanos()));
        image.flush();
    }

    @Test void skipsUprightBlankSparseGridOutOfRangeAndExpiredBudget() throws Exception {
        BufferedImage blank = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        var graphics = blank.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 1000); graphics.dispose();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        assertEquals(0, OcrDeskew.detect(blank, deadline));
        for (int index = 0; index < 20; index++) blank.setRGB(20 + index * 25, 40 + index * 13, Color.BLACK.getRGB());
        assertEquals(0, OcrDeskew.detect(blank, deadline));
        graphics = blank.createGraphics(); graphics.setColor(Color.BLACK);
        for (int x = 100; x < 1300; x += 150) graphics.drawLine(x, 100, x, 900);
        for (int y = 100; y < 900; y += 100) graphics.drawLine(100, y, 1300, y);
        graphics.dispose();
        assertEquals(0, OcrDeskew.detect(blank, deadline)); blank.flush();
        for (int angle : new int[]{0, 15}) {
            BufferedImage source = page(angle);
            assertEquals(0, OcrDeskew.detect(source, System.nanoTime() + Duration.ofSeconds(3).toNanos()));
            assertNull(OcrDeskew.prepare(source, 25_000_000, System.nanoTime() - 1));
            source.flush();
        }
        BufferedImage tilted = page(6);
        assertNull(OcrDeskew.prepare(tilted, 1_400_000, System.nanoTime() + Duration.ofSeconds(3).toNanos()),
                "expanded input must remain within the OCR pixel budget");
        tilted.flush();
    }

    @Test void preservesReliableNumericConflictAndRejectsLowConfidenceOrLostReliableWords() {
        Rect box = new Rect(10, 10, 30, 10);
        var original = result("80424", .95, box);
        var candidate = result("80421", .97, box);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), 6, new AffineTransform())) {
            var selected = OcrDeskewSelection.select(original, candidate, prepared, new Rect(0, 0, 100, 100), 100, 100, .35);
            assertEquals("80424", selected.blocks().get(0).text());
            assertEquals(6, selected.deskewDegrees());
            assertFalse(selected.conflicts().isEmpty());
            assertEquals(box, selected.blocks().get(0).ocrWords().get(0).box());
            assertSame(original, OcrDeskewSelection.select(original, result("80421", .2, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("80421", .97, new Rect(60, 60, 30, 10)),
                    prepared, new Rect(0, 0, 100, 100), 100, 100, .35));
        }
    }

    @Test void bundledEngineRecoversMissingLinesWithSourceGeometryAndReviewWarnings() throws Exception {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.available() && capability.settings().bundled(), "requires pinned bundled OCR runtime");
        for (int angle : new int[]{0, -6, 6}) {
            BufferedImage source = page(angle); Path path = temp.resolve("page-" + angle + ".png");
            ImageIO.write(source, "png", path.toFile()); source.flush();
            byte[] original = Files.readAllBytes(path);
            var converter = new TesseractOcrConverter(DocumentFormat.PNG, capability.settings());
            var selected = converter.recognizeLayoutResult(path, temp.resolve("work-" + angle), 1,
                    new Rect(10, 20, 140, 100), ParseLimits.defaults(), true);
            String text = selected.blocks().stream().map(TextBlock::text).reduce("", (a, b) -> a + b).replaceAll("\\s", "").toLowerCase();
            assertTrue(text.contains("revieweachconvertedparagraphbeforesharing"), text);
            assertTrue(text.contains("9876"), text);
            assertEquals(angle == 0, selected.deskewDegrees() == 0);
            if (angle != 0) assertTrue(converter.warningsFor(selected, 1, "page").stream().anyMatch(w -> w.code() == WarningCode.OCR_DESKEW_APPLIED));
            for (var line : selected.blocks()) for (var word : line.ocrWords()) {
                assertTrue(new Rect(10, 20, 140, 100).contains(word.box().center(), 0));
            }
            assertArrayEquals(original, Files.readAllBytes(path));
            try (var files = Files.list(temp.resolve("work-" + angle))) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("tesseract-deskew-") && file.toString().endsWith(".png")));
            }
        }
    }

    @Test void optionalDeskewFailureAndTimeoutKeepOriginalAndDeleteTemporaryInput() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        for (String behavior : List.of("exit 23", "sleep 8")) {
            var converter = fake("eng", behavior, "", Duration.ofSeconds(2));
            Path image = writePage(6), work = temp.resolve("failed-" + behavior.replace(' ', '-'));
            long started = System.nanoTime();
            var result = converter.recognizeLayoutResult(image, work, 1,
                    new Rect(0, 0, 140, 100), ParseLimits.defaults(), true);
            assertEquals("usable original 2026", result.blocks().get(0).text());
            assertEquals(0, result.deskewDegrees());
            assertTrue(Files.exists(work.resolve("deskew-attempted")), "the optional process must actually run");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5,
                    "retry cannot receive a fresh page timeout");
            try (var files = Files.list(work)) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("tesseract-deskew-")
                        && file.toString().endsWith(".png")));
            }
        }
    }

    @Test void doesNotRetryLayoutVerticalAnisotropicOrNearlyExpiredPages() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        Path image = writePage(6);
        var ordinary = fake("eng", "exit 23", "", Duration.ofSeconds(3));
        var vertical = fake("eng+chi_sim_vert", "exit 23", "", Duration.ofSeconds(3));
        var nearlyExpired = fake("eng", "exit 23", "sleep 2.2", Duration.ofSeconds(3));
        Path layout = temp.resolve("layout"), anisotropic = temp.resolve("anisotropic"),
                verticalWork = temp.resolve("vertical"), expired = temp.resolve("expired");
        ordinary.recognizeLayoutResult(image, layout, 1, new Rect(0, 0, 140, 100), ParseLimits.defaults());
        ordinary.recognizeLayoutResult(image, anisotropic, 1, new Rect(0, 0, 280, 100), ParseLimits.defaults(), true);
        vertical.recognizeLayoutResult(image, verticalWork, 1, new Rect(0, 0, 140, 100), ParseLimits.defaults(), true);
        nearlyExpired.recognizeLayoutResult(image, expired, 1, new Rect(0, 0, 140, 100), ParseLimits.defaults(), true);
        for (Path work : List.of(layout, anisotropic, verticalWork, expired)) {
            assertFalse(Files.exists(work.resolve("deskew-attempted")), work.toString());
        }
    }

    @Test void staggeredUprightColumnFragmentsDoNotTriggerGlobalDeskew() throws Exception {
        staggeredColumnGuard(false, false, false);
    }

    @Test void alreadyOrderedStaggeredFragmentsDoNotTriggerGlobalDeskew() throws Exception {
        staggeredColumnGuard(true, false, false);
    }

    @Test void multipleColumnFallbackStillRetriesDeskew() throws Exception {
        staggeredColumnGuard(true, true, true);
    }

    private void staggeredColumnGuard(boolean rowOrder, boolean numericCell, boolean expectDeskew) throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        var image = new BufferedImage(2400,1500,BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0,0,2400,1500);
        graphics.setColor(Color.BLACK);
        var tsv = new StringBuilder("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n");
        String[] labels = {"Harbor","Meadow","Copper"};
        int block = 0;
        for (int column=0;column<3;column++) for (int outer=0;outer<(rowOrder?4:3);outer++)
                for (int inner=0;inner<(rowOrder?3:4);inner++) {
            int part=rowOrder?inner:outer, row=rowOrder?outer:inner;
            int x=120+column*720+part*80, y=250+row*290+column*35;
            String text = part==0 ? labels[column] : part==1 ? "keeps" : numericCell ? "12" : "record0"+(row+1);
            graphics.fillRect(x,y,60,20);
            tsv.append("5\t1\t").append(++block).append("\t1\t1\t1\t").append(x).append('\t')
                .append(y).append("\t60\t20\t90\t").append(text).append('\n');
        }
        graphics.dispose();
        assertNotEquals(0,OcrDeskew.detect(image,System.nanoTime()+Duration.ofSeconds(3).toNanos()),
            "the staggered ink must reproduce the false global projection angle");
        Path input=temp.resolve("staggered.png"); ImageIO.write(image,"png",input.toFile()); image.flush();
        byte[] original=Files.readAllBytes(input);
        Path raw=temp.resolve("original-columns.tsv"); Files.writeString(raw,tsv);
        Path binary=temp.resolve("column-engine.sh");
        Files.writeString(binary,"#!/bin/sh\ncase \"$1\" in *tesseract-deskew-*)\n"
            +"touch \"$(dirname \"$2\")/deskew-attempted\"\nexit 23\n;; esac\n"
            +"cp '"+raw.toString().replace("'","'\\''")+"' \"$2.tsv\"\n");
        assertTrue(binary.toFile().setExecutable(true));
        var converter=new TesseractOcrConverter(DocumentFormat.PNG,new TesseractOcrConverter.Settings(
            binary,"eng","fake",Duration.ofSeconds(5),1,.35,.75,25_000_000,temp.resolve("locks")));
        Path work=temp.resolve("column-work");
        var result=converter.recognizeLayoutResult(input,work,1,new Rect(0,0,2400,1500),ParseLimits.defaults(),true);
        assertEquals(expectDeskew,Files.exists(work.resolve("deskew-attempted")),
            "valid fragment geometry must skip deskew regardless of engine order; fallback must still retry");
        assertEquals(0,result.deskewDegrees());
        assertEquals(36,result.wordCount());
        var ordered=OcrReadingOrder.arrange(result.blocks(),2400,System.nanoTime()+Duration.ofSeconds(1).toNanos());
        var expected=new java.util.ArrayList<String>();
        for (String label:labels) for (int row=1;row<=4;row++) expected.add(label+" keeps record0"+row);
        if (!numericCell) {
            assertTrue(ordered.fragmentedColumnsValidated());
            assertEquals(expected,ordered.lines());
            assertEquals(!rowOrder,ordered.adjusted(), "text-order change is not a geometry-validation signal");
        } else {
            assertTrue(ordered.multipleColumns());
            assertFalse(ordered.adjusted());
            assertFalse(ordered.fragmentedColumnsValidated());
        }
        assertArrayEquals(original,Files.readAllBytes(input));
    }

    @Test void rejectsEmptyNumericMismatchAndPreservesDecimalBoundariesWithExplicitConflict() {
        Rect box = new Rect(10, 10, 30, 10);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), 6, new AffineTransform())) {
            var original = result("USD804.24", .96, box);
            var selected = OcrDeskewSelection.select(original, result("USD80424", .98, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35);
            assertEquals("USD804.24", selected.blocks().get(0).text());
            assertEquals(1, selected.conflicts().size());
            for (String numeric : List.of("USD-804.24", "USD８０４．２４")) {
                var sourceNumber = result(numeric, .96, box);
                var preserved = OcrDeskewSelection.select(sourceNumber, result("USD80424", .98, box), prepared,
                        new Rect(0, 0, 100, 100), 100, 100, .35);
                assertEquals(numeric, preserved.blocks().get(0).text());
                assertEquals(1, preserved.conflicts().size());
            }
            assertSame(original, OcrDeskewSelection.select(original, selected, prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35, System.nanoTime() - 1));
            var dense = new TesseractOcrConverter.RecognitionResult(original.blocks(), .96, 2000);
            assertSame(dense, OcrDeskewSelection.select(dense, dense, prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("USDpaid", .98, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original,
                    new TesseractOcrConverter.RecognitionResult(List.of(), .99, 0), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("USD804.24", .90, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
        }
    }

    @Test void matchesInflatedBoxesOnlyThroughUniqueContainedReliableExactText() {
        Rect large = new Rect(10, 10, 100, 60), small = new Rect(20, 30, 10, 8);
        var original = result("客户", .96, large);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(150, 100, BufferedImage.TYPE_INT_RGB), 6, new AffineTransform())) {
            Rect page = new Rect(0, 0, 150, 100);
            var adopted = OcrDeskewSelection.select(original, result("客户", .98, small), prepared, page, 150, 100, .35);
            assertEquals(6, adopted.deskewDegrees());
            assertEquals("客户", adopted.blocks().get(0).text());
            assertSame(original, OcrDeskewSelection.select(original, result("客产", .98, small), prepared, page, 150, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("客户", .80, small), prepared, page, 150, 100, .35));
            var weakBlocks = new java.util.ArrayList<>(result("客户", .80, small).blocks());
            for (int index = 0; index < 4; index++) weakBlocks.addAll(
                    result("RELIABLE", .99, new Rect(20 + index * 25, 80, 20, 8)).blocks());
            var highAverageWeakToken = new TesseractOcrConverter.RecognitionResult(weakBlocks, .952, 5);
            assertSame(original, OcrDeskewSelection.select(original, highAverageWeakToken, prepared, page, 150, 100, .35),
                    "a high page average cannot make the exact fallback token reliable");
            assertSame(original, OcrDeskewSelection.select(original, result("客户", .98, new Rect(105, 65, 10, 8)),
                    prepared, page, 150, 100, .35));
            var twice = new TesseractOcrConverter.RecognitionResult(
                    java.util.stream.Stream.concat(result("客户", .98, small).blocks().stream(),
                            result("客户", .98, new Rect(70, 40, 10, 8)).blocks().stream()).toList(), .98, 2);
            assertSame(original, OcrDeskewSelection.select(original, twice, prepared, page, 150, 100, .35),
                    "two equally plausible exact tokens cannot replace a single reliable occurrence");
            var repeat = new TesseractOcrConverter.RecognitionResult(
                    java.util.stream.Stream.concat(original.blocks().stream(), original.blocks().stream()).toList(), .96, 2);
            assertSame(repeat, OcrDeskewSelection.select(repeat, result("客户", .98, small), prepared, page, 150, 100, .35),
                    "two original occurrences cannot share one exact fallback match");
            var number = result("80424", .96, large);
            assertSame(number, OcrDeskewSelection.select(number, result("80421", .98, small), prepared, page, 150, 100, .35),
                    "the fallback must never manufacture a numeric correspondence from known truth");
        }
    }

    @Test void neverDropsTheDecimalPointFromAReliableLeadingDecimalWithALabel() {
        Rect box = new Rect(10, 10, 30, 10);
        var original = result("Balance:.95", .96, box);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                6, new AffineTransform())) {
            var selected = OcrDeskewSelection.select(original, result("Balance:0.95", .98, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35);
            assertEquals("Balance:.95", selected.blocks().get(0).text(),
                    "A numeric conflict may retain the original, never fabricate Balance:95");
        }
    }

    @Test void neverFusesSeparateReliableNumericWordsIntoOneAmount() {
        Rect left = new Rect(10, 10, 10, 10), right = new Rect(30, 10, 10, 10);
        Rect box = left.union(right);
        var block = new TextBlock("line", 1, box, "12 34", box.bottom(), null, 1, 0, 0, List.of(),
                Transform2D.IDENTITY, List.of(new TextBlock.OcrWord(left, "12", .96),
                new TextBlock.OcrWord(right, "34", .96)));
        var original = new TesseractOcrConverter.RecognitionResult(List.of(block), .96, 2);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                6, new AffineTransform())) {
            var selected = OcrDeskewSelection.select(original, result("1234", .98, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35);
            assertSame(original, selected, "Two reliable amounts must not become a new concatenated amount");
        }
    }

    @Test void preservesCompleteReliableNumericTokensAcrossFormatsOrRejectsTheCandidate() {
        Rect box = new Rect(10, 10, 30, 10);
        String[][] pairs = {{"Balance:-.95", "Balance:-0.95"}, {"Balance:．９５", "Balance:０．９５"},
                {"Balance:－．９５", "Balance:-0.95"}, {"EUR,95", "EUR0,95"},
                {"USD.95", "USD0.95"}, {"$0.95", "$95"}, {"Rate:.95%", "Rate:0.95%"},
                {"Rate:95%", "Rate:95"}, {"Amount:(.95)", "Amount:.95"},
                {"Amount:1,234.50", "Amount:1234.50"}, {"Amount:1.234,50", "Amount:1234.50"},
                {"Amount:1'234.50", "Amount:1'235.50"}, {"Amount:1\u202f234.50", "Amount:1234.50"},
                {"A:.95B:1.25", "A:0.95B:1.25"}, {"ID:00424", "ID:80424"},
                {"Balance:0.95", "Balance:.95"}, {".95", "0.95"}, {"-.95", "-0.95"},
                {"Balance:٫٩٥", "Balance:٠٫٩٥"}, {"Amount:95USD", "Amount:.95USD"}};
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                6, new AffineTransform())) {
            assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
                var original = result(pair[0], .96, box);
                var selected = OcrDeskewSelection.select(original, result(pair[1], .98, box), prepared,
                        new Rect(0, 0, 100, 100), 100, 100, .35);
                assertEquals(pair[0], selected.blocks().get(0).text(),
                        "Preserve the complete original lexeme or reject: " + pair[0] + " -> " + pair[1]);
                if (selected != original) assertFalse(selected.conflicts().isEmpty());
            }));
        }
    }

    @Test void unchangedNumericTokenDoesNotBecomeANumericConflictFromOtherMappedWords() {
        Rect box = new Rect(10, 10, 30, 10);
        var source = new TextBlock("line", 1, box, "Amount 12345", box.bottom(), null, 1, 0, 0, List.of(),
                Transform2D.IDENTITY, List.of(new TextBlock.OcrWord(box, "Amount", .96),
                new TextBlock.OcrWord(box, "12345", .96)));
        var original = new TesseractOcrConverter.RecognitionResult(List.of(source), .96, 2);
        var recovered = new java.util.ArrayList<>(result("12345", .98, box).blocks());
        recovered.addAll(result("additional recovered prose", .98, new Rect(50, 10, 40, 10)).blocks());
        var candidate = new TesseractOcrConverter.RecognitionResult(recovered, .98, 2);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                6, new AffineTransform())) {
            var selected = OcrDeskewSelection.select(original, candidate, prepared, new Rect(0, 0, 100, 100),
                    100, 100, .35);
            assertEquals(6, selected.deskewDegrees());
            assertEquals("12345", selected.blocks().get(0).text());
            assertTrue(selected.conflicts().stream().allMatch(message -> message.startsWith("文字原结果")),
                    "The existing text conflict remains explicit, but unchanged 12345 is not rewritten");
        }
    }

    @Test void rejectsLosingSeparateReliableDecimalCurrencyPercentOrAccountingContext() {
        Rect box = new Rect(10, 10, 30, 10);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                6, new AffineTransform())) {
            for (String context : List.of(".", "$", "USD", "usd", "EUR", "JPY", "%", "(", "－")) {
                var source = new TextBlock("line", 1, box, context + " 95", box.bottom(), null, 1,
                        0, 0, List.of(), Transform2D.IDENTITY, List.of(new TextBlock.OcrWord(box, context, .96),
                        new TextBlock.OcrWord(box, "95", .96)));
                var original = new TesseractOcrConverter.RecognitionResult(List.of(source), .96, 2);
                var blocks = new java.util.ArrayList<>(result("95", .98, box).blocks());
                blocks.addAll(result("additional recovered prose", .98, new Rect(50, 10, 40, 10)).blocks());
                var candidate = new TesseractOcrConverter.RecognitionResult(blocks, .98, 2);
                assertSame(original, OcrDeskewSelection.select(original, candidate, prepared,
                        new Rect(0, 0, 100, 100), 100, 100, .35),
                        "Do not lose separately tokenized numeric context: " + context);
            }
        }
    }

    private Path writePage(int angle) throws Exception {
        BufferedImage source = page(angle);
        Path image = Files.createTempFile(temp, "deskew-input-", ".png");
        ImageIO.write(source, "png", image.toFile()); source.flush(); return image;
    }

    private TesseractOcrConverter fake(String languages, String behavior, String initialBehavior, Duration timeout) throws Exception {
        Path binary = Files.createTempFile(temp, "fake-deskew-", ".sh");
        String script = "#!/bin/sh\nbase=\"$2\"\ncase \"$1\" in *tesseract-deskew-*)\n"
                + "touch \"$(dirname \"$2\")/deskew-attempted\"\n" + behavior + "\n;; *) " + initialBehavior + "\n;; esac\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t50\\t50\\t400\\t100\\t95\\tusable original 2026\\n' >> \"${base}.tsv\"\n";
        Files.writeString(binary, script); assertTrue(binary.toFile().setExecutable(true));
        return new TesseractOcrConverter(DocumentFormat.PNG, new TesseractOcrConverter.Settings(binary, languages, "fake",
                timeout, 1, .35, .75, 25_000_000, temp.resolve("locks")));
    }

    private TesseractOcrConverter.RecognitionResult result(String text, double confidence, Rect box) {
        var block = new TextBlock("line", 1, box, text, box.bottom(), null, 1, 0, 0, List.of(), Transform2D.IDENTITY,
                List.of(new TextBlock.OcrWord(box, text, confidence)));
        return new TesseractOcrConverter.RecognitionResult(List.of(block), confidence, 1);
    }

    private BufferedImage page(int angle) throws Exception {
        BufferedImage image = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 1000);
        graphics.rotate(Math.toRadians(angle), 700, 500);
        graphics.setColor(Color.BLACK); graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        try (var font = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            assertNotNull(font); graphics.setFont(Font.createFont(Font.TRUETYPE_FONT, font).deriveFont(32f));
        }
        for (int index = 0; index < LINES.length; index++) graphics.drawString(LINES[index], 150, 125 + index * 100);
        graphics.dispose(); return image;
    }
}
