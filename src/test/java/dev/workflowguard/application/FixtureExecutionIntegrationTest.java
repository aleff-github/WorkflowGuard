package dev.workflowguard.application;

import dev.workflowguard.core.ExecutionPlanner;
import dev.workflowguard.core.MutationEngine;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.InvariantRule;
import dev.workflowguard.domain.JsonInvariant;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.fixture.FixtureMode;
import dev.workflowguard.fixture.WorkflowGuardFixture;
import dev.workflowguard.ports.HttpExchangeEvidence;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureExecutionIntegrationTest {
    @Test
    void detectsReplayStateChangeAndVerifiesCleanupAgainstTheLoopbackFixture() throws Exception {
        try (WorkflowGuardFixture fixture = WorkflowGuardFixture.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                FixtureMode.VULNERABLE
        )) {
            String origin = "http://127.0.0.1:" + fixture.port();
            WorkflowStep create = step(
                    "S1 — create invitation",
                    "POST",
                    origin + "/api/invitations",
                    "POST /api/invitations HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + fixture.port() + "\r\n"
                            + "Content-Type: application/json\r\n\r\n"
                            + "{\"recipient\":\"member-1\"}",
                    StepCategory.INVITE,
                    StepRole.ACTION
            );
            WorkflowStep accept = step(
                    "S2 — accept invitation",
                    "POST",
                    origin + "/api/invitations/template/accept",
                    "POST /api/invitations/${invitationId}/accept HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + fixture.port() + "\r\n\r\n",
                    StepCategory.ONE_TIME_USE,
                    StepRole.ACTION
            );
            WorkflowStep revoke = step(
                    "S3 — revoke member",
                    "DELETE",
                    origin + "/api/members/member-1",
                    "DELETE /api/members/member-1 HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + fixture.port() + "\r\n\r\n",
                    StepCategory.REVOKE,
                    StepRole.ACTION
            );
            WorkflowStep probe = step(
                    "P1 — members probe",
                    "GET",
                    origin + "/api/organizations/org-1/members",
                    "GET /api/organizations/org-1/members HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + fixture.port() + "\r\n\r\n",
                    StepCategory.READ,
                    StepRole.PROBE
            );
            WorkflowStep cleanup = step(
                    "C1 — remove replayed member",
                    "DELETE",
                    origin + "/api/members/member-1",
                    "DELETE /api/members/member-1 HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + fixture.port() + "\r\n\r\n",
                    StepCategory.DELETE,
                    StepRole.CLEANUP
            );
            VariableDefinition invitationId = new VariableDefinition(
                    "invitationId",
                    create.id(),
                    ExtractionType.JSON_POINTER,
                    "/id",
                    0
            );
            ProbeInvariant memberCount = ProbeInvariant.create(
                    probe.id(),
                    new JsonInvariant(
                            "Member count unchanged",
                            "/count",
                            InvariantRule.UNCHANGED,
                            null
                    )
            );
            Workflow workflow = new Workflow(
                    UUID.randomUUID(),
                    "Invitation replay",
                    List.of(create, accept, revoke, probe, cleanup),
                    List.of(invitationId),
                    List.of(memberCount),
                    Instant.now()
            );
            var mutation = new MutationEngine().generate(
                    workflow,
                    EnumSet.of(MutationType.REPLAY_AFTER_REVOKE),
                    20
            ).getFirst();
            assertEquals(MutationType.REPLAY_AFTER_REVOKE, mutation.type());
            var plan = new ExecutionPlanner().plan(workflow, mutation);

            try (var coordinator = new ExecutionCoordinator(
                    new JdkFixtureSender(origin),
                    ignored -> true
            )) {
                var run = coordinator.execute(
                        plan,
                        new ExecutionPolicy(10, Duration.ZERO, true, true),
                        true
                ).join();

                assertEquals(RunStatus.COMPLETED, run.status());
                assertEquals("inv-1", run.variables().get("invitationId"));
                assertEquals(2, run.invariantResults().size());
                assertFalse(run.invariantResults().getFirst().passed());
                assertEquals(ExecutionPhase.AFTER_PROBE,
                        run.invariantResults().getFirst().comparisonPhase());
                assertTrue(run.invariantResults().getLast().passed());
                assertEquals(ExecutionPhase.POST_CLEANUP_PROBE,
                        run.invariantResults().getLast().comparisonPhase());
                assertTrue(run.stepResults().stream().anyMatch(
                        result -> result.statusCode().equals(Optional.of(409))
                ));
            }

            HttpResponse<String> finalState = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(
                            URI.create(origin + "/api/organizations/org-1/members")
                    ).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, finalState.statusCode());
            assertTrue(finalState.body().contains("\"count\":0"));
        }
    }

    private WorkflowStep step(
            String name,
            String method,
            String url,
            String rawRequest,
            StepCategory category,
            StepRole role
    ) {
        return new WorkflowStep(
                UUID.randomUUID(),
                name,
                method,
                url,
                rawRequest,
                category,
                role,
                true,
                true
        );
    }

    private static final class JdkFixtureSender
            implements dev.workflowguard.ports.RequestSender {
        private final String origin;
        private final HttpClient client = HttpClient.newHttpClient();

        private JdkFixtureSender(String origin) {
            this.origin = origin;
        }

        @Override
        public HttpExchangeEvidence send(WorkflowStep step) {
            try {
                ParsedRequest parsed = parse(step.rawRequest());
                HttpRequest.BodyPublisher body = parsed.body().isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(
                                parsed.body(),
                                StandardCharsets.UTF_8
                        );
                HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(origin + parsed.path())
                ).method(parsed.method(), body);
                if (parsed.rawRequest().toLowerCase().contains(
                        "content-type: application/json"
                )) {
                    builder.header("Content-Type", "application/json");
                }
                HttpResponse<String> response = client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                return new HttpExchangeEvidence(
                        true,
                        Optional.of(response.statusCode()),
                        "HTTP/1.1 " + response.statusCode()
                                + System.lineSeparator()
                                + System.lineSeparator()
                                + response.body(),
                        response.body()
                );
            } catch (Exception exception) {
                throw new IllegalStateException("Fixture request failed", exception);
            }
        }

        private ParsedRequest parse(String rawRequest) {
            String normalized = rawRequest.replace("\r\n", "\n");
            int separator = normalized.indexOf("\n\n");
            String head = separator < 0 ? normalized : normalized.substring(0, separator);
            String body = separator < 0 ? "" : normalized.substring(separator + 2);
            String[] requestLine = head.lines().findFirst().orElseThrow().split(" ", 3);
            return new ParsedRequest(requestLine[0], requestLine[1], body, rawRequest);
        }
    }

    private record ParsedRequest(String method, String path, String body, String rawRequest) {
    }
}
