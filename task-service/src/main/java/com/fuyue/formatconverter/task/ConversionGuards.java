package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class ConversionGuards {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PROCESS_LOG_BYTES = 16 * 1024;
    private static final long MAX_IMAGE_PIXELS = 80_000_000L;
    private static final int MAX_EXCEL_CELL_TEXT = 32_767;
    private static final int MAX_EXCEL_COLUMNS = 16_384;

    private ConversionGuards() {}

    static long copyLimited(InputStream in, OutputStream out, long declaredSize, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) continue;
            copied += read;
            if (copied > maxBytes) throw new IOException("上传文件超过限制");
            out.write(buffer, 0, read);
        }
        if (declaredSize >= 0 && copied != declaredSize) throw new IOException("上传文件大小与声明不一致");
        return copied;
    }

    static String runProcess(List<String> command, Path logFile, Duration timeout, String label)
            throws IOException, InterruptedException {
        Files.createDirectories(logFile.getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ProcessOutputCapture capture = ProcessOutputCapture.start(process, command, MAX_PROCESS_LOG_BYTES);
        Set<ProcessHandle> observedDescendants = new HashSet<>();
        boolean finished;
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            finished = false;
            while (System.nanoTime() < deadline) {
                observeDescendants(process, observedDescendants);
                long remainingMillis = Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (process.waitFor(Math.min(200L, remainingMillis), TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }
        } catch (InterruptedException e) {
            terminateProcessTree(process, observedDescendants);
            capture.finish(logFile);
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            terminateProcessTree(process, observedDescendants);
            throw new IOException(label + "超时，输出：" + capture.finish(logFile));
        }
        observeDescendants(process, observedDescendants);
        terminateAlive(observedDescendants);
        String output = capture.finish(logFile);
        if (process.exitValue() != 0) {
            throw new IOException(label + "失败，exit=" + process.exitValue() + "，输出：" + output);
        }
        return output;
    }

    static int requirePdfPageCount(Path pdf, ParseLimits limits) throws IOException {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw new IOException("PDF 没有可转换页面");
            if (pages > limits.maxPages()) throw new IOException("PDF 页数超过限制：" + pages + " > " + limits.maxPages());
            return pages;
        }
    }

    static void requireOutputFile(Path path, ParseLimits limits, String label) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException(label + "未生成有效输出文件");
        long size = Files.size(path);
        if (size > limits.maxExpandedBytes()) {
            throw new IOException(label + "输出超过限制：" + size + " > " + limits.maxExpandedBytes());
        }
    }

    static void requireNonEmptyOutputFile(Path path, ParseLimits limits, String label) throws IOException {
        requireOutputFile(path, limits, label);
        if (Files.size(path) == 0) throw new IOException(label + "未生成有效输出文件");
    }

    static void requireTotalSize(List<Path> files, ParseLimits limits, String label) throws IOException {
        long total = 0;
        for (Path file : files) {
            long size = Files.size(file);
            if (size > limits.maxEntryBytes()) {
                throw new IOException(label + "单页输出超过限制：" + size + " > " + limits.maxEntryBytes());
            }
            total += size;
            if (total > limits.maxExpandedBytes()) {
                throw new IOException(label + "总输出超过限制：" + total + " > " + limits.maxExpandedBytes());
            }
        }
    }

    static void requireImageBounds(Path image, ParseLimits limits) throws IOException {
        ImageSize size = readImageSize(image);
        long pixels;
        try {
            pixels = Math.multiplyExact((long) size.width(), (long) size.height());
        } catch (ArithmeticException e) {
            throw new IOException("图片像素尺寸无效", e);
        }
        long allowed = allowedImagePixels(limits);
        if (pixels > allowed) throw new IOException("图片像素超过限制：" + pixels + " > " + allowed);
    }

    static void requireRenderBounds(double widthPoints, double heightPoints, double dpi,
                                    ParseLimits limits) throws IOException {
        if (!Double.isFinite(widthPoints) || !Double.isFinite(heightPoints) ||
                widthPoints <= 0 || heightPoints <= 0 || !Double.isFinite(dpi) || dpi <= 0) {
            throw new IOException("文档页面尺寸无效");
        }
        double pixels = Math.ceil(widthPoints * dpi / 72d) * Math.ceil(heightPoints * dpi / 72d);
        long allowed = allowedImagePixels(limits);
        if (!Double.isFinite(pixels) || pixels > allowed) {
            throw new IOException("页面渲染像素超过限制：" + Math.round(pixels) + " > " + allowed);
        }
    }

    static int requireSpreadsheetRow(int rowIndex, ParseLimits limits) throws IOException {
        if (rowIndex >= limits.maxEntries()) {
            throw new IOException("表格行数超过限制：" + (rowIndex + 1) + " > " + limits.maxEntries());
        }
        return rowIndex;
    }

    static void requireSpreadsheetColumn(int columnIndex) throws IOException {
        if (columnIndex >= MAX_EXCEL_COLUMNS) {
            throw new IOException("表格列数超过 Excel 限制：" + (columnIndex + 1) + " > " + MAX_EXCEL_COLUMNS);
        }
    }

    static void requireCellText(String value) throws IOException {
        if (value != null && value.length() > MAX_EXCEL_CELL_TEXT) {
            throw new IOException("单元格文本超过 Excel 限制：" + value.length() + " > " + MAX_EXCEL_CELL_TEXT);
        }
    }

    static void requireSpreadsheetCellCount(long cells, ParseLimits limits) throws IOException {
        long maxCells = Math.max(limits.maxEntries(), Math.min(250_000L, Math.max(1L, limits.maxExpandedBytes() / 64L)));
        if (cells > maxCells) throw new IOException("表格单元格数量超过限制：" + cells + " > " + maxCells);
    }

    private static ImageSize readImageSize(Path image) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(image.toFile())) {
            if (stream == null) throw new IOException("无法读取图片尺寸");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("不支持的图片格式");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new ImageSize(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private static long allowedImagePixels(ParseLimits limits) {
        return Math.min(MAX_IMAGE_PIXELS, Math.max(1L, limits.maxExpandedBytes() / 4L));
    }

    static void observeDescendants(Process process, Set<ProcessHandle> observed) {
        process.toHandle().descendants().forEach(observed::add);
    }

    static void terminateProcessTree(Process process, Set<ProcessHandle> observed) {
        observeDescendants(process, observed);
        terminateAlive(observed);
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        terminateAlive(observed);
    }

    static void terminateAlive(Set<ProcessHandle> handles) {
        handles.stream().filter(ProcessHandle::isAlive)
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroyForcibly);
    }

    private record ImageSize(int width, int height) {}
}
