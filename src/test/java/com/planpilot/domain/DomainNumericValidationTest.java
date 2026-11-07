package com.planpilot.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DomainNumericValidationTest {
    private GeneralTask task() {
        return new GeneralTask("Reading", LocalDateTime.of(2026, 11, 20, 17, 0), 4, 3, PriorityLevel.MEDIUM);
    }

    @Test void invalidEstimatedHoursAreRejectedBeforeTheyCanCorruptTaskState() {
        Task task = task(); task.recordProgress(1);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> task.setEstimatedHours(value));
            assertEquals(4, task.getEstimatedHours()); assertEquals(1, task.getCompletedHours());
            assertEquals(3, task.getRemainingHours()); assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
            assertThrows(IllegalArgumentException.class, () -> new GeneralTask("Invalid", task.getDeadline(), value, 3, PriorityLevel.MEDIUM));
        }
    }

    @Test void nonfiniteProgressIsRejectedWithoutChangingCompletedEffortOrStatus() {
        Task task = task(); task.recordProgress(1);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> task.recordProgress(value));
            assertEquals(1, task.getCompletedHours()); assertEquals(3, task.getRemainingHours());
            assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        }
        task.recordProgress(0); assertEquals(1, task.getCompletedHours());
        task.recordProgress(3); assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertThrows(IllegalArgumentException.class, () -> task.recordProgress(Double.NaN));
        assertEquals(4, task.getCompletedHours());
    }

    @Test void invalidAssessmentWeightsNeverReachSchedulingOrPersistence() {
        var course = new Course("EECS3311", "Design");
        var task = new AcademicTask("Report", LocalDateTime.of(2026, 11, 20, 17, 0), 4, 3,
                PriorityLevel.MEDIUM, course, 20, AcademicTaskType.ASSIGNMENT);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 101}) {
            assertThrows(IllegalArgumentException.class, () -> task.setAssessmentWeight(value));
            assertEquals(20, task.getAssessmentWeight());
            assertThrows(IllegalArgumentException.class, () -> new AcademicTask("Invalid", task.getDeadline(), 4, 3,
                    PriorityLevel.MEDIUM, course, value, AcademicTaskType.ASSIGNMENT));
        }
        task.setAssessmentWeight(0); assertEquals(0, task.getAssessmentWeight());
        task.setAssessmentWeight(100); assertEquals(100, task.getAssessmentWeight());
    }
}
