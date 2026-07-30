package dev.workflowguard.core;

import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionPlan;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.PlannedStep;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ExecutionPlanner {
    public ExecutionPlan plan(Workflow workflow, MutationCase mutationCase) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(mutationCase, "mutationCase");

        List<WorkflowStep> probes = enabledSteps(workflow, StepRole.PROBE);
        List<WorkflowStep> cleanup = enabledSteps(workflow, StepRole.CLEANUP);
        List<PlannedStep> planned = new ArrayList<>();

        add(planned, ExecutionPhase.BEFORE_PROBE, probes);
        add(planned, ExecutionPhase.MUTATION, mutationCase.steps());
        add(planned, ExecutionPhase.AFTER_PROBE, probes);
        add(planned, ExecutionPhase.CLEANUP, cleanup);
        if (!cleanup.isEmpty()) {
            add(planned, ExecutionPhase.POST_CLEANUP_PROBE, probes);
        }

        return new ExecutionPlan(
                mutationCase,
                planned,
                workflow.actors(),
                workflow.variables(),
                workflow.invariants(),
                workflow.volatileJsonPointers()
        );
    }

    private List<WorkflowStep> enabledSteps(Workflow workflow, StepRole role) {
        return workflow.steps().stream()
                .filter(WorkflowStep::enabled)
                .filter(step -> step.role() == role)
                .toList();
    }

    private void add(
            List<PlannedStep> target,
            ExecutionPhase phase,
            List<WorkflowStep> steps
    ) {
        steps.forEach(step -> target.add(new PlannedStep(phase, step)));
    }
}
