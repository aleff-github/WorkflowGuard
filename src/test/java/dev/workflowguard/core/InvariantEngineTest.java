package dev.workflowguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.domain.InvariantRule;
import dev.workflowguard.domain.JsonInvariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantEngineTest {
    private final InvariantEngine engine = new InvariantEngine(new ObjectMapper());

    @Test
    void detectsStateViolationsAfterReplay() {
        String before = """
                {"status":"revoked","members":["owner"],"token":null}
                """;
        String after = """
                {"status":"active","members":["owner","attacker"],"token":"new-token"}
                """;
        var invariants = List.of(
                new JsonInvariant("Status remains revoked", "/status", InvariantRule.EQUALS, "\"revoked\""),
                new JsonInvariant("Member count is stable", "/members", InvariantRule.ARRAY_SIZE_UNCHANGED, null),
                new JsonInvariant("Attacker stays absent", "/members", InvariantRule.MUST_NOT_CONTAIN, "\"attacker\""),
                new JsonInvariant("Access token stays absent", "/token", InvariantRule.ABSENT, null)
        );

        var violations = engine.evaluate(invariants, before, after);

        assertEquals(4, violations.size());
        assertTrue(violations.stream().anyMatch(item -> item.invariantName().equals("Status remains revoked")));
    }

    @Test
    void acceptsAnUnchangedValue() {
        var invariants = List.of(
                new JsonInvariant("Balance does not change", "/balance", InvariantRule.UNCHANGED, null)
        );

        assertTrue(engine.evaluate(invariants, "{\"balance\":10}", "{\"balance\":10}").isEmpty());
    }

    @Test
    void evaluatesExpressionsAndIgnoresConfiguredVolatilePaths() {
        var invariants = List.of(
                new JsonInvariant(
                        "Count can only grow",
                        "",
                        InvariantRule.EXPRESSION,
                        "after:/count >= before:/count && unchanged(/owner)"
                ),
                new JsonInvariant(
                        "Stable document",
                        "",
                        InvariantRule.UNCHANGED,
                        null
                )
        );

        assertTrue(engine.evaluate(
                invariants,
                "{\"count\":2,\"owner\":\"alice\",\"requestId\":\"one\"}",
                "{\"count\":2,\"owner\":\"alice\",\"requestId\":\"two\"}",
                Set.of("/requestId")
        ).isEmpty());
    }
}
