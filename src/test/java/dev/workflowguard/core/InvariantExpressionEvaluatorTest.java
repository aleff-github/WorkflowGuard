package dev.workflowguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantExpressionEvaluatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InvariantExpressionEvaluator evaluator =
            new InvariantExpressionEvaluator(objectMapper);

    @Test
    void evaluatesReferencesLogicalOperatorsAndLifecycleFunctions() throws Exception {
        var before = objectMapper.readTree("""
                {"count":2,"owner":"alice","token":"old"}
                """);
        var after = objectMapper.readTree("""
                {"count":3,"owner":"alice","token":"new"}
                """);

        assertTrue(evaluator.evaluate(
                "after:/count > before:/count && unchanged(/owner) && changed(/token)",
                before,
                after
        ));
        assertTrue(evaluator.evaluate(
                "exists(after:/owner) && after:/owner == \"alice\"",
                before,
                after
        ));
        assertFalse(evaluator.evaluate("after:/count <= 2", before, after));
    }

    @Test
    void rejectsAnythingOutsideTheRestrictedGrammar() throws Exception {
        var document = objectMapper.readTree("{}");

        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(
                "Runtime.getRuntime().exec(\"calc\")",
                document,
                document
        ));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(
                "after:/missing > \"text\"",
                document,
                document
        ));
    }
}
