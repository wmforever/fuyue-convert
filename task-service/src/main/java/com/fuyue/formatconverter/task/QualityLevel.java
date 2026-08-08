package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.annotation.JsonValue;

public enum QualityLevel {
    STABLE("stable"),
    BETA("beta"),
    EXPERIMENTAL("experimental"),
    PLANNED("planned");

    private final String id;

    QualityLevel(String id) { this.id = id; }

    @JsonValue public String id() { return id; }
}
