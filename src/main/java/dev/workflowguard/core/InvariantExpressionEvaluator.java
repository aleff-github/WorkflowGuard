package dev.workflowguard.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Evaluates a deliberately small invariant language without loading a script engine.
 *
 * <p>Supported values are JSON literals and {@code before:/pointer} or
 * {@code after:/pointer} references. Expressions support {@code &&}, {@code ||},
 * {@code !}, comparisons, parentheses, {@code exists(reference)},
 * {@code changed(/pointer)}, and {@code unchanged(/pointer)}.</p>
 */
public final class InvariantExpressionEvaluator {
    private final ObjectMapper objectMapper;

    public InvariantExpressionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public boolean evaluate(String expression, JsonNode before, JsonNode after) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Parser parser = new Parser(expression, before, after);
        boolean result = parser.parseOr();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected input");
        }
        return result;
    }

    private final class Parser {
        private final String source;
        private final JsonNode before;
        private final JsonNode after;
        private int offset;

        private Parser(String source, JsonNode before, JsonNode after) {
            this.source = source;
            this.before = before;
            this.after = after;
        }

        private boolean parseOr() {
            boolean value = parseAnd();
            while (match("||")) {
                boolean right = parseAnd();
                value = value || right;
            }
            return value;
        }

        private boolean parseAnd() {
            boolean value = parseUnary();
            while (match("&&")) {
                boolean right = parseUnary();
                value = value && right;
            }
            return value;
        }

        private boolean parseUnary() {
            if (match("!")) {
                return !parseUnary();
            }
            if (match("(")) {
                boolean value = parseOr();
                require(")");
                return value;
            }
            if (matchWord("exists")) {
                require("(");
                JsonNode value = parseValue();
                require(")");
                return !value.isMissingNode();
            }
            if (matchWord("changed")) {
                String pointer = parsePointerArgument();
                return !before.at(pointer).equals(after.at(pointer));
            }
            if (matchWord("unchanged")) {
                String pointer = parsePointerArgument();
                return before.at(pointer).equals(after.at(pointer));
            }

            JsonNode left = parseValue();
            String operator = comparisonOperator();
            if (operator == null) {
                if (!left.isBoolean()) {
                    throw error("A value must be followed by a comparison operator");
                }
                return left.booleanValue();
            }
            JsonNode right = parseValue();
            return compare(left, operator, right);
        }

        private String parsePointerArgument() {
            require("(");
            skipWhitespace();
            int start = offset;
            while (!atEnd() && source.charAt(offset) != ')') {
                offset++;
            }
            if (atEnd()) {
                throw error("Missing ')'");
            }
            String pointer = source.substring(start, offset).trim();
            require(")");
            validatePointer(pointer);
            return pointer;
        }

        private JsonNode parseValue() {
            skipWhitespace();
            if (source.startsWith("before:", offset)) {
                offset += "before:".length();
                return before.at(readReferencePointer());
            }
            if (source.startsWith("after:", offset)) {
                offset += "after:".length();
                return after.at(readReferencePointer());
            }
            String literal = readJsonLiteral();
            try {
                JsonNode value = objectMapper.readTree(literal);
                if (value == null) {
                    throw error("Missing JSON value");
                }
                return value;
            } catch (JsonProcessingException exception) {
                throw error("Invalid JSON literal '" + literal + "'");
            }
        }

        private String readReferencePointer() {
            skipWhitespace();
            int start = offset;
            while (!atEnd() && !isValueTerminator(source.charAt(offset))) {
                offset++;
            }
            String pointer = source.substring(start, offset);
            validatePointer(pointer);
            return pointer;
        }

        private String readJsonLiteral() {
            skipWhitespace();
            if (atEnd()) {
                throw error("Expected a value");
            }
            int start = offset;
            if (source.charAt(offset) == '"') {
                offset++;
                boolean escaped = false;
                while (!atEnd()) {
                    char current = source.charAt(offset++);
                    if (current == '"' && !escaped) {
                        return source.substring(start, offset);
                    }
                    escaped = current == '\\' && !escaped;
                    if (current != '\\') {
                        escaped = false;
                    }
                }
                throw error("Unterminated string literal");
            }
            while (!atEnd() && !isValueTerminator(source.charAt(offset))) {
                offset++;
            }
            return source.substring(start, offset);
        }

        private String comparisonOperator() {
            for (String candidate : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                if (match(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private boolean compare(JsonNode left, String operator, JsonNode right) {
            return switch (operator) {
                case "==" -> left.equals(right);
                case "!=" -> !left.equals(right);
                case ">", ">=", "<", "<=" -> orderedComparison(left, operator, right);
                default -> throw error("Unsupported operator " + operator);
            };
        }

        private boolean orderedComparison(JsonNode left, String operator, JsonNode right) {
            int comparison;
            if (left.isNumber() && right.isNumber()) {
                BigDecimal leftNumber = left.decimalValue();
                BigDecimal rightNumber = right.decimalValue();
                comparison = leftNumber.compareTo(rightNumber);
            } else if (left.isTextual() && right.isTextual()) {
                comparison = left.textValue().compareTo(right.textValue());
            } else {
                throw error("Ordered comparisons require two numbers or two strings");
            }
            return switch (operator) {
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                default -> BooleanNode.FALSE.booleanValue();
            };
        }

        private void validatePointer(String pointer) {
            if (!pointer.isEmpty() && !pointer.startsWith("/")) {
                throw error("JSON pointer must be empty or start with '/'");
            }
            try {
                before.at(pointer);
            } catch (IllegalArgumentException exception) {
                throw error("Invalid JSON pointer '" + pointer + "'");
            }
        }

        private boolean matchWord(String word) {
            skipWhitespace();
            if (!source.startsWith(word, offset)) {
                return false;
            }
            int end = offset + word.length();
            if (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                return false;
            }
            offset = end;
            return true;
        }

        private boolean match(String token) {
            skipWhitespace();
            if (!source.startsWith(token, offset)) {
                return false;
            }
            offset += token.length();
            return true;
        }

        private void require(String token) {
            if (!match(token)) {
                throw error("Expected '" + token + "'");
            }
        }

        private boolean isValueTerminator(char value) {
            return Character.isWhitespace(value)
                    || value == '&'
                    || value == '|'
                    || value == '='
                    || value == '!'
                    || value == '>'
                    || value == '<'
                    || value == ')'
                    || value == ',';
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(source.charAt(offset))) {
                offset++;
            }
        }

        private boolean atEnd() {
            return offset >= source.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    message + " at character " + (offset + 1) + " in invariant expression"
            );
        }
    }
}
