package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public abstract class Task {
    private final String id;
    private String title;
    private LocalDateTime deadline;
    private double estimatedHours;
    private double completedHours;
    private int difficulty;
    private PriorityLevel userImportance;
    private TaskStatus status;

    protected Task(String id, String title, LocalDateTime deadline,
                   double estimatedHours, int difficulty,
                   PriorityLevel userImportance) {
        this.id = requireText(id, "Task id");
        setTitle(title);
        setDeadline(deadline);
        setEstimatedHours(estimatedHours);
        setDifficulty(difficulty);
        setUserImportance(userImportance);
        this.status = TaskStatus.NOT_STARTED;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public LocalDateTime getDeadline() { return deadline; }
    public double getEstimatedHours() { return estimatedHours; }
    public double getCompletedHours() { return completedHours; }
    public int getDifficulty() { return difficulty; }
    public PriorityLevel getUserImportance() { return userImportance; }
    public TaskStatus getStatus() { return status; }

    public double getRemainingHours() {
        return Math.max(0.0, estimatedHours - completedHours);
    }

    public void recordProgress(double hours) {
        if (hours < 0) {
            throw new IllegalArgumentException("Progress hours cannot be negative.");
        }
        if (hours == 0 || status == TaskStatus.COMPLETED) {
            return;
        }
        completedHours = Math.min(estimatedHours, completedHours + hours);
        status = completedHours >= estimatedHours
                ? TaskStatus.COMPLETED
                : TaskStatus.IN_PROGRESS;
    }

    public void markCompleted() {
        completedHours = estimatedHours;
        status = TaskStatus.COMPLETED;
    }

    public void markSkipped() {
        if (status != TaskStatus.COMPLETED) {
            status = TaskStatus.SKIPPED;
        }
    }

    public void setTitle(String title) {
        this.title = requireText(title, "Task title");
    }

    public void setDeadline(LocalDateTime deadline) {
        this.deadline = Objects.requireNonNull(deadline, "Deadline cannot be null.");
    }

    public void setEstimatedHours(double estimatedHours) {
        if (estimatedHours <= 0) {
            throw new IllegalArgumentException("Estimated hours must be positive.");
        }
        if (estimatedHours < completedHours) {
            throw new IllegalArgumentException("Estimated hours cannot be less than completed hours.");
        }
        this.estimatedHours = estimatedHours;
        if (completedHours == estimatedHours) {
            status = TaskStatus.COMPLETED;
        } else if (status == TaskStatus.COMPLETED) {
            status = TaskStatus.IN_PROGRESS;
        }
    }

    public void setDifficulty(int difficulty) {
        if (difficulty < 1 || difficulty > 5) {
            throw new IllegalArgumentException("Difficulty must be between 1 and 5.");
        }
        this.difficulty = difficulty;
    }

    public void setUserImportance(PriorityLevel userImportance) {
        this.userImportance = Objects.requireNonNull(
                userImportance, "User importance cannot be null.");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }
}
