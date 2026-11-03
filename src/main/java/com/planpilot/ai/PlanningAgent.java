package com.planpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.Task;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

public final class PlanningAgent {
    private static final double MAX_SUGGESTED_HOURS = 1000.0;

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;
    private final TaskParser taskParser;
    private final Clock clock;

    public PlanningAgent(LLMClient llmClient) {
        this(llmClient, new ObjectMapper(), Clock.systemDefaultZone());
    }

    PlanningAgent(LLMClient llmClient, ObjectMapper objectMapper) {
        this(llmClient, objectMapper, Clock.systemDefaultZone());
    }

    PlanningAgent(
            LLMClient llmClient, ObjectMapper objectMapper, Clock clock) {
        this.llmClient = Objects.requireNonNull(
                llmClient, "LLM client cannot be null.");
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "Object mapper cannot be null.");
        this.taskParser = new TaskParser(objectMapper);
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null.");
    }

    public double suggestEffort(
            Task task, OptionalDouble historicalAverage, int sampleCount) {
        Objects.requireNonNull(task, "Task cannot be null.");
        Objects.requireNonNull(
                historicalAverage, "Historical average cannot be null.");
        if (sampleCount < 0
                || (historicalAverage.isPresent() && sampleCount == 0)) {
            throw new IllegalArgumentException(
                    "Historical sample count is inconsistent.");
        }

        String response = llmClient.generateText(
                systemInstruction(), createEffortPrompt(
                        task, historicalAverage, sampleCount));
        return parseSuggestedHours(response);
    }

    public ParsedTaskData parseTask(String text) {
        requireText(text, "Task description");
        String response = llmClient.generateText(
                taskParsingSystemInstruction(), createTaskParsingPrompt(text));
        return taskParser.parse(response);
    }

    private String taskParsingSystemInstruction() {
        return "Convert a task description into a proposed task. Use the "
                + "provided reference time to resolve relative dates. Return "
                + "exactly one JSON object with required fields title, deadline "
                + "(ISO local date-time), and estimatedHours. Optional fields: "
                + "difficulty (1-5), importance (LOW, MEDIUM, HIGH), taskKind "
                + "(ACADEMIC or GENERAL), courseCode, assessmentWeight (0-100), "
                + "and academicTaskType (ASSIGNMENT, EXAM, QUIZ, PROJECT, "
                + "READING, STUDY). Use null for unknown optional academic "
                + "fields. General tasks must use null for all academic fields. "
                + "Do not include Markdown or commentary.";
    }

    private String createTaskParsingPrompt(String text) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("referenceDateTime", LocalDateTime.now(clock).toString());
        prompt.put("taskDescription", text.trim());
        try {
            return objectMapper.writeValueAsString(prompt);
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "Unable to create the task parsing request.", exception);
        }
    }

    private String systemInstruction() {
        return "You estimate task effort for a student planner. "
                + "Use only the supplied task facts and historical summary. "
                + "Return exactly one JSON object with a positive numeric "
                + "estimatedHours field. Do not include Markdown or commentary.";
    }

    private String createEffortPrompt(
            Task task, OptionalDouble historicalAverage, int sampleCount) {
        StringBuilder prompt = new StringBuilder()
                .append("Task title: ").append(task.getTitle()).append('\n')
                .append("Task kind: ")
                .append(task instanceof AcademicTask ? "academic" : "general")
                .append('\n')
                .append("Current user estimate: ")
                .append(format(task.getEstimatedHours())).append(" hours\n")
                .append("Difficulty: ").append(task.getDifficulty())
                .append(" out of 5\n")
                .append("Importance: ").append(task.getUserImportance())
                .append('\n');

        if (task instanceof AcademicTask academicTask) {
            prompt.append("Academic task type: ")
                    .append(academicTask.getTaskType()).append('\n')
                    .append("Assessment weight: ")
                    .append(format(academicTask.getAssessmentWeight()))
                    .append("%\n");
        }
        if (historicalAverage.isPresent()) {
            prompt.append("Average actual effort from ")
                    .append(sampleCount)
                    .append(" similar completed task(s): ")
                    .append(format(historicalAverage.getAsDouble()))
                    .append(" hours\n");
        } else {
            prompt.append("Similar completed-task history: unavailable\n");
        }
        prompt.append("Recommend a realistic total effort estimate.");
        return prompt.toString();
    }

    private double parseSuggestedHours(String response) {
        Objects.requireNonNull(response, "LLM response cannot be null.");
        if (response.isBlank()) {
            throw invalidResponse();
        }
        try {
            JsonNode root = objectMapper.readTree(response.trim());
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode value = root.path("estimatedHours");
            if (!value.isNumber()) {
                throw invalidResponse();
            }
            double hours = value.asDouble();
            if (!Double.isFinite(hours)
                    || hours <= 0
                    || hours > MAX_SUGGESTED_HOURS) {
                throw invalidResponse();
            }
            return hours;
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "The LLM returned an invalid effort suggestion.", exception);
        }
    }

    private LLMClientException invalidResponse() {
        return new LLMClientException(
                "The LLM returned an invalid effort suggestion.");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }
}
