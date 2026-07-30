package dev.workflowguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRequestTemplateRendererTest {
    private final HttpRequestTemplateRenderer renderer = new HttpRequestTemplateRenderer(
            new VariableResolver(new ObjectMapper())
    );

    @Test
    void substitutesVariablesAndRecalculatesUtf8ContentLength() {
        String template = "POST /members/${id} HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Content-Length: 1\r\n\r\n"
                + "{\"name\":\"${name}\"}";

        String rendered = renderer.render(
                template,
                Map.of("id", "42", "name", "José")
        );

        assertEquals(
                "POST /members/42 HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Length: 16\r\n\r\n"
                        + "{\"name\":\"José\"}",
                rendered
        );
    }

    @Test
    void rejectsVariableReplacementInsideChunkedBodies() {
        String template = "POST / HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "${value}";

        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(template, Map.of("value", "data"))
        );
    }
}
