package dev.workflowguard.core;

import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MutationEngine {
    public List<MutationCase> generate(
            Workflow workflow,
            Set<MutationType> enabledTypes,
            int maximumCases
    ) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(enabledTypes, "enabledTypes");
        if (maximumCases < 1) {
            throw new IllegalArgumentException("maximumCases must be positive");
        }

        Workflow executableWorkflow = workflow.withSteps(
                workflow.steps().stream()
                        .filter(WorkflowStep::enabled)
                        .filter(step -> step.role() == StepRole.ACTION)
                        .toList()
        );
        EnumSet<MutationType> enabled = enabledTypes.isEmpty()
                ? EnumSet.noneOf(MutationType.class)
                : EnumSet.copyOf(enabledTypes);
        List<MutationCase> cases = new ArrayList<>();

        for (MutationType type : MutationType.values()) {
            if (!enabled.contains(type)) {
                continue;
            }
            switch (type) {
                case REPLAY_AFTER_REVOKE -> addReplayAfterBoundaryCases(
                        executableWorkflow,
                        StepCategory.REVOKE,
                        type,
                        "revocation",
                        cases,
                        maximumCases
                );
                case REPLAY_AFTER_DELETE -> addReplayAfterBoundaryCases(
                        executableWorkflow,
                        StepCategory.DELETE,
                        type,
                        "deletion",
                        cases,
                        maximumCases
                );
                case REPLAY_ONE_TIME_USE -> addOneTimeReplayCases(
                        executableWorkflow,
                        cases,
                        maximumCases
                );
                case SKIP_STEP -> addSkipCases(executableWorkflow, cases, maximumCases);
                case REPEAT_STEP -> addRepeatCases(executableWorkflow, cases, maximumCases);
                case REPLAY_EARLIER_STEP -> addReplayCases(executableWorkflow, cases, maximumCases);
                case SWAP_ACTOR -> addActorSwapCases(
                        executableWorkflow,
                        cases,
                        maximumCases
                );
                case STALE_VARIABLE -> addStaleVariableCases(
                        executableWorkflow,
                        cases,
                        maximumCases
                );
            }
            if (cases.size() >= maximumCases) {
                break;
            }
        }

        return List.copyOf(cases);
    }

    private void addSkipCases(Workflow workflow, List<MutationCase> cases, int maximumCases) {
        if (workflow.steps().size() < 2) {
            return;
        }
        for (int index = 0; index < workflow.steps().size() && cases.size() < maximumCases; index++) {
            WorkflowStep skipped = workflow.steps().get(index);
            List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
            steps.remove(index);
            cases.add(mutationCase(
                    workflow,
                    MutationType.SKIP_STEP,
                    index,
                    "Skip " + skipped.name(),
                    steps,
                    "Executes the workflow without step " + skipped.name() + "."
            ));
        }
    }

    private void addReplayAfterBoundaryCases(
            Workflow workflow,
            StepCategory boundaryCategory,
            MutationType type,
            String boundaryLabel,
            List<MutationCase> cases,
            int maximumCases
    ) {
        for (int boundaryIndex = 1;
             boundaryIndex < workflow.steps().size() && cases.size() < maximumCases;
             boundaryIndex++) {
            WorkflowStep boundary = workflow.steps().get(boundaryIndex);
            if (boundary.category() != boundaryCategory) {
                continue;
            }
            int replayIndex = replayCandidateBefore(workflow.steps(), boundaryIndex);
            if (replayIndex < 0) {
                continue;
            }
            WorkflowStep replayed = workflow.steps().get(replayIndex);
            List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
            steps.add(boundaryIndex + 1, replayed);
            cases.add(mutationCase(
                    workflow,
                    type,
                    boundaryIndex,
                    replayed.id().toString(),
                    "Replay " + replayed.name() + " after " + boundaryLabel,
                    steps,
                    "Replays " + replayed.name() + " immediately after "
                            + boundary.name() + " to test stale authorization or object state."
            ));
        }
    }

    private int replayCandidateBefore(List<WorkflowStep> steps, int boundaryIndex) {
        for (int index = boundaryIndex - 1; index >= 0; index--) {
            if (steps.get(index).category() == StepCategory.ONE_TIME_USE) {
                return index;
            }
        }
        for (int index = boundaryIndex - 1; index >= 0; index--) {
            WorkflowStep candidate = steps.get(index);
            if (candidate.category().isStateChanging()
                    && candidate.category() != StepCategory.REVOKE
                    && candidate.category() != StepCategory.DELETE) {
                return index;
            }
        }
        return -1;
    }

    private void addOneTimeReplayCases(
            Workflow workflow,
            List<MutationCase> cases,
            int maximumCases
    ) {
        for (int index = 0;
             index < workflow.steps().size() && cases.size() < maximumCases;
             index++) {
            WorkflowStep step = workflow.steps().get(index);
            if (step.category() != StepCategory.ONE_TIME_USE) {
                continue;
            }
            List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
            steps.add(index + 1, step);
            cases.add(mutationCase(
                    workflow,
                    MutationType.REPLAY_ONE_TIME_USE,
                    index,
                    "Replay one-time step " + step.name(),
                    steps,
                    "Executes " + step.name()
                            + " a second time to verify single-use enforcement."
            ));
        }
    }

    private void addRepeatCases(Workflow workflow, List<MutationCase> cases, int maximumCases) {
        for (int index = 0; index < workflow.steps().size() && cases.size() < maximumCases; index++) {
            WorkflowStep repeated = workflow.steps().get(index);
            List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
            steps.add(index + 1, repeated);
            cases.add(mutationCase(
                    workflow,
                    MutationType.REPEAT_STEP,
                    index,
                    "Repeat " + repeated.name(),
                    steps,
                    "Executes " + repeated.name() + " twice in immediate succession."
            ));
        }
    }

    private void addReplayCases(Workflow workflow, List<MutationCase> cases, int maximumCases) {
        for (int index = 0;
             index < workflow.steps().size() - 1 && cases.size() < maximumCases;
             index++) {
            WorkflowStep replayed = workflow.steps().get(index);
            List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
            steps.add(replayed);
            cases.add(mutationCase(
                    workflow,
                    MutationType.REPLAY_EARLIER_STEP,
                    index,
                    "Replay " + replayed.name() + " after completion",
                    steps,
                    "Replays the earlier step after the legitimate sequence has completed."
            ));
        }
    }

    private void addActorSwapCases(
            Workflow workflow,
            List<MutationCase> cases,
            int maximumCases
    ) {
        if (workflow.actors().size() < 2) {
            return;
        }
        for (int stepIndex = 0;
             stepIndex < workflow.steps().size() && cases.size() < maximumCases;
             stepIndex++) {
            WorkflowStep original = workflow.steps().get(stepIndex);
            for (ActorDefinition actor : workflow.actors()) {
                if (cases.size() >= maximumCases) {
                    return;
                }
                if (actor.id().equals(original.actorId())) {
                    continue;
                }
                List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
                steps.set(stepIndex, original.withActorId(actor.id()));
                cases.add(mutationCase(
                        workflow,
                        MutationType.SWAP_ACTOR,
                        stepIndex,
                        actor.id().toString(),
                        "Run " + original.name() + " as " + actor.name(),
                        steps,
                        "Executes " + original.name() + " with the isolated session for "
                                + actor.name() + "."
                ));
            }
        }
    }

    private void addStaleVariableCases(
            Workflow workflow,
            List<MutationCase> cases,
            int maximumCases
    ) {
        for (VariableDefinition variable : workflow.variables()) {
            if (cases.size() >= maximumCases) {
                return;
            }
            if (!variable.hasStaleValue() || !usesVariable(workflow.steps(), variable.name())) {
                continue;
            }
            cases.add(mutationCase(
                    workflow,
                    MutationType.STALE_VARIABLE,
                    workflow.steps().indexOf(firstVariableConsumer(
                            workflow.steps(),
                            variable.name()
                    )),
                    variable.name(),
                    "Use stale " + variable.name(),
                    workflow.steps(),
                    "Overrides ${" + variable.name()
                            + "} with its configured stale object or token value.",
                    Map.of(variable.name(), variable.staleValue())
            ));
        }
    }

    private boolean usesVariable(List<WorkflowStep> steps, String variableName) {
        return firstVariableConsumer(steps, variableName) != null;
    }

    private WorkflowStep firstVariableConsumer(
            List<WorkflowStep> steps,
            String variableName
    ) {
        String placeholder = "${" + variableName + "}";
        return steps.stream()
                .filter(step -> step.rawRequest().contains(placeholder))
                .findFirst()
                .orElse(null);
    }

    private MutationCase mutationCase(
            Workflow workflow,
            MutationType type,
            int stepIndex,
            String name,
            List<WorkflowStep> steps,
            String description
    ) {
        return mutationCase(
                workflow,
                type,
                stepIndex,
                "",
                name,
                steps,
                description,
                Map.of()
        );
    }

    private MutationCase mutationCase(
            Workflow workflow,
            MutationType type,
            int stepIndex,
            String discriminator,
            String name,
            List<WorkflowStep> steps,
            String description
    ) {
        return mutationCase(
                workflow,
                type,
                stepIndex,
                discriminator,
                name,
                steps,
                description,
                Map.of()
        );
    }

    private MutationCase mutationCase(
            Workflow workflow,
            MutationType type,
            int stepIndex,
            String discriminator,
            String name,
            List<WorkflowStep> steps,
            String description,
            Map<String, String> variableOverrides
    ) {
        String stableKey = workflow.id() + ":" + type + ":" + stepIndex + ":" + discriminator;
        UUID id = UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8));
        return new MutationCase(id, name, type, steps, description, variableOverrides);
    }
}
