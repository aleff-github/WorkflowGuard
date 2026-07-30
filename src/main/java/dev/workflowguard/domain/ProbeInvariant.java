package dev.workflowguard.domain;

import java.util.Objects;
import java.util.UUID;

public record ProbeInvariant(
        UUID id,
        UUID probeStepId,
        JsonInvariant invariant
) {
    public ProbeInvariant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(probeStepId, "probeStepId");
        Objects.requireNonNull(invariant, "invariant");
    }

    public static ProbeInvariant create(UUID probeStepId, JsonInvariant invariant) {
        return new ProbeInvariant(UUID.randomUUID(), probeStepId, invariant);
    }
}
