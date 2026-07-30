package dev.workflowguard.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record WorkflowStep(
        UUID id,
        String name,
        String method,
        String url,
        String rawRequest,
        StepCategory category,
        StepRole role,
        UUID actorId,
        boolean enabled,
        boolean inScope
) {
    public WorkflowStep {
        Objects.requireNonNull(id, "id");
        name = requireText(name, "name");
        method = requireText(method, "method").toUpperCase(Locale.ROOT);
        url = requireText(url, "url");
        rawRequest = requireText(rawRequest, "rawRequest");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(role, "role");
        actorId = actorId == null ? ActorDefinition.DEFAULT_ID : actorId;
    }

    public WorkflowStep(
            UUID id,
            String name,
            String method,
            String url,
            String rawRequest,
            StepCategory category,
            StepRole role,
            boolean enabled,
            boolean inScope
    ) {
        this(
                id,
                name,
                method,
                url,
                rawRequest,
                category,
                role,
                ActorDefinition.DEFAULT_ID,
                enabled,
                inScope
        );
    }

    public WorkflowStep withCategory(StepCategory newCategory) {
        return new WorkflowStep(
                id, name, method, url, rawRequest, newCategory, role, actorId, enabled, inScope
        );
    }

    public WorkflowStep withRole(StepRole newRole) {
        return new WorkflowStep(
                id, name, method, url, rawRequest, category, newRole, actorId, enabled, inScope
        );
    }

    public WorkflowStep withEnabled(boolean newEnabled) {
        return new WorkflowStep(
                id, name, method, url, rawRequest, category, role, actorId, newEnabled, inScope
        );
    }

    public WorkflowStep withInScope(boolean newInScope) {
        return new WorkflowStep(
                id, name, method, url, rawRequest, category, role, actorId, enabled, newInScope
        );
    }

    public WorkflowStep withRawRequest(String newRawRequest) {
        return new WorkflowStep(
                id, name, method, url, newRawRequest, category, role, actorId, enabled, inScope
        );
    }

    public WorkflowStep withEffectiveRequest(String effectiveUrl, String newRawRequest) {
        return new WorkflowStep(
                id,
                name,
                method,
                effectiveUrl,
                newRawRequest,
                category,
                role,
                actorId,
                enabled,
                inScope
        );
    }

    public WorkflowStep withActorId(UUID newActorId) {
        return new WorkflowStep(
                id,
                name,
                method,
                url,
                rawRequest,
                category,
                role,
                Objects.requireNonNull(newActorId, "newActorId"),
                enabled,
                inScope
        );
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
