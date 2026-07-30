package dev.workflowguard.fixture;

import java.util.Locale;

public enum FixtureMode {
    SECURE,
    VULNERABLE;

    public static FixtureMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown fixture mode '" + value + "'. Expected secure or vulnerable.",
                    exception
            );
        }
    }
}
