package dev.workflowguard.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class SemanticJsonDiff {
    private final ObjectMapper objectMapper;

    public SemanticJsonDiff(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<JsonDifference> compare(String beforeJson, String afterJson, JsonDiffOptions options) {
        Objects.requireNonNull(beforeJson, "beforeJson");
        Objects.requireNonNull(afterJson, "afterJson");
        Objects.requireNonNull(options, "options");
        try {
            JsonNode before = objectMapper.readTree(beforeJson);
            JsonNode after = objectMapper.readTree(afterJson);
            List<JsonDifference> differences = new ArrayList<>();
            compareNodes("", before, after, options, differences);
            return List.copyOf(differences);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Both values must contain valid JSON", exception);
        }
    }

    private void compareNodes(
            String pointer,
            JsonNode before,
            JsonNode after,
            JsonDiffOptions options,
            List<JsonDifference> differences
    ) {
        if (isIgnored(pointer, options.ignoredPointers())) {
            return;
        }
        if (before == null) {
            differences.add(new JsonDifference(displayPointer(pointer), DifferenceKind.ADDED, null, render(after)));
            return;
        }
        if (after == null) {
            differences.add(new JsonDifference(displayPointer(pointer), DifferenceKind.REMOVED, render(before), null));
            return;
        }
        if (before.equals(after)) {
            return;
        }
        if (before.isObject() && after.isObject()) {
            Set<String> fields = new TreeSet<>();
            before.fieldNames().forEachRemaining(fields::add);
            after.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                compareNodes(
                        pointer + "/" + escape(field),
                        before.get(field),
                        after.get(field),
                        options,
                        differences
                );
            }
            return;
        }
        if (before.isArray() && after.isArray()) {
            compareArrays(pointer, before, after, options, differences);
            return;
        }
        differences.add(new JsonDifference(
                displayPointer(pointer),
                DifferenceKind.CHANGED,
                render(before),
                render(after)
        ));
    }

    private void compareArrays(
            String pointer,
            JsonNode before,
            JsonNode after,
            JsonDiffOptions options,
            List<JsonDifference> differences
    ) {
        if (options.ignoreArrayOrder()) {
            List<String> beforeValues = canonicalArray(before);
            List<String> afterValues = canonicalArray(after);
            if (!beforeValues.equals(afterValues)) {
                differences.add(new JsonDifference(
                        displayPointer(pointer),
                        DifferenceKind.CHANGED,
                        render(before),
                        render(after)
                ));
            }
            return;
        }

        int maximumSize = Math.max(before.size(), after.size());
        for (int index = 0; index < maximumSize; index++) {
            compareNodes(
                    pointer + "/" + index,
                    index < before.size() ? before.get(index) : null,
                    index < after.size() ? after.get(index) : null,
                    options,
                    differences
            );
        }
    }

    private List<String> canonicalArray(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(render(node)));
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private boolean isIgnored(String pointer, Set<String> ignoredPointers) {
        for (String ignored : ignoredPointers) {
            if (pointer.equals(ignored) || (!ignored.isEmpty() && pointer.startsWith(ignored + "/"))) {
                return true;
            }
        }
        return false;
    }

    private String displayPointer(String pointer) {
        return pointer.isEmpty() ? "/" : pointer;
    }

    private String escape(String field) {
        return field.replace("~", "~0").replace("/", "~1");
    }

    private String render(JsonNode node) {
        return node == null ? null : node.toString();
    }
}
