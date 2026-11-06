package com.planpilot.service;

import com.planpilot.ai.LLMClient;
import com.planpilot.ai.PlanningAgent;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffortEstimatorTest {
    private final LocalDateTime start = LocalDateTime.of(
            2026, 11, 3, 9, 0);

    @Test
    void usesSimilarHistoryWithoutOverwritingTaskEstimate() {
        AtomicReference<String> prompt = new AtomicReference<>();
        LLMClient fakeClient = (systemInstruction, userPrompt) -> {
            prompt.set(userPrompt);
            return "{\"estimatedHours\":3.75}";
        };
        EffortEstimator estimator = new EffortEstimator(
                new PlanningAgent(fakeClient));
        Task target = task("target", 5, 3);
        Task historical = task("historical", 3, 3);
        historical.markCompleted();
        List<ProgressRecord> history = List.of(
                record("R1", historical, "B1", start, 2),
                record("R2", historical, "B2", start.plusHours(2), 1));

        double suggestion = estimator.estimateEffort(target, history);

        assertEquals(3.75, suggestion, 0.0001);
        assertEquals(5, target.getEstimatedHours(), 0.0001);
        assertTrue(prompt.get().contains(
                "Average actual effort from 1 similar completed task(s): 3.00"));
    }

    @Test
    void calculatesAverageAcrossRelevantHistoricalTasks() {
        EffortEstimator estimator = estimatorReturning(4);
        Task target = task("target", 5, 3);
        Task firstSimilar = task("first", 4, 2);
        Task secondSimilar = task("second", 6, 4);
        Task differentDifficulty = task("different", 8, 5);
        Task incompleteSimilar = task("incomplete", 10, 3);
        firstSimilar.markCompleted();
        secondSimilar.markCompleted();
        differentDifficulty.markCompleted();

        OptionalDouble average = estimator.calculateHistoricalAverage(
                target,
                List.of(
                        record("R1", firstSimilar, "B1", start, 2),
                        record("R2", secondSimilar, "B2", start, 4),
                        record("R3", differentDifficulty, "B3", start, 5),
                        record("R4", incompleteSimilar, "B4", start, 10)));

        assertTrue(average.isPresent());
        assertEquals(3, average.getAsDouble(), 0.0001);
    }

    @Test
    void latestProgressCorrectionControlsHistoricalContribution() {
        EffortEstimator estimator = estimatorReturning(2);
        Task target = task("target", 3, 3);
        Task historical = task("historical", 2, 3);
        historical.markCompleted();
        StudyBlock block = new StudyBlock(
                "B1", historical, start, start.plusHours(2));
        ProgressRecord completed = new ProgressRecord(
                "R1", historical, block, start.plusDays(1),
                2, ProgressStatus.COMPLETED);
        ProgressRecord correctedToSkipped = new ProgressRecord(
                "R2", historical, block, start.plusDays(2),
                0, ProgressStatus.SKIPPED);

        OptionalDouble average = estimator.calculateHistoricalAverage(
                target, List.of(completed, correctedToSkipped));

        assertFalse(average.isPresent());
    }

    @Test
    void tentativeCompletionDoesNotBecomeHistoricalTrainingData() {
        Task target = task("target", 3, 3);
        Task historical = task("historical", 1, 3);
        StudyBlock session = new StudyBlock("B", historical, start, start.plusHours(1));
        ProgressService progress = new ProgressService();
        progress.inferProgress(session);
        assertFalse(estimatorReturning(2).calculateHistoricalAverage(target, progress.getHistory()).isPresent());
        progress.recordProgress(session, ProgressStatus.COMPLETED, 1);
        assertEquals(1, estimatorReturning(2).calculateHistoricalAverage(target, progress.getHistory()).orElseThrow());
    }

    private EffortEstimator estimatorReturning(double hours) {
        return new EffortEstimator(new PlanningAgent(
                (systemInstruction, userPrompt) ->
                        "{\"estimatedHours\":" + hours + "}"));
    }

    private Task task(String id, double estimatedHours, int difficulty) {
        return new GeneralTask(
                id, "Task " + id, start.plusDays(7), estimatedHours,
                difficulty, PriorityLevel.MEDIUM);
    }

    private ProgressRecord record(
            String recordId,
            Task task,
            String blockId,
            LocalDateTime blockStart,
            double hours) {
        StudyBlock block = new StudyBlock(
                blockId, task, blockStart, blockStart.plusMinutes(
                Math.round(hours * 60)));
        return new ProgressRecord(
                recordId, task, block, blockStart.plusDays(1),
                hours, ProgressStatus.COMPLETED);
    }
}
