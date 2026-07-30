package dev.workflowguard.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.domain.JsonInvariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class InvariantEngine {
    private final ObjectMapper objectMapper;
    private final InvariantExpressionEvaluator expressionEvaluator;
    private final SemanticJsonDiff semanticJsonDiff;

    public InvariantEngine(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        expressionEvaluator = new InvariantExpressionEvaluator(objectMapper);
        semanticJsonDiff = new SemanticJsonDiff(objectMapper);
    }

    public List<InvariantViolation> evaluate(
            List<JsonInvariant> invariants,
            String beforeJson,
            String afterJson
    ) {
        return evaluate(invariants, beforeJson, afterJson, Set.of());
    }

    public List<InvariantViolation> evaluate(
            List<JsonInvariant> invariants,
            String beforeJson,
            String afterJson,
            Set<String> volatileJsonPointers
    ) {
        Objects.requireNonNull(invariants, "invariants");
        Objects.requireNonNull(volatileJsonPointers, "volatileJsonPointers");
        try {
            JsonNode beforeRoot = objectMapper.readTree(beforeJson);
            JsonNode afterRoot = objectMapper.readTree(afterJson);
            List<InvariantViolation> violations = new ArrayList<>();

            for (JsonInvariant invariant : invariants) {
                JsonNode before = beforeRoot.at(invariant.jsonPointer());
                JsonNode after = afterRoot.at(invariant.jsonPointer());
                String failure = failureMessage(
                        invariant,
                        beforeRoot,
                        afterRoot,
                        before,
                        after,
                        beforeJson,
                        afterJson,
                        volatileJsonPointers
                );
                if (failure != null) {
                    violations.add(new InvariantViolation(
                            invariant.name(),
                            invariant.jsonPointer().isEmpty() ? "/" : invariant.jsonPointer(),
                            failure,
                            render(before),
                            render(after)
                    ));
                }
            }
            return List.copyOf(violations);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invariant evaluation requires valid JSON and JSON pointers", exception);
        }
    }

    private String failureMessage(
            JsonInvariant invariant,
            JsonNode beforeRoot,
            JsonNode afterRoot,
            JsonNode before,
            JsonNode after,
            String beforeJson,
            String afterJson,
            Set<String> volatileJsonPointers
    )
            throws JsonProcessingException {
        return switch (invariant.rule()) {
            case UNCHANGED -> unchanged(
                    invariant.jsonPointer(),
                    beforeJson,
                    afterJson,
                    volatileJsonPointers
            ) ? null : "Value changed outside configured volatile JSON paths";
            case ABSENT -> after.isMissingNode() ? null : "Value is present";
            case EQUALS -> {
                JsonNode expected = objectMapper.readTree(invariant.expectedJson());
                yield expected.equals(after) ? null : "Value does not equal the expected JSON";
            }
            case ARRAY_SIZE_UNCHANGED -> {
                boolean valid = before.isArray() && after.isArray() && before.size() == after.size();
                yield valid ? null : "Array size changed or the selected value is not an array";
            }
            case MUST_NOT_CONTAIN -> {
                JsonNode forbidden = objectMapper.readTree(invariant.expectedJson());
                boolean contains = after.isArray()
                        ? contains(after, forbidden)
                        : after.equals(forbidden);
                yield contains ? "Forbidden value is present" : null;
            }
            case EXPRESSION -> expressionEvaluator.evaluate(
                    invariant.expectedJson(),
                    beforeRoot,
                    afterRoot
            ) ? null : "Invariant expression evaluated to false";
        };
    }

    private boolean unchanged(
            String pointer,
            String beforeJson,
            String afterJson,
            Set<String> volatileJsonPointers
    ) {
        List<JsonDifference> differences = semanticJsonDiff.compare(
                beforeJson,
                afterJson,
                new JsonDiffOptions(volatileJsonPointers, false)
        );
        String displayedPointer = pointer.isEmpty() ? "/" : pointer;
        return differences.stream().noneMatch(difference ->
                displayedPointer.equals("/")
                        || difference.jsonPointer().equals(displayedPointer)
                        || difference.jsonPointer().startsWith(displayedPointer + "/")
        );
    }

    private boolean contains(JsonNode array, JsonNode expected) {
        for (JsonNode item : array) {
            if (item.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private String render(JsonNode node) {
        return node.isMissingNode() ? null : node.toString();
    }
}
