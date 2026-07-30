package dev.workflowguard.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WorkflowGuardFixture implements AutoCloseable {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final FixtureMode mode;
    private final FixtureState state = new FixtureState();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor;

    private WorkflowGuardFixture(InetSocketAddress address, FixtureMode mode) throws IOException {
        validateLoopback(address);
        this.mode = Objects.requireNonNull(mode, "mode");
        server = HttpServer.create(address, 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public static WorkflowGuardFixture start(InetSocketAddress address, FixtureMode mode)
            throws IOException {
        WorkflowGuardFixture fixture = new WorkflowGuardFixture(address, mode);
        fixture.server.start();
        return fixture;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public FixtureMode mode() {
        return mode;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (BadRequestException exception) {
                sendJson(exchange, 400, Map.of("error", "bad_request", "message", exception.getMessage()));
            } catch (RuntimeException exception) {
                sendJson(exchange, 500, Map.of("error", "fixture_failure"));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (method.equals("GET") && path.equals("/health")) {
            sendJson(exchange, 200, Map.of("status", "ok", "mode", mode.name().toLowerCase()));
            return;
        }
        if (method.equals("POST") && path.equals("/test/reset")) {
            state.reset();
            sendEmpty(exchange, 204);
            return;
        }
        if (method.equals("POST") && path.equals("/api/invitations")) {
            createInvitation(exchange);
            return;
        }
        if (method.equals("POST")
                && path.matches("/api/invitations/[^/]+/accept")) {
            String invitationId = path.substring(
                    "/api/invitations/".length(),
                    path.length() - "/accept".length()
            );
            acceptInvitation(exchange, invitationId);
            return;
        }
        if (method.equals("DELETE") && path.matches("/api/members/[^/]+")) {
            String memberId = path.substring("/api/members/".length());
            revokeMember(exchange, memberId);
            return;
        }
        if (method.equals("GET") && path.equals("/api/organizations/org-1/members")) {
            var members = state.members();
            sendJson(
                    exchange,
                    200,
                    Map.of("organizationId", "org-1", "count", members.size(), "members", members)
            );
            return;
        }

        sendJson(exchange, 404, Map.of("error", "not_found", "path", path));
    }

    private void createInvitation(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        JsonNode recipientNode = request.get("recipient");
        if (recipientNode == null || !recipientNode.isTextual() || recipientNode.asText().isBlank()) {
            throw new BadRequestException("recipient must be a non-empty string");
        }

        FixtureState.InvitationSnapshot invitation = state.createInvitation(recipientNode.asText());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", invitation.id());
        response.put("organizationId", "org-1");
        response.put("recipient", invitation.recipient());
        response.put("status", "pending");
        sendJson(exchange, 201, response);
    }

    private void acceptInvitation(HttpExchange exchange, String invitationId) throws IOException {
        var result = state.acceptInvitation(invitationId, mode);
        if (result.isEmpty()) {
            sendJson(exchange, 404, Map.of("error", "invitation_not_found"));
            return;
        }

        FixtureState.AcceptResult acceptResult = result.get();
        if (acceptResult.conflict()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("error", acceptResult.invitation().revoked()
                    ? "invitation_revoked"
                    : "invitation_already_used");
            response.put("stateChanged", acceptResult.stateChanged());
            sendJson(exchange, 409, response);
            return;
        }

        sendJson(exchange, 200, Map.of(
                "status", "accepted",
                "memberId", acceptResult.invitation().recipient()
        ));
    }

    private void revokeMember(HttpExchange exchange, String memberId) throws IOException {
        boolean existed = state.revokeMember(memberId);
        if (!existed) {
            sendJson(exchange, 404, Map.of("error", "member_not_found"));
            return;
        }
        sendEmpty(exchange, 204);
    }

    private JsonNode readJson(HttpExchange exchange) {
        try {
            return objectMapper.readTree(exchange.getRequestBody());
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("request body must contain valid JSON");
        } catch (IOException exception) {
            throw new BadRequestException("request body could not be read");
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object value) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
    }

    private void sendEmpty(HttpExchange exchange, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, -1);
    }

    private static void validateLoopback(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        InetAddress inetAddress = address.getAddress();
        if (inetAddress == null || !inetAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("The test fixture must bind to a loopback address");
        }
    }

    private static final class BadRequestException extends RuntimeException {
        private BadRequestException(String message) {
            super(message);
        }
    }
}
