package com.planpilot.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleTest {
    @Test
    void addsStudyBlocksAndMarksScheduleOutdated() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 24, 9, 0);
        Task task = new GeneralTask(
                "T1", "Interview preparation", start.plusDays(1),
                2, 3, PriorityLevel.MEDIUM);
        StudyBlock block = new StudyBlock("B1", task, start, start.plusMinutes(90));
        Schedule schedule = new Schedule(
                "S1", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 30));

        schedule.addStudyBlock(block);
        schedule.markOutdated();

        assertEquals(1, schedule.getStudyBlocks().size());
        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
        assertThrows(UnsupportedOperationException.class,
                () -> schedule.getStudyBlocks().add(block));
    }
}
