package dev.workflowguard.adapters.burp;

import burp.api.montoya.http.Http;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.workflowguard.core.HttpRequestText;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.ports.HttpExchangeEvidence;
import dev.workflowguard.ports.RequestSender;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

final class MontoyaRequestSender implements RequestSender {
    static final int MAXIMUM_RESPONSE_CHARACTERS = 1_048_576;

    private final Http http;
    private final BiFunction<String, String, HttpRequest> requestFactory;

    MontoyaRequestSender(Http http) {
        this(http, MontoyaRequestSender::requestFor);
    }

    MontoyaRequestSender(
            Http http,
            BiFunction<String, String, HttpRequest> requestFactory
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
    }

    @Override
    public HttpExchangeEvidence send(WorkflowStep step) {
        Objects.requireNonNull(step, "step");
        HttpRequest request = requestFactory.apply(step.url(), step.rawRequest());
        HttpRequestResponse exchange = http.sendRequest(request);
        if (!exchange.hasResponse()) {
            return HttpExchangeEvidence.noResponse();
        }

        HttpResponse response = Objects.requireNonNull(
                exchange.response(),
                "Montoya reported a response but returned null"
        );
        String responseBody = Objects.requireNonNull(
                response.bodyToString(),
                "Montoya returned a null response body"
        );
        if (responseBody.length() > MAXIMUM_RESPONSE_CHARACTERS) {
            throw new IllegalStateException(
                    "Response body exceeds WorkflowGuard's 1 MiB evidence limit"
            );
        }
        String rawResponse = Objects.requireNonNull(
                response.toString(),
                "Montoya returned a null raw response"
        );
        if (rawResponse.length() > MAXIMUM_RESPONSE_CHARACTERS) {
            throw new IllegalStateException(
                    "Raw response exceeds WorkflowGuard's 1 MiB evidence limit"
            );
        }
        Map<String, List<String>> responseHeaders = response.headers() == null
                ? Map.of()
                : response.headers().stream().collect(Collectors.groupingBy(
                        header -> header.name().toLowerCase(Locale.ROOT),
                        LinkedHashMap::new,
                        Collectors.mapping(header -> header.value(), Collectors.toList())
                ));
        return new HttpExchangeEvidence(
                true,
                Optional.of((int) response.statusCode()),
                rawResponse,
                responseBody,
                responseHeaders
        );
    }

    private static HttpRequest requestFor(String url, String rawRequest) {
        return HttpRequest.httpRequest(
                serviceFor(url),
                HttpRequestText.normalizeLineEndings(rawRequest)
        );
    }

    private static HttpService serviceFor(String url) {
        URI uri = URI.create(url);
        String scheme = Objects.requireNonNull(uri.getScheme(), "Request URL has no scheme");
        boolean secure = switch (scheme.toLowerCase()) {
            case "https" -> true;
            case "http" -> false;
            default -> throw new IllegalArgumentException("Unsupported request URL scheme: " + scheme);
        };
        String host = Objects.requireNonNull(uri.getHost(), "Request URL has no host");
        int port = uri.getPort();
        if (port < 0) {
            port = secure ? 443 : 80;
        }
        return HttpService.httpService(host, port, secure);
    }
}
