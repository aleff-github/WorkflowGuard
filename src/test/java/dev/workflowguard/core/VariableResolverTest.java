package dev.workflowguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.VariableDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VariableResolverTest {
    private final VariableResolver resolver = new VariableResolver(new ObjectMapper());

    @Test
    void extractsRegexAndJsonPointerValues() {
        UUID source = UUID.randomUUID();
        var regex = new VariableDefinition(
                "inviteId",
                source,
                ExtractionType.REGEX,
                "\"id\"\\s*:\\s*(\\d+)",
                1
        );
        var pointer = new VariableDefinition(
                "token",
                source,
                ExtractionType.JSON_POINTER,
                "/session/token",
                0
        );

        assertEquals("183", resolver.extract(regex, "{\"id\":183}").orElseThrow());
        assertEquals(
                "abc-123",
                resolver.extract(pointer, "{\"session\":{\"token\":\"abc-123\"}}").orElseThrow()
        );
    }

    @Test
    void substitutesStrictPlaceholdersWithoutTreatingValuesAsRegex() {
        String request = "POST /invite/${inviteId}\r\nAuthorization: Bearer ${token}\r\n\r\n";

        String resolved = resolver.substitute(
                request,
                Map.of("inviteId", "183", "token", "$opaque\\value")
        );

        assertEquals(
                "POST /invite/183\r\nAuthorization: Bearer $opaque\\value\r\n\r\n",
                resolved
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.substitute("${missing}", Map.of())
        );
    }

    @Test
    void rejectsControlCharactersBeforeTheyReachAnHttpTemplate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.substitute(
                        "GET / HTTP/1.1\r\nX-Value: ${value}\r\n\r\n",
                        Map.of("value", "safe\r\nInjected: true")
                )
        );

        UUID source = UUID.randomUUID();
        var pointer = new VariableDefinition(
                "headerValue",
                source,
                ExtractionType.JSON_POINTER,
                "/value",
                0
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.extract(pointer, "{\"value\":\"line\\nbreak\"}")
        );
    }

    @Test
    void rejectsRegexFeaturesThatCannotBeEvaluatedByTheLinearTimeEngine() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VariableDefinition(
                        "unsafe",
                        UUID.randomUUID(),
                        ExtractionType.REGEX,
                        "(secret)\\1",
                        1
                )
        );
    }
}
