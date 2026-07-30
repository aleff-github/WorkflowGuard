package dev.workflowguard.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Workflow(
        UUID id,
        String name,
        List<WorkflowStep> steps,
        List<ActorDefinition> actors,
        List<VariableDefinition> variables,
        List<ProbeInvariant> invariants,
        Set<String> volatileJsonPointers,
        Instant createdAt
) {
    public Workflow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        actors = actors == null ? List.of() : List.copyOf(actors);
        if (actors.stream().noneMatch(actor -> actor.id().equals(ActorDefinition.DEFAULT_ID))) {
            var withDefault = new ArrayList<ActorDefinition>();
            withDefault.add(ActorDefinition.defaultActor());
            withDefault.addAll(actors);
            actors = List.copyOf(withDefault);
        }
        variables = variables == null ? List.of() : List.copyOf(variables);
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
        volatileJsonPointers = volatileJsonPointers == null
                ? Set.of()
                : Set.copyOf(volatileJsonPointers);
        volatileJsonPointers.forEach(pointer -> {
            if (!pointer.isEmpty() && !pointer.startsWith("/")) {
                throw new IllegalArgumentException(
                        "Volatile JSON pointers must be empty or start with '/'"
                );
            }
        });
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public Workflow(UUID id, String name, List<WorkflowStep> steps, Instant createdAt) {
        this(id, name, steps, List.of(), List.of(), List.of(), Set.of(), createdAt);
    }

    public Workflow(
            UUID id,
            String name,
            List<WorkflowStep> steps,
            List<VariableDefinition> variables,
            List<ProbeInvariant> invariants,
            Instant createdAt
    ) {
        this(id, name, steps, List.of(), variables, invariants, Set.of(), createdAt);
    }

    public Workflow(
            UUID id,
            String name,
            List<WorkflowStep> steps,
            List<ActorDefinition> actors,
            List<VariableDefinition> variables,
            List<ProbeInvariant> invariants,
            Instant createdAt
    ) {
        this(id, name, steps, actors, variables, invariants, Set.of(), createdAt);
    }

    public static Workflow create(String name) {
        return new Workflow(
                UUID.randomUUID(),
                name,
                List.of(),
                List.of(ActorDefinition.defaultActor()),
                List.of(),
                List.of(),
                Set.of(),
                Instant.now()
        );
    }

    public Workflow append(List<WorkflowStep> additionalSteps) {
        Objects.requireNonNull(additionalSteps, "additionalSteps");
        var combined = new ArrayList<>(steps);
        combined.addAll(additionalSteps);
        return new Workflow(
                id, name, combined, actors, variables, invariants, volatileJsonPointers, createdAt
        );
    }

    public Workflow rename(String newName) {
        return new Workflow(
                id, newName, steps, actors, variables, invariants, volatileJsonPointers, createdAt
        );
    }

    public Workflow withSteps(List<WorkflowStep> newSteps) {
        return new Workflow(
                id, name, newSteps, actors, variables, invariants, volatileJsonPointers, createdAt
        );
    }

    public Workflow withActors(List<ActorDefinition> newActors) {
        return new Workflow(
                id, name, steps, newActors, variables, invariants, volatileJsonPointers, createdAt
        );
    }

    public Workflow withVariables(List<VariableDefinition> newVariables) {
        return new Workflow(
                id, name, steps, actors, newVariables, invariants, volatileJsonPointers, createdAt
        );
    }

    public Workflow withInvariants(List<ProbeInvariant> newInvariants) {
        return new Workflow(
                id, name, steps, actors, variables, newInvariants, volatileJsonPointers, createdAt
        );
    }

    public Workflow withVolatileJsonPointers(Set<String> pointers) {
        return new Workflow(id, name, steps, actors, variables, invariants, pointers, createdAt);
    }
}
