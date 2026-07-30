package dev.workflowguard.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record ActorDefinition(
        UUID id,
        String name,
        String initialCookieHeader,
        String initialAuthorizationHeader,
        boolean seedFromCapturedRequest
) {
    private static final int MAXIMUM_NAME_LENGTH = 128;
    private static final int MAXIMUM_HEADER_VALUE_LENGTH = 16_384;

    public static final UUID DEFAULT_ID = UUID.nameUUIDFromBytes(
            "workflowguard.default-actor".getBytes(StandardCharsets.UTF_8)
    );

    public ActorDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Actor name must not be blank");
        }
        if (name.length() > MAXIMUM_NAME_LENGTH || containsControlCharacter(name)) {
            throw new IllegalArgumentException(
                    "Actor name is too long or contains a control character"
            );
        }
        initialCookieHeader = validateHeaderSeed(
                "Initial Cookie",
                initialCookieHeader == null ? "" : initialCookieHeader.trim()
        );
        initialAuthorizationHeader = validateHeaderSeed(
                "Initial Authorization",
                initialAuthorizationHeader == null
                        ? ""
                        : initialAuthorizationHeader.trim()
        );
    }

    public ActorDefinition(UUID id, String name, String initialCookieHeader) {
        this(id, name, initialCookieHeader, "", false);
    }

    public ActorDefinition(
            UUID id,
            String name,
            String initialCookieHeader,
            boolean seedFromCapturedRequest
    ) {
        this(id, name, initialCookieHeader, "", seedFromCapturedRequest);
    }

    public static ActorDefinition defaultActor() {
        return new ActorDefinition(DEFAULT_ID, "Default actor", "", "", true);
    }

    public static ActorDefinition create(String name, String initialCookieHeader) {
        return new ActorDefinition(
                UUID.randomUUID(),
                name,
                initialCookieHeader,
                "",
                false
        );
    }

    public static ActorDefinition create(
            String name,
            String initialCookieHeader,
            boolean seedFromCapturedRequest
    ) {
        return create(name, initialCookieHeader, "", seedFromCapturedRequest);
    }

    public static ActorDefinition create(
            String name,
            String initialCookieHeader,
            String initialAuthorizationHeader,
            boolean seedFromCapturedRequest
    ) {
        return new ActorDefinition(
                UUID.randomUUID(),
                name,
                initialCookieHeader,
                initialAuthorizationHeader,
                seedFromCapturedRequest
        );
    }

    public boolean hasConfiguredCredentials() {
        return !initialCookieHeader.isBlank() || !initialAuthorizationHeader.isBlank();
    }

    private static String validateHeaderSeed(String label, String value) {
        if (value.length() > MAXIMUM_HEADER_VALUE_LENGTH) {
            throw new IllegalArgumentException(label + " value is too long");
        }
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException(
                    label + " value must not contain control characters"
            );
        }
        return value;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character ->
                Character.isISOControl(character) || character == 0x7f
        );
    }
}
