package com.fuyue.formatconverter.web;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp) {
}

