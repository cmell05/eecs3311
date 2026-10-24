package com.planpilot.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressRecordTest {
    @Test
    void recordsActualProgressForMatchingTaskAndBlock() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 24, 13, 0);
        Task task = new GeneralTask(
                "T1", "Practice", start.plusDays(1), 2, 2, PriorityLevel.MEDIUM);
        StudyBlock block = new StudyBlock("B1", task, start, start.plusHours(1));

        ProgressRecord record = new ProgressRecord(
                "P1", task, block, start.plusHours(1), 0.5, ProgressStatus.PARTIAL);

        assertEquals(0.5, record.getCompletedHours(), 0.0001);
        assertThrows(IllegalArgumentException.class, () -> new ProgressRecord(
                "P2", task, block, start.plusHours(1), 1.5, ProgressStatus.COMPLETED));
    }
}
