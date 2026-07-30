package dev.workflowguard.ports;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Map;

public record HttpExchangeEvidence(
        boolean hasResponse,
        Optional<Integer> statusCode,
        String rawResponse,
        String responseBody,
        Map<String, List<String>> responseHeaders
) {
    public HttpExchangeEvidence {
        statusCode = Objects.requireNonNull(statusCode, "statusCode");
        Objects.requireNonNull(rawResponse, "rawResponse");
        Objects.requireNonNull(responseBody, "responseBody");
        responseHeaders = Map.copyOf(
                Objects.requireNonNull(responseHeaders, "responseHeaders")
        );
        if (!hasResponse && statusCode.isPresent()) {
            throw new IllegalArgumentException("A missing response cannot have a status code");
        }
    }

    public HttpExchangeEvidence(
            boolean hasResponse,
            Optional<Integer> statusCode,
            String rawResponse,
            String responseBody
    ) {
        this(hasResponse, statusCode, rawResponse, responseBody, Map.of());
    }

    public HttpExchangeEvidence(
            boolean hasResponse,
            Optional<Integer> statusCode,
            String rawResponse
    ) {
        this(hasResponse, statusCode, rawResponse, rawResponse, Map.of());
    }

    public static HttpExchangeEvidence noResponse() {
        return new HttpExchangeEvidence(false, Optional.empty(), "", "", Map.of());
    }
}
