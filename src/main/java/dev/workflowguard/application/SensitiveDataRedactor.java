package dev.workflowguard.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {
    public static final String REDACTED = "<redacted>";
    public static final String URL_REDACTED = "%3Credacted%3E";
    private static final String SENSITIVE_PARAMETER_NAME =
            "password|passwd|secret|token|api[_-]?key|session|e[_-]?mail|"
                    + "phone|telephone|mobile|ssn|social[_-]?security|"
                    + "credit[_-]?card|card[_-]?number|date[_-]?of[_-]?birth|"
                    + "dob|tax[_-]?id";
    private static final Pattern FORM_SECRET = Pattern.compile(
            "(?i)(^|&)([^=&]*(?:" + SENSITIVE_PARAMETER_NAME + ")[^=&]*)=([^&]*)"
    );
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&])([^=&\\s]*(?:" + SENSITIVE_PARAMETER_NAME
                    + ")[^=&\\s]*)=([^&\\s]*)"
    );
    private static final Pattern EMAIL_VALUE = Pattern.compile(
            "(?i)^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}"
                    + "[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"
    );
    private static final Pattern EMAIL_IN_TEXT = Pattern.compile(
            "(?i)[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}"
    );
    private static final Pattern BEARER_IN_TEXT = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"
    );
    private static final Pattern HTTP_URL_IN_TEXT = Pattern.compile(
            "(?i)https?://[^\\s]+"
    );
    private static final Pattern SENSITIVE_PATH_CONTEXT = Pattern.compile(
            "(?i)(reset|token|invite|verify|verification|activate|activation|"
                    + "session|authorization|password|secret|key)"
    );
    private static final Pattern OPAQUE_PATH_SEGMENT = Pattern.compile(
            "[A-Za-z0-9._~-]{16,}"
    );
    private final ObjectMapper objectMapper;

    public SensitiveDataRedactor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String redactHttpMessage(String message) {
        return redactHttpMessage(message, false);
    }

    public String redactEvidenceHttpMessage(String message) {
        return redactHttpMessage(message, true);
    }

    private String redactHttpMessage(String message, boolean evidenceMode) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String separator = message.contains("\r\n\r\n") ? "\r\n\r\n"
                : message.contains("\n\n") ? "\n\n" : "";
        String head = separator.isEmpty()
                ? message
                : message.substring(0, message.indexOf(separator));
        String body = separator.isEmpty()
                ? ""
                : message.substring(message.indexOf(separator) + separator.length());
        List<String> headers = new ArrayList<>();
        String contentType = "";
        int lineIndex = 0;
        for (String line : head.split("\\r?\\n", -1)) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String headerName = line.substring(0, colon).trim();
                if (headerName.equalsIgnoreCase("Content-Type")) {
                    contentType = line.substring(colon + 1).trim();
                }
                if (evidenceMode) {
                    lineIndex++;
                    continue;
                }
                headers.add(isSensitiveHeader(headerName)
                        ? line.substring(0, colon + 1) + " " + REDACTED
                        : line);
            } else {
                headers.add(lineIndex == 0
                        ? evidenceMode
                        ? redactEvidenceStartLine(line)
                        : redactQuery(line)
                        : line);
            }
            lineIndex++;
        }
        String newline = head.contains("\r\n") ? "\r\n" : "\n";
        return String.join(newline, headers)
                + (separator.isEmpty()
                ? ""
                : separator + (evidenceMode
                ? body.isEmpty() ? "" : REDACTED
                : redactPortableBody(body, contentType)));
    }

    public String redactQuery(String value) {
        if (value == null) {
            return null;
        }
        String redacted = QUERY_SECRET.matcher(value).replaceAll(match ->
                match.group(1) + match.group(2) + "=" + URL_REDACTED
        );
        return redactSensitivePathSegments(redacted, false);
    }

    public String redactEvidenceUrl(String value) {
        if (value == null) {
            return null;
        }
        return redactSensitivePathSegments(value, true);
    }

    public String redactFreeText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = BEARER_IN_TEXT.matcher(
                EMAIL_IN_TEXT.matcher(value).replaceAll(REDACTED)
        ).replaceAll(REDACTED);
        return HTTP_URL_IN_TEXT.matcher(redacted).replaceAll(URL_REDACTED);
    }

    public String redactNamedValue(String name, String value) {
        return isSensitiveName(name) ? REDACTED : value;
    }

    public boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("apikey")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("email")
                || normalized.contains("phonenumber")
                || normalized.contains("telephone")
                || normalized.contains("mobile")
                || normalized.equals("ssn")
                || normalized.contains("socialsecurity")
                || normalized.contains("creditcard")
                || normalized.contains("cardnumber")
                || normalized.contains("dateofbirth")
                || normalized.equals("dob")
                || normalized.contains("taxid")
                || normalized.contains("firstname")
                || normalized.contains("lastname")
                || normalized.contains("fullname")
                || normalized.equals("username")
                || normalized.equals("postaladdress");
    }

    public String redactPayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        if (looksLikeXml(payload)) {
            return REDACTED;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            redactJson(root);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ignored) {
            return FORM_SECRET.matcher(payload)
                    .replaceAll(match -> match.group(1)
                            + match.group(2)
                            + "="
                            + REDACTED);
        }
    }

    private String redactPortableBody(String body, String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("xml")
                || normalized.contains("multipart/")
                || looksLikeXml(body)) {
            return body.isEmpty() ? "" : REDACTED;
        }
        return redactPayload(body);
    }

    private String redactEvidenceStartLine(String line) {
        if (line.regionMatches(true, 0, "HTTP/", 0, "HTTP/".length())) {
            String[] parts = line.split("\\s+", 3);
            return parts.length >= 2 ? parts[0] + " " + parts[1] : REDACTED;
        }
        String[] parts = line.split("\\s+", 3);
        if (parts.length != 3) {
            return REDACTED;
        }
        return parts[0] + " " + redactEvidenceUrl(parts[1]) + " " + parts[2];
    }

    private String redactSensitivePathSegments(String value, boolean evidenceMode) {
        String prefix = "";
        String suffix = "";
        String target = value;
        String[] requestLine = value.split("\\s+", 3);
        if (requestLine.length == 3
                && requestLine[2].toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
            prefix = requestLine[0] + " ";
            target = requestLine[1];
            suffix = " " + requestLine[2];
        }

        try {
            URI uri = URI.create(target);
            String rawPath = uri.getRawPath();
            if (rawPath == null) {
                return value;
            }
            String redactedPath = redactPath(rawPath, evidenceMode);
            String redactedQuery = uri.getRawQuery() == null
                    ? ""
                    : evidenceMode
                    ? "?" + URL_REDACTED
                    : "?" + QUERY_SECRET.matcher("?" + uri.getRawQuery())
                            .replaceAll(match ->
                                    match.group(1) + match.group(2) + "=" + URL_REDACTED
                            ).substring(1);
            String rebuilt;
            if (uri.isAbsolute()) {
                rebuilt = uri.getScheme() + "://" + uri.getRawAuthority()
                        + redactedPath + redactedQuery;
            } else {
                rebuilt = redactedPath + redactedQuery;
            }
            return prefix + rebuilt + suffix;
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String redactPath(String rawPath, boolean evidenceMode) {
        String[] segments = rawPath.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String decoded = segments[index];
            String previous = index == 0 ? "" : segments[index - 1];
            boolean sensitiveContext = SENSITIVE_PATH_CONTEXT.matcher(previous).matches();
            boolean numericIdentifier = decoded.matches("\\d+");
            boolean opaque = OPAQUE_PATH_SEGMENT.matcher(decoded).matches();
            if (!decoded.isEmpty()
                    && (sensitiveContext
                    || opaque
                    || (evidenceMode && numericIdentifier))) {
                segments[index] = URL_REDACTED;
            }
        }
        return String.join("/", segments);
    }

    private boolean looksLikeXml(String value) {
        String trimmed = value.stripLeading();
        return trimmed.startsWith("<") && trimmed.contains(">");
    }

    private void redactJson(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (isSensitiveName(name) || isSensitiveValue(value)) {
                    object.put(name, REDACTED);
                } else {
                    redactJson(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (isSensitiveValue(value)) {
                    array.set(index, array.textNode(REDACTED));
                } else {
                    redactJson(value);
                }
            }
        }
    }

    private boolean isSensitiveValue(JsonNode node) {
        return node != null
                && node.isTextual()
                && EMAIL_VALUE.matcher(node.asText().trim()).matches();
    }

    private boolean isSensitiveHeader(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return isSensitiveName(normalized)
                || normalized.equals("authorization")
                || normalized.equals("proxy-authorization")
                || normalized.equals("cookie")
                || normalized.equals("set-cookie")
                || normalized.equals("x-api-key");
    }
}
