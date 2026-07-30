package dev.workflowguard.core;

import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.InvariantCheckResult;
import dev.workflowguard.domain.RunAssessment;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepExecutionResult;
import dev.workflowguard.domain.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionRunSummarizerTest {
    private final ExecutionRunSummarizer summarizer = new ExecutionRunSummarizer();

    @Test
    void summarizesMutationHttpActorsAndRestoredStateViolation() {
        InvariantCheckResult mutationFailure = invariant(
                ExecutionPhase.AFTER_PROBE,
                false
        );
        InvariantCheckResult cleanupPass = invariant(
                ExecutionPhase.POST_CLEANUP_PROBE,
                true
        );
        ExecutionRun run = run(
                RunStatus.COMPLETED,
                List.of(
                        result("Alice", 200),
                        result("Bob", 409)
                ),
                List.of(mutationFailure, cleanupPass)
        );

        var summary = summarizer.summarize(run);

        assertEquals("Alice, Bob", summary.actors());
        assertEquals("200 → 409", summary.mutationHttpStatuses());
        assertEquals(1, summary.passedInvariantChecks());
        assertEquals(1, summary.mutationViolations());
        assertEquals(0, summary.cleanupViolations());
        assertEquals(RunAssessment.MUTATION_VIOLATION_RESTORED, summary.assessment());
    }

    @Test
    void reportsCleanupFailureWithPriority() {
        ExecutionRun run = run(
                RunStatus.COMPLETED,
                List.of(result("Alice", 204)),
                List.of(
                        invariant(ExecutionPhase.AFTER_PROBE, false),
                        invariant(ExecutionPhase.POST_CLEANUP_PROBE, false)
                )
        );

        assertEquals(
                RunAssessment.CLEANUP_FAILED,
                summarizer.summarize(run).assessment()
        );
    }

    @Test
    void distinguishesRunsWithoutStateChecksAndExecutionFailures() {
        assertEquals(
                RunAssessment.NOT_EVALUATED,
                summarizer.summarize(run(
                        RunStatus.COMPLETED,
                        List.of(result("Alice", 200)),
                        List.of()
                )).assessment()
        );
        assertEquals(
                RunAssessment.EXECUTION_FAILED,
                summarizer.summarize(run(
                        RunStatus.FAILED,
                        List.of(result("Alice", 500)),
                        List.of()
                )).assessment()
        );
    }

    @Test
    void compactsOldRunsWithoutRetainingHttpOrExtractedValues() {
        ExecutionRun compacted = run(
                RunStatus.COMPLETED,
                List.of(result("Alice", 200)),
                List.of(invariant(ExecutionPhase.AFTER_PROBE, false))
        ).withoutHttpEvidence();

        assertEquals("", compacted.stepResults().getFirst().rawRequest());
        assertEquals("", compacted.stepResults().getFirst().rawResponse());
        assertEquals("", compacted.stepResults().getFirst().responseBody());
        assertTrue(compacted.variables().isEmpty());
        assertTrue(compacted.actorCookies().values().stream().allMatch(Map::isEmpty));
        assertTrue(compacted.invariantResults().getFirst().violations().stream()
                .allMatch(violation ->
                        violation.beforeValue() == null && violation.afterValue() == null
                ));
        assertTrue(compacted.messages().stream().anyMatch(
                message -> message.contains("discarded")
        ));
    }

    private ExecutionRun run(
            RunStatus status,
            List<StepExecutionResult> results,
            List<InvariantCheckResult> invariants
    ) {
        Instant started = Instant.parse("2026-07-29T12:00:00Z");
        return new ExecutionRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Preset case",
                status,
                started,
                started.plusMillis(25),
                results,
                Map.of(),
                Map.of(),
                invariants,
                List.of()
        );
    }

    private StepExecutionResult result(String actorName, int statusCode) {
        return new StepExecutionResult(
                UUID.randomUUID(),
                "Action",
                "POST",
                "https://example.test/action",
                ActorDefinition.DEFAULT_ID,
                actorName,
                ExecutionPhase.MUTATION,
                Instant.parse("2026-07-29T12:00:00Z"),
                Duration.ofMillis(5),
                StepExecutionStatus.RESPONSE_RECEIVED,
                Optional.of(statusCode),
                "POST /action HTTP/1.1\r\nHost: example.test\r\n\r\n",
                "HTTP/1.1 " + statusCode + "\r\n\r\n{}",
                "{}",
                Map.of(),
                Optional.empty()
        );
    }

    private InvariantCheckResult invariant(ExecutionPhase phase, boolean passed) {
        return new InvariantCheckResult(
                UUID.randomUUID(),
                "State unchanged",
                UUID.randomUUID(),
                phase,
                passed
                        ? List.of()
                        : List.of(new InvariantViolation(
                                "State unchanged",
                                "/count",
                                "Value changed",
                                "0",
                                "1"
                        )),
                Optional.empty()
        );
    }
}
