package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public class StudyBlock {
    private final String id;
    private final Task task;
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

    public void markCompleted() {
        progressStatus = ProgressStatus.COMPLETED;
    }

    public void markPartial() {
        progressStatus = ProgressStatus.PARTIAL;
    }

    public void markSkipped() {
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
