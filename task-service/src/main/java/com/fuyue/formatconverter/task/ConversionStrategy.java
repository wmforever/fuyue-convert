package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ConversionStrategy {
    EDITABLE("editable"),
    FIDELITY("fidelity"),
    DATA("data"),
    EXTRACTION("extraction"),
    CONTENT("content"),
    COMPATIBILITY("compatibility"),
    PLANNED("planned");

    private final String id;

    ConversionStrategy(String id) { this.id = id; }

    @JsonValue public String id() { return id; }
}
