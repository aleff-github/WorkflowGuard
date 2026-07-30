package dev.workflowguard.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.VariableDefinition;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariableResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]*)}");
    private static final int MAXIMUM_VARIABLE_VALUE_LENGTH = 65_536;

    private final ObjectMapper objectMapper;

    public VariableResolver(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Optional<String> extract(VariableDefinition definition, String responseBody) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(responseBody, "responseBody");

        return switch (definition.extractionType()) {
            case REGEX -> extractRegex(definition, responseBody);
            case JSON_POINTER -> extractJsonPointer(definition, responseBody);
        };
    }

    public String substitute(String requestTemplate, Map<String, String> variables) {
        Objects.requireNonNull(requestTemplate, "requestTemplate");
        Objects.requireNonNull(variables, "variables");

        Matcher matcher = PLACEHOLDER.matcher(requestTemplate);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalArgumentException("No value available for variable: " + name);
            }
            validateVariableValue(name, value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Optional<String> extractRegex(VariableDefinition definition, String responseBody) {
        com.google.re2j.Matcher matcher = com.google.re2j.Pattern.compile(
                definition.expression(),
                com.google.re2j.Pattern.DOTALL
        ).matcher(responseBody);
        if (!matcher.find() || definition.captureGroup() > matcher.groupCount()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(definition.captureGroup()))
                .map(value -> validateVariableValue(definition.name(), value));
    }

    private Optional<String> extractJsonPointer(VariableDefinition definition, String responseBody) {
        String value;
        try {
            JsonNode node = objectMapper.readTree(responseBody).at(definition.expression());
            if (node.isMissingNode() || node.isNull()) {
                return Optional.empty();
            }
            value = node.isValueNode() ? node.asText() : node.toString();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
        return Optional.of(validateVariableValue(definition.name(), value));
    }

    private String validateVariableValue(String name, String value) {
        if (value.length() > MAXIMUM_VARIABLE_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "Variable '" + name + "' exceeds the 64 KiB value limit"
            );
        }
        if (value.chars().anyMatch(character ->
                Character.isISOControl(character) || character == 0x7f
        )) {
            throw new IllegalArgumentException(
                    "Variable '" + name + "' contains an unsafe control character"
            );
        }
        return value;
    }
}
