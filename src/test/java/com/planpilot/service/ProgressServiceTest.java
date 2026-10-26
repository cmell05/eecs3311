package com.planpilot.service;

import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressServiceTest {
    private ProgressService service;
    private LocalDateTime start;

    @BeforeEach
    void setUp() {
        service = new ProgressService();
        start = LocalDateTime.of(2026, 11, 1, 13, 0);
    }

    @Test
    void completedWorkUpdatesTaskAndCreatesHistory() {
        Task task = task("T1", 4);
        StudyBlock block = block("B1", task, 2);

        service.recordProgress(block, ProgressStatus.COMPLETED, 2);

        assertEquals(2, task.getCompletedHours(), 0.0001);
        assertEquals(2, task.getRemainingHours(), 0.0001);
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        assertEquals(ProgressStatus.COMPLETED, block.getProgressStatus());
        assertEquals(1, service.getHistoryForTask(task).size());
    }

    @Test
    void partialWorkRecordsOnlyReportedHours() {
        Task task = task("T1", 4);
        StudyBlock block = block("B1", task, 2);

        service.recordProgress(block, ProgressStatus.PARTIAL, 0.75);

        assertEquals(0.75, task.getCompletedHours(), 0.0001);
        assertEquals(ProgressStatus.PARTIAL, block.getProgressStatus());
        assertEquals(0.75,
                service.getHistoryForTask(task).get(0).getCompletedHours(), 0.0001);
    }

    @Test
    void skippedCorrectionRemovesPreviouslyInferredProgress() {
        Task task = task("T1", 4);
        StudyBlock block = block("B1", task, 2);
        service.inferProgress(block);

        service.recordProgress(block, ProgressStatus.SKIPPED, 0);

        assertEquals(0, task.getCompletedHours(), 0.0001);
        assertEquals(4, task.getRemainingHours(), 0.0001);
        assertEquals(ProgressStatus.SKIPPED, block.getProgressStatus());
        assertEquals(2, service.getHistoryForTask(task).size());
    }

    @Test
    void partialCorrectionReplacesInferredContribution() {
        Task task = task("T1", 4);
        StudyBlock block = block("B1", task, 2);
        service.inferProgress(block);

        service.recordProgress(block, ProgressStatus.PARTIAL, 0.5);

        assertEquals(0.5, task.getCompletedHours(), 0.0001);
        assertEquals(3.5, task.getRemainingHours(), 0.0001);
    }

    @Test
    void historyIsFilteredByTask() {
        Task firstTask = task("T1", 4);
        Task secondTask = task("T2", 3);
        service.inferProgress(block("B1", firstTask, 1));
        service.recordProgress(
                block("B2", secondTask, 1), ProgressStatus.PARTIAL, 0.5);

        List<ProgressRecord> firstHistory = service.getHistoryForTask(firstTask);

        assertEquals(1, firstHistory.size());
        assertEquals(firstTask, firstHistory.get(0).getTask());
    }

    @Test
    void rejectsInvalidProgressValues() {
        Task task = task("T1", 2);
        StudyBlock block = block("B1", task, 1);

        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress(block, ProgressStatus.PARTIAL, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress(block, ProgressStatus.PARTIAL, 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress(block, ProgressStatus.COMPLETED, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress(block, ProgressStatus.SKIPPED, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress(block, ProgressStatus.PLANNED, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress(block, ProgressStatus.PARTIAL, Double.NaN));
    }

    @Test
    void rejectsProgressBeyondTaskEstimate() {
        Task task = task("T1", 1);
        StudyBlock firstBlock = block("B1", task, 1);
        StudyBlock secondBlock = new StudyBlock(
                "B2", task, start.plusHours(2), start.plusHours(3));
        service.inferProgress(firstBlock);

        assertThrows(IllegalArgumentException.class,
                () -> service.inferProgress(secondBlock));
    }

    private Task task(String id, double estimatedHours) {
        return new GeneralTask(
                id, "Task " + id, start.plusDays(2), estimatedHours,
                3, PriorityLevel.MEDIUM);
    }

    private StudyBlock block(String id, Task task, double durationHours) {
        return new StudyBlock(
                id, task, start, start.plusMinutes(Math.round(durationHours * 60)));
    }
}
