package dev.workflowguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticJsonDiffTest {
    private final SemanticJsonDiff diff = new SemanticJsonDiff(new ObjectMapper());

    @Test
    void ignoresVolatileSubtreesAndReportsMeaningfulChanges() {
        String before = """
                {"request":{"id":"one","timestamp":1},"members":["a","b"],"active":false}
                """;
        String after = """
                {"request":{"id":"two","timestamp":2},"members":["a","b","c"],"active":true}
                """;

        var differences = diff.compare(
                before,
                after,
                new JsonDiffOptions(Set.of("/request"), false)
        );

        assertEquals(2, differences.size());
        assertTrue(differences.stream().anyMatch(item -> item.jsonPointer().equals("/members/2")));
        assertTrue(differences.stream().anyMatch(item -> item.jsonPointer().equals("/active")));
    }

    @Test
    void canTreatArrayOrderingAsVolatile() {
        var differences = diff.compare(
                "{\"roles\":[\"viewer\",\"editor\"]}",
                "{\"roles\":[\"editor\",\"viewer\"]}",
                new JsonDiffOptions(Set.of(), true)
        );

        assertTrue(differences.isEmpty());
    }
}
