package com.planpilot.ai;

import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.PriorityLevel;

import java.time.LocalDateTime;
import java.util.Objects;

public record ParsedTaskData(
        String title,
        LocalDateTime deadline,
        double estimatedHours,
        int difficulty,
        PriorityLevel importance,
        ParsedTaskKind taskKind,
        String courseCode,
        Double assessmentWeight,
        AcademicTaskType academicTaskType) {

    public ParsedTaskData {
        title = requireText(title, "Task title");
        Objects.requireNonNull(deadline, "Task deadline cannot be null.");
        if (!Double.isFinite(estimatedHours) || estimatedHours <= 0) {
            throw new IllegalArgumentException(
                    "Estimated hours must be a positive finite value.");
        }
        if (difficulty < 1 || difficulty > 5) {
            throw new IllegalArgumentException(
                    "Difficulty must be between 1 and 5.");
        }
        Objects.requireNonNull(importance, "Importance cannot be null.");
        Objects.requireNonNull(taskKind, "Task kind cannot be null.");
        courseCode = normalizeOptionalText(courseCode);
        if (assessmentWeight != null
                && (!Double.isFinite(assessmentWeight)
                || assessmentWeight < 0
                || assessmentWeight > 100)) {
            throw new IllegalArgumentException(
                    "Assessment weight must be between 0 and 100.");
        }
        if (taskKind == ParsedTaskKind.GENERAL
                && (courseCode != null
                || assessmentWeight != null
                || academicTaskType != null)) {
            throw new IllegalArgumentException(
                    "General task proposals cannot contain academic fields.");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
