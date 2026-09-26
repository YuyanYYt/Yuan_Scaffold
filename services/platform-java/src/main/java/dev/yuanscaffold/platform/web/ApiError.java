package dev.yuanscaffold.platform.web;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp) { }
