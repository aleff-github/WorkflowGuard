package dev.workflowguard.core;

import java.util.Objects;
import java.util.Set;

public record JsonDiffOptions(
        Set<String> ignoredPointers,
        boolean ignoreArrayOrder
) {
    public JsonDiffOptions {
        ignoredPointers = Set.copyOf(Objects.requireNonNull(ignoredPointers, "ignoredPointers"));
        for (String pointer : ignoredPointers) {
            if (!pointer.isEmpty() && !pointer.startsWith("/")) {
                throw new IllegalArgumentException("Ignored JSON pointers must be empty or start with '/'");
            }
        }
    }

    public static JsonDiffOptions standard() {
        return new JsonDiffOptions(Set.of(), false);
    }
}
