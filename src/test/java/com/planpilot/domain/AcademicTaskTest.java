package com.planpilot.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class AcademicTaskTest {
    private final Course course = new Course("C1", "EECS3311", "Software Design");

    @Test
    void partialProgressReducesRemainingHours() {
        AcademicTask task = new AcademicTask(
                "T1", "Assignment", LocalDateTime.now().plusDays(2),
                4, 3, PriorityLevel.HIGH, course, 10,
                AcademicTaskType.ASSIGNMENT);

        task.recordProgress(1.5);

        assertEquals(2.5, task.getRemainingHours(), 0.0001);
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
    }

    @Test
    void completingTaskSetsRemainingHoursToZero() {
        AcademicTask task = new AcademicTask(
                "T1", "Assignment", LocalDateTime.now().plusDays(2),
                4, 3, PriorityLevel.HIGH, course, 10,
                AcademicTaskType.ASSIGNMENT);

        task.markCompleted();

        assertEquals(0, task.getRemainingHours(), 0.0001);
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
    }

    @Test
    void progressCannotExceedEstimatedHours() {
        AcademicTask task = new AcademicTask(
                "T1", "Assignment", LocalDateTime.now().plusDays(2),
                4, 3, PriorityLevel.HIGH, course, 10,
                AcademicTaskType.ASSIGNMENT);

        task.recordProgress(6);

        assertEquals(4, task.getCompletedHours(), 0.0001);
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
    }

    @Test
    void skippedTaskRetainsRemainingWork() {
        AcademicTask task = new AcademicTask(
                "T1", "Assignment", LocalDateTime.now().plusDays(2),
                4, 3, PriorityLevel.HIGH, course, 10,
                AcademicTaskType.ASSIGNMENT);

        task.markSkipped();

        assertEquals(4, task.getRemainingHours(), 0.0001);
        assertEquals(TaskStatus.SKIPPED, task.getStatus());
    }
}
