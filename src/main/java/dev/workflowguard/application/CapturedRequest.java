package dev.workflowguard.application;

import java.util.Locale;
import java.util.Objects;

public record CapturedRequest(
        String method,
        String url,
        String rawRequest,
        boolean inScope
) {
    public CapturedRequest {
        method = requireText(method, "method").toUpperCase(Locale.ROOT);
        url = requireText(url, "url");
        rawRequest = requireText(rawRequest, "rawRequest");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
