package dev.workflowguard.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGuardFixtureTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void vulnerableModeChangesStateDespiteReplayConflict() throws Exception {
        try (WorkflowGuardFixture fixture = start(FixtureMode.VULNERABLE)) {
            URI baseUri = baseUri(fixture);
            String invitationId = createInvitation(baseUri, "member-b");

            assertEquals(200, post(baseUri, "/api/invitations/" + invitationId + "/accept", null).statusCode());
            assertEquals(1, members(baseUri).get("count").asInt());
            assertEquals(204, delete(baseUri, "/api/members/member-b").statusCode());
            assertEquals(0, members(baseUri).get("count").asInt());

            HttpResponse<String> replay = post(
                    baseUri,
                    "/api/invitations/" + invitationId + "/accept",
                    null
            );

            assertEquals(409, replay.statusCode());
            assertTrue(json(replay).get("stateChanged").asBoolean());
            assertEquals(1, members(baseUri).get("count").asInt());
            assertEquals("member-b", members(baseUri).get("members").get(0).asText());
        }
    }

    @Test
    void secureModeRejectsReplayWithoutChangingState() throws Exception {
        try (WorkflowGuardFixture fixture = start(FixtureMode.SECURE)) {
            URI baseUri = baseUri(fixture);
            String invitationId = createInvitation(baseUri, "member-b");
            post(baseUri, "/api/invitations/" + invitationId + "/accept", null);
            delete(baseUri, "/api/members/member-b");

            HttpResponse<String> replay = post(
                    baseUri,
                    "/api/invitations/" + invitationId + "/accept",
                    null
            );

            assertEquals(409, replay.statusCode());
            assertFalse(json(replay).get("stateChanged").asBoolean());
            assertEquals(0, members(baseUri).get("count").asInt());
        }
    }

    @Test
    void resetMakesTheFixtureDeterministic() throws Exception {
        try (WorkflowGuardFixture fixture = start(FixtureMode.VULNERABLE)) {
            URI baseUri = baseUri(fixture);
            assertEquals("inv-1", createInvitation(baseUri, "first"));
            assertEquals("inv-2", createInvitation(baseUri, "second"));

            assertEquals(204, post(baseUri, "/test/reset", null).statusCode());

            assertEquals("inv-1", createInvitation(baseUri, "after-reset"));
            assertEquals(0, members(baseUri).get("count").asInt());
        }
    }

    @Test
    void refusesInvalidInputAndNonLoopbackBinding() throws Exception {
        try (WorkflowGuardFixture fixture = start(FixtureMode.VULNERABLE)) {
            HttpResponse<String> response = post(
                    baseUri(fixture),
                    "/api/invitations",
                    "{\"recipient\":\"\"}"
            );
            assertEquals(400, response.statusCode());
            assertEquals("bad_request", json(response).get("error").asText());
        }

        InetSocketAddress publicBinding = new InetSocketAddress(
                InetAddress.getByName("0.0.0.0"),
                0
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowGuardFixture.start(publicBinding, FixtureMode.VULNERABLE)
        );
    }

    private WorkflowGuardFixture start(FixtureMode mode) throws Exception {
        return WorkflowGuardFixture.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                mode
        );
    }

    private URI baseUri(WorkflowGuardFixture fixture) {
        return URI.create("http://127.0.0.1:" + fixture.port());
    }

    private String createInvitation(URI baseUri, String recipient) throws Exception {
        HttpResponse<String> response = post(
                baseUri,
                "/api/invitations",
                objectMapper.writeValueAsString(java.util.Map.of("recipient", recipient))
        );
        assertEquals(201, response.statusCode());
        return json(response).get("id").asText();
    }

    private JsonNode members(URI baseUri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        baseUri.resolve("/api/organizations/org-1/members")
                )
                .GET()
                .build();
        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        return json(response);
    }

    private HttpResponse<String> post(URI baseUri, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(URI baseUri, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }
}
