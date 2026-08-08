package com.fuyue.formatconverter.task;

import java.util.List;

public record ConversionRoute(String id, DocumentFormat sourceFormat, DocumentFormat targetFormat,
                              String sourceLabel, String targetLabel, String inputExtension,
                              String outputExtension, String description, RouteStatus status,
                              QualityLevel qualityLevel, ConversionStrategy strategy,
                              List<String> requires, List<String> limitations) {
    public ConversionRoute {
        requires = requires == null ? List.of() : List.copyOf(requires);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public static ConversionRoute of(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description) {
        return of(sourceFormat, targetFormat, description, QualityLevel.BETA, ConversionStrategy.FIDELITY,
                List.of(), List.of());
    }

    public static ConversionRoute of(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description,
                                     QualityLevel qualityLevel, ConversionStrategy strategy,
                                     List<String> requires, List<String> limitations) {
        return new ConversionRoute(sourceFormat.id() + "-to-" + targetFormat.id(), sourceFormat, targetFormat,
                sourceFormat.label(), targetFormat.label(), "." + sourceFormat.extension(),
                "." + targetFormat.extension(), description, RouteStatus.AVAILABLE, qualityLevel, strategy,
                requires, limitations);
    }

    public static ConversionRoute planned(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description) {
        return new ConversionRoute(sourceFormat.id() + "-to-" + targetFormat.id(), sourceFormat, targetFormat,
                sourceFormat.label(), targetFormat.label(), "." + sourceFormat.extension(),
                "." + targetFormat.extension(), description, RouteStatus.PLANNED, QualityLevel.PLANNED,
                ConversionStrategy.PLANNED, List.of(), List.of("尚未开放执行"));
    }
}
