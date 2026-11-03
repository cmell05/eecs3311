package com.planpilot.service;

import com.planpilot.ai.PlanningAgent;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.TaskStatus;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

public final class EffortEstimator {
    private final PlanningAgent planningAgent;

    public EffortEstimator(PlanningAgent planningAgent) {
        this.planningAgent = Objects.requireNonNull(
                planningAgent, "Planning agent cannot be null.");
    }

    public double estimateEffort(Task task, List<ProgressRecord> history) {
        Objects.requireNonNull(task, "Task cannot be null.");
        Objects.requireNonNull(history, "Progress history cannot be null.");

        HistoricalEffort historicalEffort = calculateHistoricalEffort(
                task, history);
        return planningAgent.suggestEffort(
                task, historicalEffort.average(), historicalEffort.sampleCount());
    }

    public OptionalDouble calculateHistoricalAverage(
            Task task, List<ProgressRecord> history) {
        Objects.requireNonNull(task, "Task cannot be null.");
        Objects.requireNonNull(history, "Progress history cannot be null.");
        return calculateHistoricalEffort(task, history).average();
    }

    private HistoricalEffort calculateHistoricalEffort(
            Task target, List<ProgressRecord> history) {
        Map<Task, Map<StudyBlock, ProgressRecord>> latestByTask =
                new IdentityHashMap<>();
        for (ProgressRecord record : history) {
            Objects.requireNonNull(record,
                    "Progress history cannot contain null records.");
            Task historicalTask = record.getTask();
            if (historicalTask != target
                    && historicalTask.getStatus() == TaskStatus.COMPLETED
                    && isSimilar(target, historicalTask)) {
                latestByTask.computeIfAbsent(
                                historicalTask, ignored -> new IdentityHashMap<>())
                        .put(record.getStudyBlock(), record);
            }
        }

        double totalHours = 0;
        int sampleCount = 0;
        for (Map<StudyBlock, ProgressRecord> taskRecords
                : latestByTask.values()) {
            double actualHours = taskRecords.values().stream()
                    .mapToDouble(ProgressRecord::getCompletedHours)
                    .sum();
            if (actualHours > 0) {
                totalHours += actualHours;
                sampleCount++;
            }
        }
        OptionalDouble average = sampleCount == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(totalHours / sampleCount);
        return new HistoricalEffort(average, sampleCount);
    }

    private boolean isSimilar(Task target, Task historicalTask) {
        if (target instanceof AcademicTask targetAcademic
                && historicalTask instanceof AcademicTask historicalAcademic) {
            return targetAcademic.getTaskType() == historicalAcademic.getTaskType()
                    && difficultyIsSimilar(target, historicalTask);
        }
        return !(target instanceof AcademicTask)
                && !(historicalTask instanceof AcademicTask)
                && difficultyIsSimilar(target, historicalTask);
    }

    private boolean difficultyIsSimilar(Task first, Task second) {
        return Math.abs(first.getDifficulty() - second.getDifficulty()) <= 1;
    }

    private record HistoricalEffort(
            OptionalDouble average, int sampleCount) {
    }
}
