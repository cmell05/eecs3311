package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public class ProgressRecord {
    private final String id;
    private final Task task;
    private final StudyBlock studyBlock;
    private final LocalDateTime recordedAt;
    private final double completedHours;
    private final ProgressStatus status;

    public ProgressRecord(String id, Task task, StudyBlock studyBlock,
                          LocalDateTime recordedAt, double completedHours,
                          ProgressStatus status) {
        this.id = requireText(id);
        this.task = Objects.requireNonNull(task, "Task cannot be null.");
        this.studyBlock = Objects.requireNonNull(studyBlock, "Study block cannot be null.");
        if (studyBlock.getTask() != task) {
            throw new IllegalArgumentException("Study block must belong to the recorded task.");
        }
        this.recordedAt = Objects.requireNonNull(recordedAt, "Recorded time cannot be null.");
        if (completedHours < 0 || completedHours > studyBlock.durationHours()) {
            throw new IllegalArgumentException(
                    "Completed hours must be between zero and the study block duration.");
        }
        this.completedHours = completedHours;
        this.status = Objects.requireNonNull(status, "Progress status cannot be null.");
    }

    public String getId() { return id; }
    public Task getTask() { return task; }
    public StudyBlock getStudyBlock() { return studyBlock; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public double getCompletedHours() { return completedHours; }
    public ProgressStatus getStatus() { return status; }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "Progress record id cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Progress record id cannot be blank.");
        }
        return value.trim();
    }
}
