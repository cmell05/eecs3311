package com.planpilot.service;

import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.event.PlanningEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ProgressService {
    private static final double EPSILON = 0.000001;

    private final List<ProgressRecord> history = new ArrayList<>();
    private final Map<Task, Double> baselineCompletedHours = new IdentityHashMap<>();
    private final PlanningEventPublisher eventPublisher;

    public ProgressService() {
        this(new PlanningEventPublisher());
    }

    public ProgressService(PlanningEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "Event publisher cannot be null.");
    }

    public void recordProgress(
            StudyBlock block, ProgressStatus status, double hours) {
        Objects.requireNonNull(block, "Study block cannot be null.");
        Objects.requireNonNull(status, "Progress status cannot be null.");
        validateProgress(block, status, hours);

        Task task = block.getTask();
        baselineCompletedHours.putIfAbsent(task, task.getCompletedHours());
        double projectedHours = task.getCompletedHours()
                - latestContribution(block)
                + hours;
        if (projectedHours > task.getEstimatedHours() + EPSILON) {
            throw new IllegalArgumentException(
                    "Progress cannot exceed the task's estimated hours.");
        }

        updateBlockStatus(block, status);
        history.add(new ProgressRecord(
                UUID.randomUUID().toString(), task, block,
                LocalDateTime.now(), hours, status));
        updateRemainingEffort(task);
    }

    public void inferProgress(StudyBlock block) {
        Objects.requireNonNull(block, "Study block cannot be null.");
        recordProgress(block, ProgressStatus.COMPLETED, block.durationHours());
    }

    public void updateRemainingEffort(Task task) {
        Objects.requireNonNull(task, "Task cannot be null.");
        double completedHours = baselineCompletedHours.getOrDefault(
                task, task.getCompletedHours());
        Map<StudyBlock, ProgressRecord> latestRecords = new IdentityHashMap<>();

        for (ProgressRecord record : history) {
            if (record.getTask() == task) {
                latestRecords.put(record.getStudyBlock(), record);
            }
        }
        for (ProgressRecord record : latestRecords.values()) {
            completedHours += record.getCompletedHours();
        }

        task.setCompletedHours(completedHours);
        eventPublisher.notifyObservers();
    }

    public List<ProgressRecord> getHistoryForTask(Task task) {
        Objects.requireNonNull(task, "Task cannot be null.");
        return history.stream()
                .filter(record -> record.getTask() == task)
                .toList();
    }

    private void validateProgress(
            StudyBlock block, ProgressStatus status, double hours) {
        if (!Double.isFinite(hours) || hours < 0) {
            throw new IllegalArgumentException(
                    "Progress hours must be a non-negative finite value.");
        }

        double duration = block.durationHours();
        switch (status) {
            case COMPLETED -> {
                if (Math.abs(hours - duration) > EPSILON) {
                    throw new IllegalArgumentException(
                            "Completed progress must equal the study block duration.");
                }
            }
            case PARTIAL -> {
                if (hours <= 0 || hours >= duration) {
                    throw new IllegalArgumentException(
                            "Partial progress must be greater than zero and less than the block duration.");
                }
            }
            case SKIPPED -> {
                if (hours != 0) {
                    throw new IllegalArgumentException(
                            "Skipped progress must record zero completed hours.");
                }
            }
            case PLANNED -> throw new IllegalArgumentException(
                    "Planned blocks do not represent recorded progress.");
        }
    }

    private double latestContribution(StudyBlock block) {
        for (int index = history.size() - 1; index >= 0; index--) {
            ProgressRecord record = history.get(index);
            if (record.getStudyBlock() == block) {
                return record.getCompletedHours();
            }
        }
        return 0;
    }

    private void updateBlockStatus(StudyBlock block, ProgressStatus status) {
        switch (status) {
            case COMPLETED -> block.markCompleted();
            case PARTIAL -> block.markPartial();
            case SKIPPED -> block.markSkipped();
            case PLANNED -> throw new IllegalArgumentException(
                    "Planned blocks do not represent recorded progress.");
        }
    }
}
