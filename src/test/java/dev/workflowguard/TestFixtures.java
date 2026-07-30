package dev.workflowguard;

import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static WorkflowStep step(String name, String method, StepCategory category) {
        return new WorkflowStep(
                UUID.nameUUIDFromBytes(name.getBytes()),
                name,
                method,
                "https://example.test/" + name.toLowerCase(),
                method + " /" + name.toLowerCase() + " HTTP/1.1\r\nHost: example.test\r\n\r\n",
                category,
                StepRole.ACTION,
                true,
                true
        );
    }

    public static Workflow workflow(WorkflowStep... steps) {
        return new Workflow(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Test workflow",
                List.of(steps),
                Instant.parse("2026-07-29T12:00:00Z")
        );
    }
}
