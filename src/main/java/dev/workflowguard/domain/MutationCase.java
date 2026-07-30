package dev.workflowguard.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MutationCase(
        UUID id,
        String name,
        MutationType type,
        List<WorkflowStep> steps,
        String description,
        Map<String, String> variableOverrides
) {
    public MutationCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        Objects.requireNonNull(description, "description");
        variableOverrides = variableOverrides == null
                ? Map.of()
                : Map.copyOf(variableOverrides);
    }

    public MutationCase(
            UUID id,
            String name,
            MutationType type,
            List<WorkflowStep> steps,
            String description
    ) {
        this(id, name, type, steps, description, Map.of());
    }
}
