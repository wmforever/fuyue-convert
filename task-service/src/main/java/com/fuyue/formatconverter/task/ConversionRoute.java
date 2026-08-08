package com.fuyue.formatconverter.task;

public record ConversionRoute(String id, DocumentFormat sourceFormat, DocumentFormat targetFormat,
                              String sourceLabel, String targetLabel, String inputExtension,
                              String outputExtension, String description, RouteStatus status) {
    public static ConversionRoute of(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description) {
        return new ConversionRoute(sourceFormat.id() + "-to-" + targetFormat.id(), sourceFormat, targetFormat,
                sourceFormat.label(), targetFormat.label(), "." + sourceFormat.extension(),
                "." + targetFormat.extension(), description, RouteStatus.AVAILABLE);
    }

    public static ConversionRoute planned(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description) {
        return new ConversionRoute(sourceFormat.id() + "-to-" + targetFormat.id(), sourceFormat, targetFormat,
                sourceFormat.label(), targetFormat.label(), "." + sourceFormat.extension(),
                "." + targetFormat.extension(), description, RouteStatus.PLANNED);
    }
}
