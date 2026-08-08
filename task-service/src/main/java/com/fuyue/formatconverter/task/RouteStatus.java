package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RouteStatus {
    AVAILABLE("available"),
    PLANNED("planned");

    private final String id;

    RouteStatus(String id) { this.id = id; }

    @JsonValue public String id() { return id; }
}
