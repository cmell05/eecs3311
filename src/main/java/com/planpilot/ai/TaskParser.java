package com.planpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.PriorityLevel;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

public final class TaskParser {
    private static final int DEFAULT_DIFFICULTY = 3;
    private static final double MAX_ESTIMATED_HOURS = 1000.0;

    private final ObjectMapper objectMapper;

    public TaskParser() {
        this(new ObjectMapper());
    }

    TaskParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "Object mapper cannot be null.");
    }

    public ParsedTaskData parse(String response) {
        Objects.requireNonNull(response, "LLM response cannot be null.");
        if (response.isBlank()) {
            throw invalidResponse();
        }
        try {
            JsonNode root = objectMapper.readTree(response.trim());
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            String title = requiredText(root, "title");
            LocalDateTime deadline = parseDeadline(
                    requiredText(root, "deadline"));
            double estimatedHours = requiredNumber(root, "estimatedHours");
            if (!Double.isFinite(estimatedHours)
                    || estimatedHours <= 0
                    || estimatedHours > MAX_ESTIMATED_HOURS) {
                throw invalidResponse();
            }

            int difficulty = optionalInteger(
                    root, "difficulty", DEFAULT_DIFFICULTY);
            PriorityLevel importance = optionalEnum(
                    root, "importance", PriorityLevel.class,
                    PriorityLevel.MEDIUM);
            ParsedTaskKind taskKind = optionalEnum(
                    root, "taskKind", ParsedTaskKind.class,
                    ParsedTaskKind.GENERAL);
            String courseCode = optionalText(root, "courseCode");
            Double assessmentWeight = optionalNumber(
                    root, "assessmentWeight");
            AcademicTaskType academicTaskType = optionalEnum(
                    root, "academicTaskType", AcademicTaskType.class, null);

            return new ParsedTaskData(
                    title, deadline, estimatedHours, difficulty, importance,
                    taskKind, courseCode, assessmentWeight, academicTaskType);
        } catch (JsonProcessingException | DateTimeParseException exception) {
            throw new LLMClientException(
                    "The LLM returned invalid task data.", exception);
        } catch (IllegalArgumentException exception) {
            throw new LLMClientException(
                    "The LLM returned invalid task data.", exception);
        }
    }

    private String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root.path(fieldName);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalidResponse();
        }
        return value.asText().trim();
    }

    private double requiredNumber(JsonNode root, String fieldName) {
        JsonNode value = root.path(fieldName);
        if (!value.isNumber()) {
            throw invalidResponse();
        }
        return value.asDouble();
    }

    private int optionalInteger(
            JsonNode root, String fieldName, int defaultValue) {
        JsonNode value = root.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber()) {
            throw invalidResponse();
        }
        return value.asInt();
    }

    private Double optionalNumber(JsonNode root, String fieldName) {
        JsonNode value = root.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalidResponse();
        }
        return value.asDouble();
    }

    private String optionalText(JsonNode root, String fieldName) {
        JsonNode value = root.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalidResponse();
        }
        return value.asText();
    }

    private <E extends Enum<E>> E optionalEnum(
            JsonNode root,
            String fieldName,
            Class<E> enumType,
            E defaultValue) {
        String text = optionalText(root, fieldName);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
    }

    private LocalDateTime parseDeadline(String value) {
        return LocalDateTime.parse(value);
    }

    private LLMClientException invalidResponse() {
        return new LLMClientException(
                "The LLM returned invalid task data.");
    }
}
