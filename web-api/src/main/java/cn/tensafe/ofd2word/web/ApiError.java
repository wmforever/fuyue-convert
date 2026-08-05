package cn.tensafe.ofd2word.web;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp) {
}

