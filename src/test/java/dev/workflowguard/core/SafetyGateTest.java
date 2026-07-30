package dev.workflowguard.core;

import dev.workflowguard.TestFixtures;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.WorkflowStep;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyGateTest {
    private final SafetyGate gate = new SafetyGate();

    @Test
    void requiresConfirmationForStateChangingCases() {
        var create = TestFixtures.step("S1", "POST", StepCategory.CREATE);

        var rejected = gate.evaluate(
                List.of(create),
                ExecutionPolicy.conservativeDefaults(),
                false
        );
        var accepted = gate.evaluate(
                List.of(create),
                ExecutionPolicy.conservativeDefaults(),
                true
        );

        assertFalse(rejected.allowed());
        assertTrue(accepted.allowed());
    }

    @Test
    void rejectsOutOfScopeAndOversizedCases() {
        WorkflowStep outsideScope = new WorkflowStep(
                UUID.randomUUID(),
                "S1",
                "GET",
                "https://outside.test/",
                "GET / HTTP/1.1\r\nHost: outside.test\r\n\r\n",
                StepCategory.READ,
                StepRole.ACTION,
                true,
                false
        );
        ExecutionPolicy policy = new ExecutionPolicy(1, Duration.ZERO, false, true);

        var decision = gate.evaluate(List.of(outsideScope, outsideScope), policy, true);

        assertFalse(decision.allowed());
        assertTrue(decision.reasons().size() >= 2);
    }

    @Test
    void blocksARequestWhoseRawMethodDiffersFromTheModel() {
        WorkflowStep disguisedDelete = TestFixtures.step(
                "Disguised delete",
                "GET",
                StepCategory.READ
        ).withRawRequest(
                "DELETE /resource HTTP/1.1\r\nHost: example.test\r\n\r\n"
        );

        var decision = gate.evaluate(
                List.of(disguisedDelete),
                ExecutionPolicy.conservativeDefaults(),
                true
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reasons().stream().anyMatch(
                reason -> reason.contains("does not match the modeled method")
        ));
    }

    @Test
    void enforcesTheAbsoluteRequestLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionPolicy(
                        ExecutionPolicy.ABSOLUTE_MAXIMUM_REQUESTS + 1,
                        Duration.ZERO,
                        true,
                        true
                )
        );
    }
}
