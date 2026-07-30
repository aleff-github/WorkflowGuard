package dev.workflowguard.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Utilities for raw HTTP/1 request text.
 */
public final class HttpRequestText {
    private static final Pattern METHOD_TOKEN = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+"
    );
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$\\{[A-Za-z][A-Za-z0-9_.-]*}"
    );

    private HttpRequestText() {
    }

    /**
     * Normalizes all line endings to the CRLF delimiter required by HTTP/1.x.
     */
    public static String normalizeLineEndings(String rawRequest) {
        Objects.requireNonNull(rawRequest, "rawRequest");
        return rawRequest
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\r\n");
    }

    /**
     * Returns the method from the actual HTTP request line.
     */
    public static String requestMethod(String rawRequest) {
        RequestLine requestLine = requestLine(rawRequest);
        if (!METHOD_TOKEN.matcher(requestLine.method()).matches()) {
            throw new IllegalArgumentException("The raw request contains an invalid HTTP method");
        }
        return requestLine.method().toUpperCase(Locale.ROOT);
    }

    /**
     * Resolves the effective URL that Burp will address from the captured service and
     * the rendered request target. The raw Host header must describe the same origin.
     */
    public static String effectiveUrl(String capturedUrl, String rawRequest) {
        URI base = httpUri(capturedUrl, "Captured URL");
        validateHostHeader(base, rawRequest);

        RequestLine requestLine = requestLine(rawRequest);
        String target = requestLine.target();
        if ("*".equals(target)) {
            return origin(base) + "/";
        }
        if (target.startsWith("/")) {
            URI effective = httpUri(origin(base) + target, "Rendered request target");
            if (effective.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "HTTP request targets must not contain a fragment"
                );
            }
            return effective.toASCIIString();
        }

        URI absolute = httpUri(target, "Absolute request target");
        if (!sameOrigin(base, absolute)) {
            throw new IllegalArgumentException(
                    "The absolute request target does not match the captured origin"
            );
        }
        if (absolute.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "HTTP request targets must not contain a fragment"
            );
        }
        return absolute.toASCIIString();
    }

    /**
     * Validates a request template without resolving its dynamic values.
     */
    public static void validateTemplate(
            String expectedMethod,
            String capturedUrl,
            String rawRequest
    ) {
        Objects.requireNonNull(expectedMethod, "expectedMethod");
        String actualMethod = requestMethod(rawRequest);
        if (!actualMethod.equals(expectedMethod.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "The raw request method " + actualMethod
                            + " does not match the modeled method "
                            + expectedMethod.toUpperCase(Locale.ROOT)
            );
        }
        String safeTemplate = PLACEHOLDER.matcher(rawRequest).replaceAll("workflowguard");
        effectiveUrl(capturedUrl, safeTemplate);
    }

    /**
     * Returns a normalized scheme, host, and effective port.
     */
    public static String origin(String url) {
        return origin(httpUri(url, "URL"));
    }

    private static RequestLine requestLine(String rawRequest) {
        Objects.requireNonNull(rawRequest, "rawRequest");
        String normalized = normalizeLineEndings(rawRequest);
        int end = normalized.indexOf("\r\n");
        String line = end >= 0 ? normalized.substring(0, end) : normalized;
        String[] parts = line.trim().split("\\s+", 3);
        if (parts.length != 3
                || parts[0].isBlank()
                || parts[1].isBlank()
                || !parts[2].toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
            throw new IllegalArgumentException(
                    "The raw request must start with a valid HTTP request line"
            );
        }
        return new RequestLine(parts[0], parts[1]);
    }

    private static void validateHostHeader(URI base, String rawRequest) {
        List<String> hostValues = new ArrayList<>();
        String normalized = normalizeLineEndings(rawRequest);
        int body = normalized.indexOf("\r\n\r\n");
        String head = body >= 0 ? normalized.substring(0, body) : normalized;
        head.lines().skip(1).forEach(line -> {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Host")) {
                hostValues.add(line.substring(colon + 1).trim());
            }
        });
        if (hostValues.size() > 1) {
            throw new IllegalArgumentException(
                    "The raw request must not contain multiple Host headers"
            );
        }
        if (hostValues.isEmpty()) {
            return;
        }
        String hostValue = hostValues.getFirst();
        if (hostValue.isBlank()
                || hostValue.chars().anyMatch(Character::isWhitespace)
                || hostValue.indexOf('@') >= 0) {
            throw new IllegalArgumentException("The raw request contains an invalid Host header");
        }
        URI authority = httpUri(
                base.getScheme() + "://" + hostValue + "/",
                "Host header"
        );
        if (!sameOrigin(base, authority)) {
            throw new IllegalArgumentException(
                    "The raw Host header does not match the captured request origin"
            );
        }
    }

    private static URI httpUri(String value, String label) {
        Objects.requireNonNull(value, label);
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException(label + " must use HTTP or HTTPS");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(label + " must contain a host");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(label + " must not contain user information");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith(label)) {
                throw exception;
            }
            throw new IllegalArgumentException(label + " is not a valid URL", exception);
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static String origin(URI uri) {
        String host = uri.getHost().contains(":")
                ? "[" + uri.getHost() + "]"
                : uri.getHost().toLowerCase(Locale.ROOT);
        int port = effectivePort(uri);
        boolean defaultPort = (uri.getScheme().equalsIgnoreCase("https") && port == 443)
                || (uri.getScheme().equalsIgnoreCase("http") && port == 80);
        return uri.getScheme().toLowerCase(Locale.ROOT)
                + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private record RequestLine(String method, String target) {
    }
}
