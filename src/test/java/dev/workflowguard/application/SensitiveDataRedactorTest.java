package dev.workflowguard.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {
    private final SensitiveDataRedactor redactor =
            new SensitiveDataRedactor(new ObjectMapper());

    @Test
    void redactsPersonalDataFromQueriesAndFormBodies() {
        String redacted = redactor.redactHttpMessage(
                "POST /profile?email=query@example.test HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Type: application/x-www-form-urlencoded\r\n\r\n"
                        + "email=form@example.test&phone_number=123456789"
                        + "&label=visible"
        );

        assertFalse(redacted.contains("query@example.test"));
        assertFalse(redacted.contains("form@example.test"));
        assertFalse(redacted.contains("123456789"));
        assertTrue(redacted.contains("label=visible"));
    }

    @Test
    void redactsEmailValuesNestedUnderGenericKeys() {
        String redacted = redactor.redactPayload(
                "{\"contacts\":[{\"value\":\"nested@example.test\"}],"
                        + "\"label\":\"visible\"}"
        );

        assertFalse(redacted.contains("nested@example.test"));
        assertTrue(redacted.contains("\"label\":\"visible\""));
    }

    @Test
    void redactsXmlAndMultipartBodiesConservatively() {
        String xml = redactor.redactHttpMessage(
                "POST /soap HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Type: application/xml\r\n\r\n"
                        + "<account><token>xml-secret</token></account>"
        );
        String multipart = redactor.redactHttpMessage(
                "POST /upload HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Type: multipart/form-data; boundary=x\r\n\r\n"
                        + "--x\r\nsecret-file-content\r\n--x--"
        );

        assertFalse(xml.contains("xml-secret"));
        assertFalse(multipart.contains("secret-file-content"));
        assertTrue(xml.contains(SensitiveDataRedactor.REDACTED));
        assertTrue(multipart.contains(SensitiveDataRedactor.REDACTED));
    }

    @Test
    void omitsEvidenceBodiesAndRedactsAllQueryAndOpaquePathValues() {
        String redacted = redactor.redactEvidenceHttpMessage(
                "POST /reset/ABCDEFGHIJKLMNOPQRST?label=visible HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "X-Correlation: internal-secret\r\n"
                        + "Content-Type: text/plain\r\n\r\n"
                        + "unstructured-secret"
        );

        assertFalse(redacted.contains("ABCDEFGHIJKLMNOPQRST"));
        assertFalse(redacted.contains("label=visible"));
        assertFalse(redacted.contains("X-Correlation"));
        assertFalse(redacted.contains("Content-Type"));
        assertFalse(redacted.contains("internal-secret"));
        assertFalse(redacted.contains("unstructured-secret"));
        assertTrue(redacted.contains(SensitiveDataRedactor.REDACTED));
    }

    @Test
    void removesResponseReasonPhrasesAndRedactsUrlsInMessages() {
        String response = redactor.redactEvidenceHttpMessage(
                "HTTP/1.1 403 account-secret\r\n"
                        + "X-account-secret: value\r\n\r\n"
        );
        String message = redactor.redactFreeText(
                "Outside scope: https://example.test/reset/ABCDEFGHIJKLMNOPQRST"
                        + "?account=alice"
        );

        assertFalse(response.contains("account-secret"));
        assertFalse(message.contains("ABCDEFGHIJKLMNOPQRST"));
        assertFalse(message.contains("account=alice"));
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(message.contains(SensitiveDataRedactor.URL_REDACTED));
    }
}
