package dev.workflowguard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.core.SafetyGate;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionPlan;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.PlannedStep;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.ports.HttpExchangeEvidence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealLabActorAuthorizationIntegrationTest {
    private static final String CREDENTIAL_FILE_ENV =
            "WORKFLOWGUARD_LAB_JUICE_CREDENTIALS";
    private static final String PROXY_PORT_ENV = "WORKFLOWGUARD_LAB_PROXY_PORT";
    private static final String JUICE_ORIGIN = "http://127.0.0.1:3000";
    private static final Pattern ACCOUNT_HEADER = Pattern.compile(
            "^Account\\s+([A-D])\\s*:",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FIELD = Pattern.compile(
            "^-\\s*([^:]+?)\\s*:\\s*(.*)$"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replaysRealBasketChecksWithAuthorizationIsolatedByTheProductionCoordinator()
            throws Exception {
        String credentialFile = System.getenv(CREDENTIAL_FILE_ENV);
        String proxyPortValue = System.getenv(PROXY_PORT_ENV);
        Assumptions.assumeTrue(
                credentialFile != null && proxyPortValue != null,
                "Real local-lab integration is opt-in."
        );

        int proxyPort = Integer.parseInt(proxyPortValue);
        Map<String, Account> accounts = readAccounts(Path.of(credentialFile));
        Proxy proxy = new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", proxyPort)
        );

        Session actorASession = login(proxy, accounts.get("A"), "native-a");
        Session actorBSession = login(proxy, accounts.get("B"), "native-b");
        assertFalse(actorASession.basketId().equals(actorBSession.basketId()));

        ActorDefinition actorA = ActorDefinition.create(
                "Juice A",
                "",
                "Bearer " + actorASession.token(),
                false
        );
        ActorDefinition actorB = ActorDefinition.create(
                "Juice B",
                "",
                "Bearer " + actorBSession.token(),
                false
        );

        List<WorkflowStep> steps = List.of(
                basketStep("Own A", actorASession.basketId(), actorA),
                basketStep("Own B", actorBSession.basketId(), actorB),
                basketStep("A reads B", actorBSession.basketId(), actorA),
                basketStep("B reads A", actorASession.basketId(), actorB)
        );
        MutationCase mutation = new MutationCase(
                UUID.randomUUID(),
                "Real local-lab actor authorization isolation",
                MutationType.SWAP_ACTOR,
                steps,
                "Read-only A/B basket replay through Burp."
        );
        ExecutionPlan plan = new ExecutionPlan(
                mutation,
                steps.stream()
                        .map(step -> new PlannedStep(ExecutionPhase.MUTATION, step))
                        .toList(),
                List.of(ActorDefinition.defaultActor(), actorA, actorB),
                List.of(),
                List.of()
        );

        List<String> sentRequests = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();
        try (var coordinator = new ExecutionCoordinator(
                step -> {
                    sentRequests.add(step.rawRequest());
                    return send(proxy, step);
                },
                url -> url.startsWith(JUICE_ORIGIN + "/"),
                new SafetyGate(),
                duration -> Thread.sleep(duration),
                Clock.systemUTC(),
                executor
        )) {
            var run = coordinator.execute(
                    plan,
                    new ExecutionPolicy(10, Duration.ofMillis(750), true, true),
                    false
            ).join();

            assertEquals(RunStatus.COMPLETED, run.status());
            assertEquals(4, run.stepResults().size());
            assertTrue(run.stepResults().stream().allMatch(
                    result -> result.statusCode().orElse(0) == 200
            ));

            assertBasket(run.stepResults().get(0).responseBody(), actorASession.basketId());
            assertBasket(run.stepResults().get(1).responseBody(), actorBSession.basketId());
            assertBasket(run.stepResults().get(2).responseBody(), actorBSession.basketId());
            assertBasket(run.stepResults().get(3).responseBody(), actorASession.basketId());
        }

        assertEquals(4, sentRequests.size());
        assertAuthorization(sentRequests.get(0), actorASession.token(), actorBSession.token());
        assertAuthorization(sentRequests.get(1), actorBSession.token(), actorASession.token());
        assertAuthorization(sentRequests.get(2), actorASession.token(), actorBSession.token());
        assertAuthorization(sentRequests.get(3), actorBSession.token(), actorASession.token());
    }

    private Map<String, Account> readAccounts(Path path) throws IOException {
        Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            Matcher account = ACCOUNT_HEADER.matcher(trimmed);
            if (account.find()) {
                current = account.group(1).toUpperCase(Locale.ROOT);
                parsed.put(current, new LinkedHashMap<>());
                continue;
            }
            Matcher field = FIELD.matcher(trimmed);
            if (current != null && field.matches()) {
                String key = field.group(1).trim().toLowerCase(Locale.ROOT);
                parsed.get(current).put(key, field.group(2));
            }
        }

        Map<String, Account> accounts = new LinkedHashMap<>();
        for (String label : List.of("A", "B")) {
            Map<String, String> fields = parsed.get(label);
            if (fields == null
                    || fields.get("email") == null
                    || fields.get("password") == null) {
                throw new IllegalArgumentException(
                        "Missing required credential fields for account " + label
                );
            }
            accounts.put(
                    label,
                    new Account(fields.get("email"), fields.get("password"))
            );
        }
        return accounts;
    }

    private Session login(Proxy proxy, Account account, String marker)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", account.email(),
                "password", account.password()
        ));
        HttpURLConnection connection = open(
                proxy,
                URI.create(JUICE_ORIGIN + "/rest/user/login").toURL(),
                "POST"
        );
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-WorkflowGuard-Test-Run", marker);
        connection.setDoOutput(true);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int statusCode = connection.getResponseCode();
        String responseBody = readBody(connection, statusCode);
        connection.disconnect();
        assertEquals(200, statusCode);

        JsonNode authentication = objectMapper.readTree(responseBody)
                .path("authentication");
        String token = authentication.path("token").asText("");
        String basketId = authentication.path("bid").asText("");
        assertFalse(token.isBlank());
        assertFalse(basketId.isBlank());
        return new Session(token, basketId);
    }

    private WorkflowStep basketStep(
            String name,
            String basketId,
            ActorDefinition actor
    ) {
        String path = "/rest/basket/" + basketId;
        return new WorkflowStep(
                UUID.randomUUID(),
                name,
                "GET",
                JUICE_ORIGIN + path,
                "GET " + path + " HTTP/1.1\r\n"
                        + "Host: 127.0.0.1:3000\r\n"
                        + "Authorization: Bearer captured-must-be-replaced\r\n"
                        + "Accept: application/json\r\n\r\n",
                StepCategory.READ,
                StepRole.ACTION,
                actor.id(),
                true,
                true
        );
    }

    private HttpExchangeEvidence send(Proxy proxy, WorkflowStep step) {
        HttpURLConnection connection = null;
        try {
            RequestParts parts = split(step.rawRequest());
            connection = open(proxy, URI.create(step.url()).toURL(), step.method());
            List<String> lines = parts.head().lines().toList();
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                if (name.equalsIgnoreCase("Host")
                        || name.equalsIgnoreCase("Content-Length")
                        || name.equalsIgnoreCase("Connection")
                        || name.equalsIgnoreCase("Proxy-Connection")) {
                    continue;
                }
                connection.setRequestProperty(name, line.substring(colon + 1).trim());
            }
            if (!parts.body().isEmpty()) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(
                        parts.body().getBytes(StandardCharsets.UTF_8)
                );
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode);
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            connection.getHeaderFields().forEach((name, values) -> {
                if (name != null && values != null) {
                    responseHeaders.put(name, List.copyOf(values));
                }
            });
            return new HttpExchangeEvidence(
                    true,
                    Optional.of(statusCode),
                    "HTTP/1.1 " + statusCode + "\r\n\r\n" + responseBody,
                    responseBody,
                    responseHeaders
            );
        } catch (Exception ignored) {
            return HttpExchangeEvidence.noResponse();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(Proxy proxy, URL url, String method)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty(
                "User-Agent",
                "WorkflowGuard-Real-Lab-Integration/1.0"
        );
        return connection;
    }

    private String readBody(HttpURLConnection connection, int statusCode)
            throws IOException {
        InputStream stream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertBasket(String responseBody, String expectedBasketId)
            throws IOException {
        String returnedId = objectMapper.readTree(responseBody)
                .path("data")
                .path("id")
                .asText("");
        assertTrue(returnedId.equals(expectedBasketId));
    }

    private void assertAuthorization(
            String request,
            String expectedToken,
            String otherToken
    ) {
        assertTrue(request.contains("Authorization: Bearer " + expectedToken));
        assertFalse(request.contains(otherToken));
        assertFalse(request.contains("captured-must-be-replaced"));
    }

    private RequestParts split(String request) {
        int separator = request.indexOf("\r\n\r\n");
        if (separator >= 0) {
            return new RequestParts(
                    request.substring(0, separator),
                    request.substring(separator + 4)
            );
        }
        separator = request.indexOf("\n\n");
        if (separator >= 0) {
            return new RequestParts(
                    request.substring(0, separator),
                    request.substring(separator + 2)
            );
        }
        return new RequestParts(request, "");
    }

    private record Account(String email, String password) {
    }

    private record Session(String token, String basketId) {
    }

    private record RequestParts(String head, String body) {
    }
}
