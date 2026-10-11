package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.LineElement;
import com.fuyue.formatconverter.model.Rect;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterGridDetectorTest {
    @Test
    void recoversLongRulesFromRasterTable() {
        BufferedImage image = new BufferedImage(320, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(220, 235, 205));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        for (int x : new int[]{0, 64, 128, 192, 256, 319}) graphics.fillRect(x, 20, 2, 220);
        for (int y = 20; y <= 240; y += 40) graphics.fillRect(0, y, 320, 2);
        graphics.dispose();

        List<LineElement> lines = RasterGridDetector.detect(image, new Rect(0, 0, 160, 130), 3,
                System.nanoTime() + 2_000_000_000L);

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(line -> line.pageNumber() == 3));
        assertTrue(lines.stream().anyMatch(line -> line.horizontal(.01)));
        assertTrue(lines.stream().anyMatch(line -> line.vertical(.01)));
    }
}
