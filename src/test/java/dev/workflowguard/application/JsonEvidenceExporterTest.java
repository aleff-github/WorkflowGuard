package dev.workflowguard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepExecutionResult;
import dev.workflowguard.domain.StepExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEvidenceExporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesStructuredEvidenceAndRedactsSessionSecrets() throws Exception {
        UUID actorId = UUID.randomUUID();
        Instant started = Instant.parse("2026-07-29T10:00:00Z");
        StepExecutionResult step = new StepExecutionResult(
                UUID.randomUUID(),
                "Create order",
                "POST",
                "https://example.test/orders?access_token=url-secret",
                actorId,
                "Alice",
                ExecutionPhase.MUTATION,
                started,
                Duration.ofMillis(12),
                StepExecutionStatus.RESPONSE_RECEIVED,
                Optional.of(201),
                "POST /orders HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Authorization: Bearer request-secret\r\n"
                        + "X-Auth-Token: header-secret\r\n"
                        + "Cookie: session=request-cookie\r\n"
                        + "Content-Type: application/json\r\n\r\n"
                        + "{\"name\":\"visible\",\"accessToken\":\"body-secret\"}",
                "HTTP/1.1 201 Created\r\n"
                        + "Set-Cookie: session=response-cookie\r\n\r\n"
                        + "{\"id\":42,\"api_key\":\"response-secret\","
                        + "\"email\":\"alice@example.test\","
                        + "\"contact\":\"alice@example.test\","
                        + "\"first_name\":\"Alice\"}",
                "{\"id\":42,\"api_key\":\"response-secret\","
                        + "\"email\":\"alice@example.test\","
                        + "\"contact\":\"alice@example.test\","
                        + "\"first_name\":\"Alice\"}",
                Map.of("orderId", "42", "sessionToken", "variable-secret"),
                Optional.empty()
        );
        ExecutionRun run = new ExecutionRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Swap actor",
                RunStatus.COMPLETED,
                started,
                started.plusMillis(12),
                List.of(step),
                Map.of("orderId", "42", "authToken", "run-secret"),
                Map.of("Alice", Map.of("https://example.test:443 :: session", "cookie-secret")),
                List.of(),
                List.of("Executed 1 request.")
        );

        Path destination = temporaryDirectory.resolve("evidence.json");
        new JsonEvidenceExporter().write(destination, run);

        String json = Files.readString(destination);
        JsonNode root = new ObjectMapper().readTree(json);
        assertTrue(root.path("redactionApplied").asBoolean());
        assertEquals("conservative-v2", root.path("redactionPolicy").asText());
        assertTrue(root.path("httpBodiesOmitted").asBoolean());
        assertEquals("NOT_EVALUATED", root.path("assessment").asText());
        assertEquals("Alice", root.path("steps").get(0).path("actorName").asText());
        assertEquals("<redacted>", root.path("variables").path("orderId").asText());
        assertEquals("<redacted>", root.path("variables").path("authToken").asText());
        assertEquals(
                "<redacted>",
                root.path("steps").get(0).path("extractedVariables").path("orderId").asText()
        );
        assertTrue(json.contains("<redacted>"));
        assertFalse(json.contains("request-secret"));
        assertFalse(json.contains("header-secret"));
        assertFalse(json.contains("url-secret"));
        assertFalse(json.contains("request-cookie"));
        assertFalse(json.contains("response-cookie"));
        assertFalse(json.contains("body-secret"));
        assertFalse(json.contains("response-secret"));
        assertFalse(json.contains("alice@example.test"));
        assertFalse(
                root.path("steps").get(0).path("response").asText()
                        .contains("\"first_name\":\"Alice\"")
        );
        assertFalse(json.contains("variable-secret"));
        assertFalse(json.contains("cookie-secret"));
        assertFalse(json.contains("https://example.test:443 :: session"));
    }
}
