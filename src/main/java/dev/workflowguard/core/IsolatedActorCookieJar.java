package dev.workflowguard.core;

import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.ports.HttpExchangeEvidence;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class IsolatedActorCookieJar {
    private final ActorDefinition actor;
    private final Map<String, LinkedHashMap<String, String>> cookiesByOrigin =
            new LinkedHashMap<>();
    private final java.util.Set<String> seededOrigins = new java.util.HashSet<>();
    private String credentialOrigin;

    public IsolatedActorCookieJar(ActorDefinition actor) {
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    public IsolatedActorCookieJar(ActorDefinition actor, String credentialOrigin) {
        this(actor);
        if (actor.hasConfiguredCredentials()) {
            this.credentialOrigin = origin(
                    Objects.requireNonNull(credentialOrigin, "credentialOrigin")
            );
        }
    }

    public ActorDefinition actor() {
        return actor;
    }

    public String apply(String rawRequest, String url) {
        Objects.requireNonNull(rawRequest, "rawRequest");
        String origin = origin(url);
        LinkedHashMap<String, String> cookies = cookiesByOrigin.computeIfAbsent(
                origin,
                ignored -> new LinkedHashMap<>()
        );
        boolean configuredCredentialsAllowed = configuredCredentialsAllowed(origin);
        if (seededOrigins.add(origin)) {
            if (!actor.initialCookieHeader().isBlank() && configuredCredentialsAllowed) {
                cookies.putAll(parseCookieHeader(actor.initialCookieHeader()));
            } else if (!actor.hasConfiguredCredentials() && actor.seedFromCapturedRequest()) {
                requestCookieHeaders(rawRequest).forEach(
                        header -> cookies.putAll(parseCookieHeader(header))
                );
            }
        }
        String withCookies = replaceCookieHeader(rawRequest, cookies);
        return replaceAuthorizationHeader(withCookies, configuredCredentialsAllowed);
    }

    public void capture(String url, HttpExchangeEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.hasResponse()) {
            return;
        }
        String origin = origin(url);
        LinkedHashMap<String, String> cookies = cookiesByOrigin.computeIfAbsent(
                origin,
                ignored -> new LinkedHashMap<>()
        );
        seededOrigins.add(origin);
        evidence.responseHeaders().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Set-Cookie")) {
                values.forEach(value -> applySetCookie(cookies, value));
            }
        });
    }

    public Map<String, String> snapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        cookiesByOrigin.forEach((origin, cookies) -> cookies.forEach(
                (name, value) -> snapshot.put(origin + " :: " + name, value)
        ));
        return Map.copyOf(snapshot);
    }

    private void applySetCookie(Map<String, String> cookies, String setCookie) {
        String[] parts = setCookie.split(";", -1);
        int equals = parts[0].indexOf('=');
        if (equals <= 0) {
            return;
        }
        String name = parts[0].substring(0, equals).trim();
        String value = parts[0].substring(equals + 1).trim();
        boolean delete = value.isEmpty();
        for (int index = 1; index < parts.length; index++) {
            String attribute = parts[index].trim().toLowerCase(Locale.ROOT);
            if (attribute.equals("max-age=0") || attribute.startsWith("max-age=-")) {
                delete = true;
            }
        }
        if (delete) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

    private Map<String, String> parseCookieHeader(String header) {
        Map<String, String> cookies = new LinkedHashMap<>();
        String normalized = header.trim();
        if (normalized.regionMatches(true, 0, "Cookie:", 0, "Cookie:".length())) {
            normalized = normalized.substring("Cookie:".length()).trim();
        }
        for (String pair : normalized.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (!name.isEmpty()) {
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    private List<String> requestCookieHeaders(String rawRequest) {
        List<String> values = new ArrayList<>();
        String head = requestHead(rawRequest);
        head.lines().skip(1).forEach(line -> {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Cookie")) {
                values.add(line.substring(colon + 1).trim());
            }
        });
        return values;
    }

    private String replaceCookieHeader(
            String rawRequest,
            LinkedHashMap<String, String> cookies
    ) {
        RequestParts parts = split(rawRequest);
        String newline = parts.head().contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(parts.head().split("\\r?\\n", -1)));
        lines.removeIf(line -> {
            int colon = line.indexOf(':');
            return colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Cookie");
        });
        if (!cookies.isEmpty()) {
            String cookieHeader = cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("; "));
            lines.add(Math.min(1, lines.size()), "Cookie: " + cookieHeader);
        }
        return String.join(newline, lines) + parts.separator() + parts.body();
    }

    private String replaceAuthorizationHeader(
            String rawRequest,
            boolean configuredCredentialsAllowed
    ) {
        String configured = actor.initialAuthorizationHeader();
        if (configured.isBlank()
                && actor.id().equals(ActorDefinition.DEFAULT_ID)
                && actor.seedFromCapturedRequest()) {
            return rawRequest;
        }

        String normalized = configured;
        if (normalized.regionMatches(
                true,
                0,
                "Authorization:",
                0,
                "Authorization:".length()
        )) {
            normalized = normalized.substring("Authorization:".length()).trim();
        }

        RequestParts parts = split(rawRequest);
        String newline = parts.head().contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(parts.head().split("\\r?\\n", -1)));
        lines.removeIf(line -> {
            int colon = line.indexOf(':');
            return colon > 0
                    && line.substring(0, colon).trim().equalsIgnoreCase("Authorization");
        });
        if (!normalized.isBlank() && configuredCredentialsAllowed) {
            lines.add(Math.min(1, lines.size()), "Authorization: " + normalized);
        }
        return String.join(newline, lines) + parts.separator() + parts.body();
    }

    private boolean configuredCredentialsAllowed(String origin) {
        if (!actor.hasConfiguredCredentials()) {
            return false;
        }
        if (credentialOrigin == null) {
            credentialOrigin = origin;
        }
        return credentialOrigin.equals(origin);
    }

    private String requestHead(String rawRequest) {
        return split(rawRequest).head();
    }

    private RequestParts split(String request) {
        int separator = request.indexOf("\r\n\r\n");
        if (separator >= 0) {
            return new RequestParts(
                    request.substring(0, separator),
                    "\r\n\r\n",
                    request.substring(separator + 4)
            );
        }
        separator = request.indexOf("\n\n");
        if (separator >= 0) {
            return new RequestParts(
                    request.substring(0, separator),
                    "\n\n",
                    request.substring(separator + 2)
            );
        }
        return new RequestParts(request, "", "");
    }

    private String origin(String url) {
        URI uri = URI.create(url);
        String normalized = HttpRequestText.origin(url);
        int port = uri.getPort();
        if (port < 0) {
            port = uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        }
        return normalized + (normalized.endsWith(":" + port) ? "" : ":" + port);
    }

    private record RequestParts(String head, String separator, String body) {
    }
}
