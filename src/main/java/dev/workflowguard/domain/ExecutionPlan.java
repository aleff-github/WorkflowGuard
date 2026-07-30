package dev.workflowguard.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ExecutionPlan(
        MutationCase mutationCase,
        List<PlannedStep> steps,
        List<ActorDefinition> actors,
        List<VariableDefinition> variables,
        List<ProbeInvariant> invariants,
        Set<String> volatileJsonPointers
) {
    public ExecutionPlan {
        Objects.requireNonNull(mutationCase, "mutationCase");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
        variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
        invariants = List.copyOf(Objects.requireNonNull(invariants, "invariants"));
        volatileJsonPointers = volatileJsonPointers == null
                ? Set.of()
                : Set.copyOf(volatileJsonPointers);
    }

    public ExecutionPlan(
            MutationCase mutationCase,
            List<PlannedStep> steps,
            List<ActorDefinition> actors,
            List<VariableDefinition> variables,
            List<ProbeInvariant> invariants
    ) {
        this(mutationCase, steps, actors, variables, invariants, Set.of());
    }

    public ExecutionPlan(
            MutationCase mutationCase,
            List<PlannedStep> steps,
            List<VariableDefinition> variables,
            List<ProbeInvariant> invariants
    ) {
        this(
                mutationCase,
                steps,
                List.of(ActorDefinition.defaultActor()),
                variables,
                invariants,
                Set.of()
        );
    }
}
