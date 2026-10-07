package com.fuyue.formatconverter.task;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Deterministic shaded inputs; medians are diagnostic, never timing assertions. */
public class OcrEnhancementBenchmark {
    private static BufferedImage sample(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int gray = 110 + x * 70 / width + y * 30 / height;
            if (x % 37 > 7 && x % 37 < 23 && y % 41 > 13 && y % 41 < 26) gray -= 25;
            int alpha = (x + y) % 17 == 0 ? 180 : 255;
            image.setRGB(x, y, (alpha << 24) | (gray << 16) | (gray << 8) | gray);
        }
        return image;
    }

    public static void main(String[] args) throws Exception {
        int[][] sizes = {{1, 83}, {83, 1}, {31, 29}, {137, 91}, {257, 259}, {1400, 1000}, {3000, 4000}};
        for (int[] size : sizes) {
            BufferedImage source = sample(size[0], size[1]);
            for (int index = 0; index < 3; index++) {
                BufferedImage output = OcrContrastEnhancer.enhance(source);
                if (output != null) output.flush();
            }
            long[] times = new long[7];
            BufferedImage output = null;
            for (int index = 0; index < times.length; index++) {
                if (output != null) output.flush();
                long started = System.nanoTime();
                output = OcrContrastEnhancer.enhance(source);
                times[index] = System.nanoTime() - started;
            }
            Arrays.sort(times);
            String hash = "null";
            if (output != null) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) {
                    digest.update((byte) output.getRaster().getSample(x, y, 0));
                }
                hash = HexFormat.of().formatHex(digest.digest());
                output.flush();
            }
            System.out.printf(java.util.Locale.ROOT, "%dx%d %.3f %s%n",
                    size[0], size[1], times[times.length / 2] / 1e6, hash);
            source.flush();
        }
    }
}
