package com.yoda.codingagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ToolArguments {

    private final ObjectNode values;
    private final Set<String> allowed;

    private ToolArguments(ObjectNode values, Set<String> allowed, boolean validateNames) {
        this.values = values.deepCopy();
        this.allowed = Set.copyOf(allowed);
        if (validateNames) {
            Iterator<String> names = values.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!this.allowed.contains(name)) {
                    throw invalid("unknown argument: " + name);
                }
            }
        }
    }

    public static ToolArguments raw(ObjectNode values) {
        return new ToolArguments(Objects.requireNonNull(values, "values"), Set.of(), false);
    }

    public ToolArguments allowOnly(String... allowedFields) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(allowedFields, "allowedFields");
        Set<String> allowed = new HashSet<>();
        for (String field : allowedFields) {
            if (field == null || field.isBlank() || !allowed.add(field)) {
                throw new IllegalArgumentException("allowed fields must be unique and non-blank");
            }
        }
        return new ToolArguments(values, allowed, true);
    }

    public String requireString(String name, int minimumLength, int maximumLength) {
        JsonNode node = required(name);
        if (!node.isTextual()) {
            throw invalid(name + " must be a string");
        }
        return validateString(name, node.textValue(), minimumLength, maximumLength);
    }

    public String optionalString(String name, String defaultValue,
                                 int minimumLength, int maximumLength) {
        JsonNode node = values.get(name);
        if (node == null) {
            return validateString(name, defaultValue, minimumLength, maximumLength);
        }
        if (!node.isTextual()) {
            throw invalid(name + " must be a string");
        }
        return validateString(name, node.textValue(), minimumLength, maximumLength);
    }

    public int requireInteger(String name, int minimum, int maximum) {
        return validateInteger(name, required(name), minimum, maximum);
    }

    public int optionalInteger(String name, int defaultValue, int minimum, int maximum) {
        JsonNode node = values.get(name);
        return node == null ? validateRange(name, defaultValue, minimum, maximum)
                : validateInteger(name, node, minimum, maximum);
    }

    public boolean optionalBoolean(String name, boolean defaultValue) {
        JsonNode node = values.get(name);
        if (node == null) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw invalid(name + " must be a boolean");
        }
        return node.booleanValue();
    }

    public List<String> requireStringArray(String name, int minimumItems, int maximumItems,
                                           int minimumItemLength, int maximumItemLength,
                                           int maximumTotalCharacters) {
        JsonNode node = required(name);
        if (!node.isArray()) {
            throw invalid(name + " must be an array");
        }
        if (node.size() < minimumItems || node.size() > maximumItems) {
            throw invalid(name + " must contain between " + minimumItems
                    + " and " + maximumItems + " items");
        }
        List<String> result = new ArrayList<>(node.size());
        long total = 0;
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            if (!item.isTextual()) {
                throw invalid(name + "[" + index + "] must be a string");
            }
            String value = validateString(name + "[" + index + "]", item.textValue(),
                    minimumItemLength, maximumItemLength);
            if (value.isBlank()) {
                throw invalid(name + "[" + index + "] must not be blank");
            }
            total += value.length();
            if (total > maximumTotalCharacters) {
                throw invalid(name + " exceeds its total character limit");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private JsonNode required(String name) {
        checkAllowed(name);
        JsonNode node = values.get(name);
        if (node == null || node.isNull()) {
            throw invalid("missing required argument: " + name);
        }
        return node;
    }

    private void checkAllowed(String name) {
        if (!allowed.contains(name)) {
            throw new IllegalArgumentException("field is not declared by this tool: " + name);
        }
    }

    private static int validateInteger(String name, JsonNode node, int minimum, int maximum) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(name + " must be an integer");
        }
        return validateRange(name, node.intValue(), minimum, maximum);
    }

    private static int validateRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw invalid(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String validateString(String name, String value,
                                         int minimumLength, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.length() < minimumLength || value.length() > maximumLength) {
            throw invalid(name + " length must be between " + minimumLength
                    + " and " + maximumLength);
        }
        if (value.indexOf('\0') >= 0) {
            throw invalid(name + " must not contain NUL");
        }
        return value;
    }

    private static ToolArgumentException invalid(String message) {
        return new ToolArgumentException(message);
    }
}
