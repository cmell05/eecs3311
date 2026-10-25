package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictDetectorTest {
    private final ConflictDetector detector = new ConflictDetector();
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 26, 9, 0);

    @Test
    void detectsOverlappingStudyBlocks() {
        StudyBlock first = block("B1", task("T1", 2), start, start.plusHours(2));
        StudyBlock second = block(
                "B2", task("T2", 2), start.plusHours(1), start.plusHours(3));

        assertTrue(detector.hasConflict(first, second));
    }

    @Test
    void detectsConflictWithFixedCommitment() {
        StudyBlock studyBlock = block(
                "B1", task("T1", 2), start, start.plusHours(2));
        AvailabilityBlock classMeeting = new AvailabilityBlock(
                "A1", start.plusMinutes(30), start.plusMinutes(90), AvailabilityType.CLASS);

        assertTrue(detector.hasConflict(studyBlock, List.of(classMeeting)));
    }

    @Test
    void acceptsValidNonOverlappingSchedule() {
        Schedule schedule = schedule();
        schedule.addStudyBlock(block(
                "B1", task("T1", 1), start, start.plusHours(1)));
        schedule.addStudyBlock(block(
                "B2", task("T2", 1), start.plusHours(1), start.plusHours(2)));
        AvailabilityBlock commitment = new AvailabilityBlock(
                "A1", start.plusHours(3), start.plusHours(4), AvailabilityType.WORK);

        assertTrue(detector.isScheduleFeasible(schedule));
        assertTrue(detector.isScheduleFeasible(schedule, List.of(commitment)));
    }

    @Test
    void rejectsScheduleWithOverlappingBlocks() {
        Schedule schedule = schedule();
        schedule.addStudyBlock(block(
                "B1", task("T1", 2), start, start.plusHours(2)));
        schedule.addStudyBlock(block(
                "B2", task("T2", 2), start.plusHours(1), start.plusHours(3)));

        assertFalse(detector.isScheduleFeasible(schedule));
    }

    @Test
    void workloadDeficitIsPositiveWhenWorkExceedsAvailableTime() {
        List<Task> tasks = List.of(task("T1", 3), task("T2", 2));
        List<AvailabilityBlock> availability = List.of(new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));

        assertEquals(3.0,
                detector.calculateWorkloadDeficit(tasks, availability), 0.0001);
    }

    @Test
    void workloadDeficitIsZeroWhenEnoughTimeExists() {
        List<Task> tasks = List.of(task("T1", 2), task("T2", 1));
        List<AvailabilityBlock> availability = List.of(new AvailabilityBlock(
                "A1", start, start.plusHours(4), AvailabilityType.AVAILABLE));

        assertEquals(0.0,
                detector.calculateWorkloadDeficit(tasks, availability), 0.0001);
    }

    @Test
    void fixedCommitmentsReduceUsableAvailability() {
        List<Task> tasks = List.of(task("T1", 3));
        List<AvailabilityBlock> availability = List.of(
                new AvailabilityBlock(
                        "A1", start, start.plusHours(3), AvailabilityType.AVAILABLE),
                new AvailabilityBlock(
                        "A2", start.plusHours(1), start.plusHours(2), AvailabilityType.CLASS));

        assertEquals(1.0,
                detector.calculateWorkloadDeficit(tasks, availability), 0.0001);
    }

    private Schedule schedule() {
        return new Schedule(
                "S1", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1));
    }

    private Task task(String id, double hours) {
        return new GeneralTask(
                id, "Task " + id, start.plusDays(2), hours,
                3, PriorityLevel.MEDIUM);
    }

    private StudyBlock block(
            String id, Task task, LocalDateTime blockStart, LocalDateTime blockEnd) {
        return new StudyBlock(id, task, blockStart, blockEnd);
    }
}
