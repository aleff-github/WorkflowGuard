package dev.workflowguard.core;

import dev.workflowguard.domain.DependencyConfidence;
import dev.workflowguard.domain.DependencyType;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowDependency;
import dev.workflowguard.domain.WorkflowStep;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class WorkflowDependencySuggester {
    public List<WorkflowDependency> suggest(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        List<WorkflowStep> enabled = workflow.steps().stream()
                .filter(WorkflowStep::enabled)
                .toList();
        Map<UUID, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < enabled.size(); index++) {
            positions.put(enabled.get(index).id(), index);
        }

        List<WorkflowDependency> suggestions = new ArrayList<>();
        addVariableDependencies(workflow.variables(), enabled, positions, suggestions);
        addLifecycleDependencies(enabled, suggestions);
        addObservedOrder(enabled, suggestions);
        return List.copyOf(suggestions);
    }

    private void addVariableDependencies(
            List<VariableDefinition> variables,
            List<WorkflowStep> steps,
            Map<UUID, Integer> positions,
            List<WorkflowDependency> target
    ) {
        for (VariableDefinition variable : variables) {
            Integer sourcePosition = positions.get(variable.sourceStepId());
            if (sourcePosition == null) {
                continue;
            }
            for (WorkflowStep consumer : steps) {
                Integer consumerPosition = positions.get(consumer.id());
                if (consumerPosition == null
                        || consumerPosition <= sourcePosition
                        || consumer.id().equals(variable.sourceStepId())) {
                    continue;
                }
                if (consumer.rawRequest().contains("${" + variable.name() + "}")) {
                    target.add(dependency(
                            variable.sourceStepId(),
                            consumer.id(),
                            DependencyType.VARIABLE_DATA,
                            DependencyConfidence.HIGH,
                            "Response variable ${" + variable.name()
                                    + "} is consumed by the target request."
                    ));
                }
            }
        }
    }

    private void addLifecycleDependencies(
            List<WorkflowStep> steps,
            List<WorkflowDependency> target
    ) {
        for (int targetIndex = 1; targetIndex < steps.size(); targetIndex++) {
            WorkflowStep step = steps.get(targetIndex);
            Set<StepCategory> expectedSources = expectedSourceCategories(step);
            if (expectedSources.isEmpty() && step.role() != StepRole.CLEANUP) {
                continue;
            }
            int sourceIndex = closestLifecycleSource(
                    steps,
                    targetIndex,
                    expectedSources,
                    step.role() == StepRole.CLEANUP
            );
            if (sourceIndex < 0) {
                continue;
            }
            WorkflowStep source = steps.get(sourceIndex);
            target.add(dependency(
                    source.id(),
                    step.id(),
                    DependencyType.RESOURCE_LIFECYCLE,
                    lifecycleConfidence(source, step),
                    lifecycleReason(source, step)
            ));
        }
    }

    private Set<StepCategory> expectedSourceCategories(WorkflowStep target) {
        return switch (target.category()) {
            case ONE_TIME_USE -> Set.of(
                    StepCategory.INVITE,
                    StepCategory.CREATE,
                    StepCategory.UPDATE
            );
            case UPDATE -> Set.of(StepCategory.CREATE, StepCategory.INVITE);
            case REVOKE -> Set.of(
                    StepCategory.ONE_TIME_USE,
                    StepCategory.UPDATE,
                    StepCategory.INVITE
            );
            case DELETE -> Set.of(
                    StepCategory.CREATE,
                    StepCategory.UPDATE,
                    StepCategory.ONE_TIME_USE,
                    StepCategory.INVITE,
                    StepCategory.PAYMENT
            );
            case PAYMENT -> Set.of(StepCategory.CREATE, StepCategory.UPDATE);
            default -> Set.of();
        };
    }

    private int closestLifecycleSource(
            List<WorkflowStep> steps,
            int targetIndex,
            Set<StepCategory> expectedSources,
            boolean cleanup
    ) {
        for (int index = targetIndex - 1; index >= 0; index--) {
            WorkflowStep candidate = steps.get(index);
            if (candidate.role() != StepRole.ACTION) {
                continue;
            }
            if (cleanup
                    ? candidate.category().isStateChanging()
                    : expectedSources.contains(candidate.category())) {
                return index;
            }
        }
        return -1;
    }

    private DependencyConfidence lifecycleConfidence(
            WorkflowStep source,
            WorkflowStep target
    ) {
        if (target.category() == StepCategory.ONE_TIME_USE
                && (source.category() == StepCategory.INVITE
                || source.category() == StepCategory.CREATE)) {
            return DependencyConfidence.HIGH;
        }
        if (target.category() == StepCategory.REVOKE
                && source.category() == StepCategory.ONE_TIME_USE) {
            return DependencyConfidence.HIGH;
        }
        return DependencyConfidence.MEDIUM;
    }

    private String lifecycleReason(WorkflowStep source, WorkflowStep target) {
        if (target.role() == StepRole.CLEANUP) {
            return "Cleanup follows the nearest earlier state-changing action.";
        }
        return target.category() + " commonly depends on an earlier "
                + source.category() + " lifecycle step.";
    }

    private void addObservedOrder(
            List<WorkflowStep> steps,
            List<WorkflowDependency> target
    ) {
        List<WorkflowStep> actions = steps.stream()
                .filter(step -> step.role() == StepRole.ACTION)
                .toList();
        Set<String> existingPairs = new HashSet<>();
        target.forEach(dependency -> existingPairs.add(pairKey(
                dependency.sourceStepId(),
                dependency.targetStepId()
        )));
        for (int index = 1; index < actions.size(); index++) {
            WorkflowStep source = actions.get(index - 1);
            WorkflowStep destination = actions.get(index);
            String pair = pairKey(source.id(), destination.id());
            if (existingPairs.add(pair)) {
                target.add(dependency(
                        source.id(),
                        destination.id(),
                        DependencyType.OBSERVED_ORDER,
                        DependencyConfidence.LOW,
                        "The target immediately follows the source in the captured action order."
                ));
            }
        }
    }

    private WorkflowDependency dependency(
            UUID sourceStepId,
            UUID targetStepId,
            DependencyType type,
            DependencyConfidence confidence,
            String reason
    ) {
        String stableKey = sourceStepId + ":" + targetStepId + ":" + type;
        return new WorkflowDependency(
                UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)),
                sourceStepId,
                targetStepId,
                type,
                confidence,
                reason
        );
    }

    private String pairKey(UUID source, UUID target) {
        return source + ":" + target;
    }
}
