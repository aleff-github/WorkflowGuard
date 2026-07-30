package dev.workflowguard.application;

import dev.workflowguard.core.SafetyGate;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionPlan;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.PlannedStep;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepExecutionStatus;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.ports.HttpExchangeEvidence;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static dev.workflowguard.TestFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionCoordinatorTest {
    @Test
    void executesRequestsSequentiallyAndRecordsEvidence() {
        WorkflowStep first = step("First", "GET", StepCategory.READ);
        WorkflowStep second = step("Second", "GET", StepCategory.READ);
        List<UUID> sent = new ArrayList<>();
        List<Duration> delays = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = new ExecutionCoordinator(
                workflowStep -> {
                    sent.add(workflowStep.id());
                    return new HttpExchangeEvidence(
                            true,
                            Optional.of(200),
                            "HTTP/1.1 200 OK\r\n\r\n{}"
                    );
                },
                ignored -> true,
                new SafetyGate(),
                delays::add,
                Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC),
                executor
        )) {
            var run = coordinator.execute(
                    mutation(first, second),
                    new ExecutionPolicy(5, Duration.ofMillis(250), true, true),
                    false
            ).join();

            assertEquals(RunStatus.COMPLETED, run.status());
            assertEquals(List.of(first.id(), second.id()), sent);
            assertEquals(List.of(Duration.ofMillis(250)), delays);
            assertEquals(2, run.stepResults().size());
            assertEquals(
                    StepExecutionStatus.RESPONSE_RECEIVED,
                    run.stepResults().getFirst().status()
            );
            assertEquals(Optional.of(200), run.stepResults().getFirst().statusCode());
            assertTrue(run.stepResults().getFirst().rawResponse().contains("200 OK"));
            assertFalse(coordinator.isRunning());
        }
    }

    @Test
    void blocksStateChangingRunWithoutExplicitConfirmation() {
        WorkflowStep create = step("Create", "POST", StepCategory.CREATE);
        List<UUID> sent = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = coordinator(sent, ignored -> true, executor)) {
            var run = coordinator.execute(
                    mutation(create),
                    ExecutionPolicy.conservativeDefaults(),
                    false
            ).join();

            assertEquals(RunStatus.BLOCKED, run.status());
            assertTrue(run.messages().stream().anyMatch(
                    message -> message.contains("Explicit confirmation")
            ));
            assertTrue(sent.isEmpty());
        }
    }

    @Test
    void refreshesScopeImmediatelyBeforeExecution() {
        WorkflowStep capturedInScope = step("Probe", "GET", StepCategory.READ);
        List<UUID> sent = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = coordinator(sent, ignored -> false, executor)) {
            var run = coordinator.execute(
                    mutation(capturedInScope),
                    ExecutionPolicy.conservativeDefaults(),
                    false
            ).join();

            assertEquals(RunStatus.BLOCKED, run.status());
            assertTrue(run.messages().stream().anyMatch(
                    message -> message.contains("outside the Burp target scope")
            ));
            assertTrue(sent.isEmpty());
        }
    }

    @Test
    void stopsAfterARequestReturnsNoResponse() {
        WorkflowStep first = step("First", "GET", StepCategory.READ);
        WorkflowStep second = step("Second", "GET", StepCategory.READ);
        List<UUID> sent = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = new ExecutionCoordinator(
                workflowStep -> {
                    sent.add(workflowStep.id());
                    return HttpExchangeEvidence.noResponse();
                },
                ignored -> true,
                new SafetyGate(),
                ignored -> {
                },
                Clock.systemUTC(),
                executor
        )) {
            var run = coordinator.execute(
                    mutation(first, second),
                    new ExecutionPolicy(5, Duration.ZERO, true, true),
                    false
            ).join();

            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(List.of(first.id()), sent);
            assertEquals(StepExecutionStatus.NO_RESPONSE, run.stepResults().getFirst().status());
        }
    }

    @Test
    void executesEachActorWithAnIsolatedCookieJarAndCapturesRotation() {
        ActorDefinition alice = ActorDefinition.create(
                "Alice",
                "session=alice",
                "Bearer alice-token",
                false
        );
        ActorDefinition bob = ActorDefinition.create(
                "Bob",
                "session=bob",
                "Bearer bob-token",
                false
        );
        WorkflowStep aliceFirst = step("AliceFirst", "GET", StepCategory.READ)
                .withActorId(alice.id());
        WorkflowStep aliceSecond = step("AliceSecond", "GET", StepCategory.READ)
                .withActorId(alice.id());
        WorkflowStep bobStep = step("Bob", "GET", StepCategory.READ)
                .withActorId(bob.id());
        MutationCase mutation = mutation(aliceFirst, aliceSecond, bobStep);
        ExecutionPlan plan = new ExecutionPlan(
                mutation,
                mutation.steps().stream()
                        .map(item -> new PlannedStep(ExecutionPhase.MUTATION, item))
                        .toList(),
                List.of(ActorDefinition.defaultActor(), alice, bob),
                List.of(),
                List.of()
        );
        List<String> sentRequests = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = new ExecutionCoordinator(
                workflowStep -> {
                    sentRequests.add(workflowStep.rawRequest());
                    Map<String, List<String>> headers = sentRequests.size() == 1
                            ? Map.of(
                                    "set-cookie",
                                    List.of("session=alice-rotated; Path=/")
                            )
                            : Map.of();
                    return new HttpExchangeEvidence(
                            true,
                            Optional.of(200),
                            "HTTP/1.1 200 OK\r\n\r\n{}",
                            "{}",
                            headers
                    );
                },
                ignored -> true,
                new SafetyGate(),
                ignored -> {
                },
                Clock.systemUTC(),
                executor
        )) {
            ExecutionRun run = coordinator.execute(
                    plan,
                    new ExecutionPolicy(5, Duration.ZERO, true, true),
                    false
            ).join();

            assertTrue(sentRequests.get(0).contains("Cookie: session=alice"));
            assertTrue(sentRequests.get(0).contains("Authorization: Bearer alice-token"));
            assertTrue(sentRequests.get(1).contains("Cookie: session=alice-rotated"));
            assertTrue(sentRequests.get(1).contains("Authorization: Bearer alice-token"));
            assertTrue(sentRequests.get(2).contains("Cookie: session=bob"));
            assertTrue(sentRequests.get(2).contains("Authorization: Bearer bob-token"));
            assertFalse(sentRequests.get(2).contains("alice"));
            assertEquals("Alice", run.stepResults().getFirst().actorName());
            assertEquals(
                    "alice-rotated",
                    run.actorCookies().get("Alice")
                            .get("https://example.test:443 :: session")
            );
            assertEquals(
                    "bob",
                    run.actorCookies().get("Bob")
                            .get("https://example.test:443 :: session")
            );
        }
    }

    @Test
    void appliesStaleOverridesToMutationRequests() {
        WorkflowStep consumer = step("Consumer", "GET", StepCategory.READ)
                .withRawRequest(
                        "GET /objects/${objectId} HTTP/1.1\r\nHost: example.test\r\n\r\n"
                );
        MutationCase mutation = new MutationCase(
                UUID.randomUUID(),
                "Use stale objectId",
                MutationType.STALE_VARIABLE,
                List.of(consumer),
                "Test stale value",
                Map.of("objectId", "retired-42")
        );
        List<String> requests = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = new ExecutionCoordinator(
                workflowStep -> {
                    requests.add(workflowStep.rawRequest());
                    return new HttpExchangeEvidence(
                            true,
                            Optional.of(200),
                            "HTTP/1.1 200 OK\r\n\r\n{}",
                            "{}"
                    );
                },
                ignored -> true,
                new SafetyGate(),
                ignored -> {
                },
                Clock.systemUTC(),
                executor
        )) {
            ExecutionRun run = coordinator.execute(
                    mutation,
                    new ExecutionPolicy(5, Duration.ZERO, true, true),
                    false
            ).join();

            assertEquals(RunStatus.COMPLETED, run.status());
            assertTrue(requests.getFirst().contains("/objects/retired-42"));
            assertTrue(run.messages().stream().anyMatch(
                    message -> message.contains("stale variable override")
            ));
        }
    }

    @Test
    void checksScopeAgainUsingTheRenderedEffectiveUrl() {
        WorkflowStep consumer = step("Consumer", "GET", StepCategory.READ)
                .withRawRequest(
                        "GET /objects/${objectId} HTTP/1.1\r\nHost: example.test\r\n\r\n"
                );
        MutationCase mutation = new MutationCase(
                UUID.randomUUID(),
                "Resolve target",
                MutationType.STALE_VARIABLE,
                List.of(consumer),
                "Resolve a dynamic path",
                Map.of("objectId", "outside")
        );
        List<String> scopedUrls = new ArrayList<>();
        List<UUID> sent = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = coordinator(
                sent,
                url -> {
                    scopedUrls.add(url);
                    return !url.endsWith("/objects/outside");
                },
                executor
        )) {
            ExecutionRun run = coordinator.execute(
                    mutation,
                    ExecutionPolicy.conservativeDefaults(),
                    false
            ).join();

            assertEquals(RunStatus.FAILED, run.status());
            assertTrue(sent.isEmpty());
            assertTrue(scopedUrls.contains("https://example.test/objects/outside"));
            assertTrue(run.stepResults().getFirst().errorMessage().orElseThrow()
                    .contains("outside the current Burp target scope"));
        }
    }

    @Test
    void recordsAndSendsTheRenderedEffectiveUrl() {
        WorkflowStep consumer = step("Consumer", "GET", StepCategory.READ)
                .withRawRequest(
                        "GET /objects/${objectId}?view=full HTTP/1.1\r\n"
                                + "Host: example.test\r\n\r\n"
                );
        MutationCase mutation = new MutationCase(
                UUID.randomUUID(),
                "Resolve target",
                MutationType.STALE_VARIABLE,
                List.of(consumer),
                "Resolve a dynamic path",
                Map.of("objectId", "42")
        );
        List<String> sentUrls = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = new ExecutionCoordinator(
                workflowStep -> {
                    sentUrls.add(workflowStep.url());
                    return new HttpExchangeEvidence(
                            true,
                            Optional.of(200),
                            "HTTP/1.1 200 OK\r\n\r\n{}",
                            "{}"
                    );
                },
                ignored -> true,
                new SafetyGate(),
                ignored -> {
                },
                Clock.systemUTC(),
                executor
        )) {
            ExecutionRun run = coordinator.execute(
                    mutation,
                    ExecutionPolicy.conservativeDefaults(),
                    false
            ).join();

            assertEquals(List.of("https://example.test/objects/42?view=full"), sentUrls);
            assertEquals(sentUrls.getFirst(), run.stepResults().getFirst().url());
        }
    }

    @Test
    void blocksConfiguredActorCredentialsAcrossMultipleOrigins() {
        ActorDefinition actor = ActorDefinition.create(
                "Alice",
                "session=alice",
                "Bearer alice-token",
                false
        );
        WorkflowStep first = step("First", "GET", StepCategory.READ)
                .withActorId(actor.id());
        WorkflowStep second = new WorkflowStep(
                UUID.randomUUID(),
                "Second",
                "GET",
                "https://other.test/second",
                "GET /second HTTP/1.1\r\nHost: other.test\r\n\r\n",
                StepCategory.READ,
                dev.workflowguard.domain.StepRole.ACTION,
                actor.id(),
                true,
                true
        );
        MutationCase mutation = mutation(first, second);
        ExecutionPlan plan = new ExecutionPlan(
                mutation,
                mutation.steps().stream()
                        .map(item -> new PlannedStep(ExecutionPhase.MUTATION, item))
                        .toList(),
                List.of(ActorDefinition.defaultActor(), actor),
                List.of(),
                List.of()
        );
        List<UUID> sent = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = coordinator(sent, ignored -> true, executor)) {
            ExecutionRun run = coordinator.execute(
                    plan,
                    ExecutionPolicy.conservativeDefaults(),
                    false
            ).join();

            assertEquals(RunStatus.BLOCKED, run.status());
            assertTrue(sent.isEmpty());
            assertTrue(run.messages().stream().anyMatch(
                    message -> message.contains("assigned to multiple origins")
            ));
        }
    }

    private ExecutionCoordinator coordinator(
            List<UUID> sent,
            dev.workflowguard.ports.ScopeChecker scopeChecker,
            java.util.concurrent.ExecutorService executor
    ) {
        return new ExecutionCoordinator(
                workflowStep -> {
                    sent.add(workflowStep.id());
                    return new HttpExchangeEvidence(true, Optional.of(204), "HTTP/1.1 204\r\n\r\n");
                },
                scopeChecker,
                new SafetyGate(),
                ignored -> {
                },
                Clock.systemUTC(),
                executor
        );
    }

    private MutationCase mutation(WorkflowStep... steps) {
        return new MutationCase(
                UUID.randomUUID(),
                "Test mutation",
                MutationType.REPEAT_STEP,
                List.of(steps),
                "Test sequence"
        );
    }
}
