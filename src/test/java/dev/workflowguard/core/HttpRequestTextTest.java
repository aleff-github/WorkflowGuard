package dev.workflowguard.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRequestTextTest {
    @Test
    void normalizesLfAndCrLineEndingsToHttpCrlf() {
        assertEquals(
                "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n",
                HttpRequestText.normalizeLineEndings(
                        "GET / HTTP/1.1\nHost: example.test\r\r"
                )
        );
    }

    @Test
    void preservesAlreadyNormalizedRequests() {
        String request = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        assertEquals(request, HttpRequestText.normalizeLineEndings(request));
    }

    @Test
    void derivesTheEffectiveUrlFromTheRenderedRequestTarget() {
        assertEquals(
                "https://example.test/orders/42?view=full",
                HttpRequestText.effectiveUrl(
                        "https://example.test/orders/template",
                        "GET /orders/42?view=full HTTP/1.1\r\n"
                                + "Host: example.test\r\n\r\n"
                )
        );
    }

    @Test
    void rejectsMethodAndOriginDivergence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequestText.validateTemplate(
                        "GET",
                        "https://example.test/account",
                        "DELETE /account HTTP/1.1\r\nHost: example.test\r\n\r\n"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequestText.effectiveUrl(
                        "https://example.test/account",
                        "GET /account HTTP/1.1\r\nHost: other.test\r\n\r\n"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequestText.effectiveUrl(
                        "https://example.test/account",
                        "GET https://other.test/account HTTP/1.1\r\n"
                                + "Host: example.test\r\n\r\n"
                )
        );
    }
}
