package dev.workflowguard.application;

import dev.workflowguard.core.ExecutionPlanner;
import dev.workflowguard.core.SafetyGate;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.InvariantRule;
import dev.workflowguard.domain.JsonInvariant;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.ports.HttpExchangeEvidence;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.workflowguard.TestFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanIntegrationTest {
    @Test
    void resolvesVariablesAndEvaluatesBeforeAfterAndCleanupProbes() {
        var probe = step("Members", "GET", StepCategory.READ).withRole(StepRole.PROBE);
        var action = step("Replay", "POST", StepCategory.UPDATE).withRawRequest(
                "POST /accept HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "X-Workflow-Token: ${workflowToken}\r\n\r\n"
        );
        var cleanup = step("Cleanup", "DELETE", StepCategory.DELETE)
                .withRole(StepRole.CLEANUP);
        var variable = new VariableDefinition(
                "workflowToken",
                probe.id(),
                ExtractionType.JSON_POINTER,
                "/token",
                0
        );
        var invariant = ProbeInvariant.create(
                probe.id(),
                new JsonInvariant(
                        "Member collection restored",
                        "/members",
                        InvariantRule.UNCHANGED,
                        null
                )
        );
        var workflow = new Workflow(
                UUID.randomUUID(),
                "Probe lifecycle",
                List.of(probe, action, cleanup),
                List.of(variable),
                List.of(invariant),
                Instant.parse("2026-07-29T12:00:00Z")
        );
        var mutation = new MutationCase(
                UUID.randomUUID(),
                "Replay action",
                MutationType.REPLAY_EARLIER_STEP,
                List.of(action),
                "Replay after completion"
        );
        var plan = new ExecutionPlanner().plan(workflow, mutation);
        AtomicInteger probeCalls = new AtomicInteger();
        List<String> sentRequests = new ArrayList<>();
        List<Duration> delays = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();

        try (var coordinator = new ExecutionCoordinator(
                workflowStep -> {
                    sentRequests.add(workflowStep.rawRequest());
                    if (workflowStep.id().equals(probe.id())) {
                        String body = switch (probeCalls.getAndIncrement()) {
                            case 0 -> "{\"token\":\"abc-123\",\"members\":[]}";
                            case 1 -> "{\"token\":\"abc-123\",\"members\":[\"member-1\"]}";
                            default -> "{\"token\":\"abc-123\",\"members\":[]}";
                        };
                        return new HttpExchangeEvidence(
                                true,
                                Optional.of(200),
                                "HTTP/1.1 200 OK\r\n\r\n" + body,
                                body
                        );
                    }
                    return new HttpExchangeEvidence(
                            true,
                            Optional.of(204),
                            "HTTP/1.1 204 No Content\r\n\r\n",
                            ""
                    );
                },
                ignored -> true,
                new SafetyGate(),
                delays::add,
                Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC),
                executor
        )) {
            var run = coordinator.execute(
                    plan,
                    new ExecutionPolicy(5, Duration.ofMillis(50), true, true),
                    true
            ).join();

            assertEquals(RunStatus.COMPLETED, run.status());
            assertEquals(
                    List.of(
                            ExecutionPhase.BEFORE_PROBE,
                            ExecutionPhase.MUTATION,
                            ExecutionPhase.AFTER_PROBE,
                            ExecutionPhase.CLEANUP,
                            ExecutionPhase.POST_CLEANUP_PROBE
                    ),
                    run.stepResults().stream().map(result -> result.phase()).toList()
            );
            assertTrue(sentRequests.get(1).contains("X-Workflow-Token: abc-123"));
            assertEquals("abc-123", run.variables().get("workflowToken"));
            assertEquals(4, delays.size());
            assertEquals(2, run.invariantResults().size());
            assertFalse(run.invariantResults().getFirst().passed());
            assertTrue(run.invariantResults().getLast().passed());
        }
    }

    @Test
    void requestCapIncludesProbesAndCleanup() {
        var probe = step("Members", "GET", StepCategory.READ).withRole(StepRole.PROBE);
        var action = step("Replay", "POST", StepCategory.UPDATE);
        var cleanup = step("Cleanup", "DELETE", StepCategory.DELETE)
                .withRole(StepRole.CLEANUP);
        Workflow workflow = new Workflow(
                UUID.randomUUID(),
                "Cap",
                List.of(probe, action, cleanup),
                Instant.now()
        );
        MutationCase mutation = new MutationCase(
                UUID.randomUUID(),
                "Replay",
                MutationType.REPEAT_STEP,
                List.of(action),
                "Replay"
        );
        var plan = new ExecutionPlanner().plan(workflow, mutation);
        AtomicInteger sent = new AtomicInteger();

        try (var coordinator = new ExecutionCoordinator(
                ignored -> {
                    sent.incrementAndGet();
                    return new HttpExchangeEvidence(true, Optional.of(200), "", "{}");
                },
                ignored -> true
        )) {
            var run = coordinator.execute(
                    plan,
                    new ExecutionPolicy(4, Duration.ZERO, true, true),
                    true
            ).join();

            assertEquals(5, plan.steps().size());
            assertEquals(RunStatus.BLOCKED, run.status());
            assertEquals(0, sent.get());
        }
    }
}
