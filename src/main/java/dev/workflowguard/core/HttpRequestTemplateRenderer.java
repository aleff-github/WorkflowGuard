package dev.workflowguard.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class HttpRequestTemplateRenderer {
    private final VariableResolver variableResolver;

    public HttpRequestTemplateRenderer(VariableResolver variableResolver) {
        this.variableResolver = Objects.requireNonNull(variableResolver, "variableResolver");
    }

    public String render(String template, Map<String, String> variables) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(variables, "variables");
        RequestParts templateParts = split(template);
        if (hasChunkedTransferEncoding(templateParts.head())
                && templateParts.body().contains("${")) {
            throw new IllegalArgumentException(
                    "Variables in chunked request bodies are not supported"
            );
        }

        String rendered = variableResolver.substitute(template, variables);
        RequestParts renderedParts = split(rendered);
        if (renderedParts.separator().isEmpty()) {
            return rendered;
        }

        String newline = renderedParts.head().contains("\r\n") ? "\r\n" : "\n";
        String[] lines = renderedParts.head().split("\\r?\\n", -1);
        int contentLength = renderedParts.body().getBytes(StandardCharsets.UTF_8).length;
        for (int index = 0; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0 && lines[index].substring(0, colon).trim()
                    .equalsIgnoreCase("Content-Length")) {
                lines[index] = lines[index].substring(0, colon)
                        + ": " + contentLength;
            }
        }
        return String.join(newline, Arrays.asList(lines))
                + renderedParts.separator()
                + renderedParts.body();
    }

    private boolean hasChunkedTransferEncoding(String head) {
        return head.lines()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.startsWith("transfer-encoding:")
                        && line.contains("chunked"));
    }

    private RequestParts split(String request) {
        int separatorIndex = request.indexOf("\r\n\r\n");
        if (separatorIndex >= 0) {
            return new RequestParts(
                    request.substring(0, separatorIndex),
                    "\r\n\r\n",
                    request.substring(separatorIndex + 4)
            );
        }
        separatorIndex = request.indexOf("\n\n");
        if (separatorIndex >= 0) {
            return new RequestParts(
                    request.substring(0, separatorIndex),
                    "\n\n",
                    request.substring(separatorIndex + 2)
            );
        }
        return new RequestParts(request, "", "");
    }

    private record RequestParts(String head, String separator, String body) {
    }
}
