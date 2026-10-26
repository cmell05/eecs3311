package com.planpilot.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudyBlockTest {
    @Test
    void tracksDurationAndProgressStatus() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 24, 18, 0);
        Task task = new GeneralTask(
                "T1", "Portfolio", start.plusDays(2), 3, 2, PriorityLevel.LOW);
        StudyBlock block = new StudyBlock("B1", task, start, start.plusMinutes(75));

        assertEquals(1.25, block.durationHours(), 0.0001);
        assertEquals(ProgressStatus.PLANNED, block.getProgressStatus());

        block.markPartial();

        assertEquals(ProgressStatus.PARTIAL, block.getProgressStatus());
    }
}
