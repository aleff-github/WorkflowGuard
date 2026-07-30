package dev.workflowguard.adapters.burp;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.workflowguard.application.CapturedRequest;

import java.util.Objects;

final class BurpRequestMapper {
    CapturedRequest map(HttpRequestResponse exchange) {
        Objects.requireNonNull(exchange, "exchange");
        HttpRequest request = Objects.requireNonNull(exchange.request(), "exchange.request");
        return new CapturedRequest(
                request.method(),
                request.url(),
                request.toString(),
                request.isInScope()
        );
    }
}
