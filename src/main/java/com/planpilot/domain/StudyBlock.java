package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public class StudyBlock {
    private final String id;
    private Task task;
    private boolean inferred;
    private final LocalDateTime start;
    private final LocalDateTime end;
    private ProgressStatus progressStatus;

    public StudyBlock(String id, Task task, LocalDateTime start, LocalDateTime end) {
        this.id = requireText(id);
        this.task = Objects.requireNonNull(task, "Task cannot be null.");
        this.start = Objects.requireNonNull(start, "Start cannot be null.");
        this.end = Objects.requireNonNull(end, "End cannot be null.");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("End must be after start.");
        }
        this.progressStatus = ProgressStatus.PLANNED;
    }

    public String getId() { return id; }
    public Task getTask() { return task; }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd() { return end; }
    public ProgressStatus getProgressStatus() { return progressStatus; }

    public double durationHours() {
        return java.time.Duration.between(start, end).toMinutes() / 60.0;
    }

    public boolean isInferred() { return inferred; }

    public void rebindTask(Task task) {
        if (!this.task.getId().equals(task.getId())) throw new IllegalArgumentException("Task identity cannot change.");
        this.task = task;
    }

    public void markInferred() { inferred = true; }

    public void markCompleted() {
        inferred = false;
        progressStatus = ProgressStatus.COMPLETED;
    }

    public void markPartial() {
        inferred = false;
        progressStatus = ProgressStatus.PARTIAL;
    }

    public void markSkipped() {
        inferred = false;
        progressStatus = ProgressStatus.SKIPPED;
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "Study block id cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Study block id cannot be blank.");
        }
        return value.trim();
    }
}
