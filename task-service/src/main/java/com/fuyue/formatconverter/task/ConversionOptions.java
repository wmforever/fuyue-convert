package com.fuyue.formatconverter.task;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public record ConversionOptions(PdfCompressionMode compressionMode,
                                String watermarkText,
                                Double watermarkOpacity,
                                Double watermarkAngle,
                                WatermarkPosition watermarkPosition,
                                Boolean watermarkTiled,
                                String watermarkPages,
                                String watermarkColor,
                                String splitPages,
                                Integer imageDpi,
                                String imagePages,
                                ImagePdfPageSize imagePdfPageSize,
                                Double imagePdfMarginMm,
                                String spreadsheetSheets,
                                Boolean spreadsheetFitWidth) {
    public ConversionOptions(PdfCompressionMode compressionMode, String watermarkText,
                             Double watermarkOpacity, Double watermarkAngle, WatermarkPosition watermarkPosition,
                             Boolean watermarkTiled, String watermarkPages, String watermarkColor, String splitPages,
                             Integer imageDpi, String imagePages, ImagePdfPageSize imagePdfPageSize, Double imagePdfMarginMm) {
        this(compressionMode, watermarkText, watermarkOpacity, watermarkAngle, watermarkPosition,
                watermarkTiled, watermarkPages, watermarkColor, splitPages, imageDpi, imagePages,
                imagePdfPageSize, imagePdfMarginMm, null, null);
    }

    public ConversionOptions(PdfCompressionMode compressionMode, String watermarkText,
                             Double watermarkOpacity, Double watermarkAngle, WatermarkPosition watermarkPosition,
                             Boolean watermarkTiled, String watermarkPages, String watermarkColor, String splitPages,
                             Integer imageDpi) {
        this(compressionMode, watermarkText, watermarkOpacity, watermarkAngle, watermarkPosition,
                watermarkTiled, watermarkPages, watermarkColor, splitPages, imageDpi, null, null, null);
    }
    public ConversionOptions(PdfCompressionMode compressionMode, String watermarkText,
                             Double watermarkOpacity, Double watermarkAngle, WatermarkPosition watermarkPosition,
                             Boolean watermarkTiled, String watermarkPages, String watermarkColor, String splitPages) {
        this(compressionMode, watermarkText, watermarkOpacity, watermarkAngle, watermarkPosition,
                watermarkTiled, watermarkPages, watermarkColor, splitPages, null);
    }

    private static final Pattern PAGE_RANGE = Pattern.compile("(?i)all|(?:[1-9]\\d*(?:-[1-9]\\d*)?)(?:,(?:[1-9]\\d*(?:-[1-9]\\d*)?))*");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    public ConversionOptions {
        spreadsheetSheets = spreadsheetSheets == null || spreadsheetSheets.isBlank()
                ? "all" : spreadsheetSheets.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!PAGE_RANGE.matcher(spreadsheetSheets).matches()) {
            throw new IllegalArgumentException("工作表范围格式无效，例如 all、1、1-3、1,3-5");
        }
        validatePageRanges(spreadsheetSheets, "工作表");
        spreadsheetFitWidth = Boolean.TRUE.equals(spreadsheetFitWidth);
        imagePages = imagePages == null || imagePages.isBlank()
                ? "all" : imagePages.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!PAGE_RANGE.matcher(imagePages).matches()) {
            throw new IllegalArgumentException("图片导出页码范围格式无效，例如 all、1、1-3、1,3-5");
        }
        validatePageRanges(imagePages, "图片导出");
        imagePdfPageSize = imagePdfPageSize == null ? ImagePdfPageSize.ORIGINAL : imagePdfPageSize;
        imagePdfMarginMm = imagePdfMarginMm == null ? 10d : imagePdfMarginMm;
        if (!Double.isFinite(imagePdfMarginMm) || imagePdfMarginMm < 0 || imagePdfMarginMm > 50) {
            throw new IllegalArgumentException("图片 PDF 页边距必须在 0 到 50 mm 之间");
        }
        if (imageDpi != null && (imageDpi < 36 || imageDpi > 600)) {
            throw new IllegalArgumentException("图片清晰度必须为 36-600 DPI");
        }
        compressionMode = compressionMode == null ? PdfCompressionMode.LOSSLESS : compressionMode;
        watermarkText = watermarkText == null || watermarkText.isBlank() ? "CONFIDENTIAL" : watermarkText.strip();
        watermarkOpacity = watermarkOpacity == null ? 0.18d : watermarkOpacity;
        watermarkAngle = watermarkAngle == null ? 35d : watermarkAngle;
        watermarkPosition = watermarkPosition == null ? WatermarkPosition.CENTER : watermarkPosition;
        watermarkTiled = watermarkTiled != null && watermarkTiled;
        watermarkPages = watermarkPages == null || watermarkPages.isBlank()
                ? "all" : watermarkPages.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        watermarkColor = watermarkColor == null || watermarkColor.isBlank()
                ? "#969696" : watermarkColor.strip().toUpperCase(Locale.ROOT);
        splitPages = splitPages == null || splitPages.isBlank()
                ? "all" : splitPages.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (watermarkText.codePointCount(0, watermarkText.length()) > 80) {
            throw new IllegalArgumentException("水印文字不能超过 80 个字符");
        }
        if (watermarkText.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("水印文字不能包含控制字符");
        }
        if (!Double.isFinite(watermarkOpacity) || watermarkOpacity < 0.05d || watermarkOpacity > 0.85d) {
            throw new IllegalArgumentException("水印不透明度必须在 0.05 到 0.85 之间");
        }
        if (!Double.isFinite(watermarkAngle) || watermarkAngle < -180d || watermarkAngle > 180d) {
            throw new IllegalArgumentException("水印角度必须在 -180 到 180 度之间");
        }
        if (!PAGE_RANGE.matcher(watermarkPages).matches()) {
            throw new IllegalArgumentException("水印页码范围格式无效，例如 all、1、1-3、1,3-5");
        }
        validatePageRanges(watermarkPages);
        if (!HEX_COLOR.matcher(watermarkColor).matches()) {
            throw new IllegalArgumentException("水印颜色必须是 #RRGGBB 格式");
        }
        if (!PAGE_RANGE.matcher(splitPages).matches()) {
            throw new IllegalArgumentException("拆分页码范围格式无效，例如 all、1、1-3、1,3-5");
        }
        validatePageRanges(splitPages, "拆分");
    }

    public static ConversionOptions defaults() {
        return new ConversionOptions(null, null, null, null, null, null, null, null, null);
    }

    public static ConversionOptions fromRequest(String compressionMode, String watermarkText,
                                                Double watermarkOpacity, Double watermarkAngle,
                                                String watermarkPosition, Boolean watermarkTiled,
                                                String watermarkPages, String watermarkColor) {
        return fromRequest(compressionMode, watermarkText, watermarkOpacity, watermarkAngle,
                watermarkPosition, watermarkTiled, watermarkPages, watermarkColor, null);
    }

    public static ConversionOptions fromRequest(String compressionMode, String watermarkText,
                                                Double watermarkOpacity, Double watermarkAngle,
                                                String watermarkPosition, Boolean watermarkTiled,
                                                String watermarkPages, String watermarkColor,
                                                String splitPages) {
        return fromRequest(compressionMode, watermarkText, watermarkOpacity, watermarkAngle,
                watermarkPosition, watermarkTiled, watermarkPages, watermarkColor, splitPages, null);
    }

    public static ConversionOptions fromRequest(String compressionMode, String watermarkText,
                                                Double watermarkOpacity, Double watermarkAngle,
                                                String watermarkPosition, Boolean watermarkTiled,
                                                String watermarkPages, String watermarkColor,
                                                String splitPages, Integer imageDpi) {
        return fromRequest(compressionMode, watermarkText, watermarkOpacity, watermarkAngle,
                watermarkPosition, watermarkTiled, watermarkPages, watermarkColor, splitPages,
                imageDpi, null, null, null);
    }

    public static ConversionOptions fromRequest(String compressionMode, String watermarkText,
                                                Double watermarkOpacity, Double watermarkAngle,
                                                String watermarkPosition, Boolean watermarkTiled,
                                                String watermarkPages, String watermarkColor,
                                                String splitPages, Integer imageDpi, String imagePages,
                                                String imagePdfPageSize, Double imagePdfMarginMm) {
        return fromRequest(compressionMode, watermarkText, watermarkOpacity, watermarkAngle,
                watermarkPosition, watermarkTiled, watermarkPages, watermarkColor, splitPages,
                imageDpi, imagePages, imagePdfPageSize, imagePdfMarginMm, null, null);
    }

    public static ConversionOptions fromRequest(String compressionMode, String watermarkText,
                                                Double watermarkOpacity, Double watermarkAngle,
                                                String watermarkPosition, Boolean watermarkTiled,
                                                String watermarkPages, String watermarkColor,
                                                String splitPages, Integer imageDpi, String imagePages,
                                                String imagePdfPageSize, Double imagePdfMarginMm,
                                                String spreadsheetSheets, Boolean spreadsheetFitWidth) {
        return new ConversionOptions(PdfCompressionMode.from(compressionMode), watermarkText,
                watermarkOpacity, watermarkAngle, WatermarkPosition.from(watermarkPosition),
                watermarkTiled, watermarkPages, watermarkColor, splitPages, imageDpi, imagePages,
                ImagePdfPageSize.from(imagePdfPageSize), imagePdfMarginMm, spreadsheetSheets, spreadsheetFitWidth);
    }

    public boolean appliesWatermarkToPage(int pageNumber) {
        if (pageNumber < 1) return false;
        if ("all".equals(watermarkPages)) return true;
        for (String part : watermarkPages.split(",")) {
            int dash = part.indexOf('-');
            if (dash < 0) {
                if (Integer.parseInt(part) == pageNumber) return true;
            } else {
                int start = Integer.parseInt(part.substring(0, dash));
                int end = Integer.parseInt(part.substring(dash + 1));
                if (end < start) throw new IllegalArgumentException("水印页码范围起始页不能大于结束页");
                if (pageNumber >= start && pageNumber <= end) return true;
            }
        }
        return false;
    }

    public List<Integer> splitPageNumbers(int totalPages) throws ConversionFailureException {
        return pageNumbers(splitPages, totalPages, "拆分");
    }

    public List<Integer> imagePageNumbers(int totalPages) throws ConversionFailureException {
        return pageNumbers(imagePages, totalPages, "图片导出");
    }

    public List<Integer> spreadsheetSheetNumbers(int totalSheets) throws ConversionFailureException {
        try {
            return pageNumbers(spreadsheetSheets, totalSheets, "工作表");
        } catch (ConversionFailureException error) {
            throw new ConversionFailureException("SPREADSHEET_SHEET_RANGE_INVALID",
                    "工作表范围超出文档：工作簿共 " + totalSheets + " 张工作表，请检查所选序号。");
        }
    }

    private static List<Integer> pageNumbers(String ranges, int totalPages, String label)
            throws ConversionFailureException {
        if (totalPages < 1) throw new ConversionFailureException("PDF_PAGE_RANGE_INVALID", "PDF 没有可" + label + "页面");
        if ("all".equals(ranges)) return IntStream.rangeClosed(1, totalPages).boxed().toList();
        TreeSet<Integer> selected = new TreeSet<>();
        for (String part : ranges.split(",")) {
            int dash = part.indexOf('-');
            int start = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
            int end = Integer.parseInt(dash < 0 ? part : part.substring(dash + 1));
            if (end > totalPages) {
                throw new ConversionFailureException("PDF_PAGE_RANGE_INVALID",
                        label + "页码超出文档范围：第 " + end + " 页，文档共 " + totalPages + " 页");
            }
            for (int page = start; page <= end; page++) selected.add(page);
        }
        return List.copyOf(selected);
    }

    private static void validatePageRanges(String ranges) {
        validatePageRanges(ranges, "水印");
    }

    private static void validatePageRanges(String ranges, String label) {
        if ("all".equals(ranges)) return;
        try {
            for (String part : ranges.split(",")) {
                int dash = part.indexOf('-');
                int start = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
                int end = Integer.parseInt(dash < 0 ? part : part.substring(dash + 1));
                if (end < start) throw new IllegalArgumentException("工作表".equals(label)
                        ? "工作表范围起始序号不能大于结束序号" : label + "页码范围起始页不能大于结束页");
                if (end > 1_000_000) throw new IllegalArgumentException(label + "页码范围超过允许上限");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "页码范围数值过大");
        }
    }
}
