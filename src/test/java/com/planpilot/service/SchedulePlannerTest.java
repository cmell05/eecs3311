package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.strategy.PriorityStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulePlannerTest {
    private final LocalDate date = LocalDate.of(2026, 10, 26);
    private final LocalDateTime start = date.atTime(9, 0);
    private final ConflictDetector conflictDetector = new ConflictDetector();
    private final PriorityStrategy priorityStrategy = task ->
            task.getUserImportance() == PriorityLevel.HIGH ? 100 : 10;
    private final SchedulePlanner planner = new SchedulePlanner(
            priorityStrategy, conflictDetector);

    @Test
    void schedulesSimpleTaskInAvailableTime() {
        Task task = task("T1", 1, PriorityLevel.MEDIUM, start.plusDays(1));

        Schedule schedule = createSchedule(
                List.of(task), List.of(available("A1", start, start.plusHours(2))),
                preferences(60, 0));

        assertEquals(1, schedule.getStudyBlocks().size());
        assertEquals(task, schedule.getStudyBlocks().get(0).getTask());
        assertEquals(start, schedule.getStudyBlocks().get(0).getStart());
    }

    @Test
    void allocatesHigherPriorityTaskFirstWhenTimeCompetes() {
        Task lowPriority = task("T1", 1, PriorityLevel.LOW, start.plusDays(1));
        Task highPriority = task("T2", 1, PriorityLevel.HIGH, start.plusDays(1));

        ScheduleInfeasibleException exception = assertThrows(
                ScheduleInfeasibleException.class,
                () -> createSchedule(
                        List.of(lowPriority, highPriority),
                        List.of(available("A1", start, start.plusHours(1))),
                        preferences(60, 0)));

        assertEquals(highPriority,
                exception.getPartialSchedule().getStudyBlocks().get(0).getTask());
        assertEquals(1.0, exception.getWorkloadDeficitHours(), 0.0001);
    }

    @Test
    void generatedStudyBlocksDoNotOverlap() {
        Schedule schedule = createSchedule(
                List.of(
                        task("T1", 1, PriorityLevel.HIGH, start.plusDays(1)),
                        task("T2", 1, PriorityLevel.MEDIUM, start.plusDays(1))),
                List.of(available("A1", start, start.plusHours(3))),
                preferences(60, 15));

        List<StudyBlock> blocks = schedule.getStudyBlocks();
        assertFalse(conflictDetector.hasConflict(blocks.get(0), blocks.get(1)));
    }

    @Test
    void generatedBlocksAvoidFixedCommitments() {
        Task task = task("T1", 2, PriorityLevel.MEDIUM, start.plusDays(1));
        AvailabilityBlock fixed = new AvailabilityBlock(
                "A2", start.plusHours(1), start.plusHours(2), AvailabilityType.CLASS);

        Schedule schedule = createSchedule(
                List.of(task),
                List.of(available("A1", start, start.plusHours(3)), fixed),
                preferences(60, 0));

        assertTrue(schedule.getStudyBlocks().stream()
                .noneMatch(block -> conflictDetector.hasConflict(block, List.of(fixed))));
    }

    @Test
    void doesNotScheduleWorkAfterDeadline() {
        LocalDateTime deadline = start.plusMinutes(30);
        Task task = task("T1", 1, PriorityLevel.MEDIUM, deadline);

        ScheduleInfeasibleException exception = assertThrows(
                ScheduleInfeasibleException.class,
                () -> createSchedule(
                        List.of(task),
                        List.of(available("A1", start, start.plusHours(2))),
                        preferences(60, 0)));

        assertTrue(exception.getPartialSchedule().getStudyBlocks().stream()
                .allMatch(block -> !block.getEnd().isAfter(deadline)));
    }

    @Test
    void splitsLargeTaskIntoMultipleStudyBlocks() {
        Task task = task("T1", 2.5, PriorityLevel.MEDIUM, start.plusDays(1));

        Schedule schedule = createSchedule(
                List.of(task), List.of(available("A1", start, start.plusHours(3))),
                preferences(60, 0));

        assertEquals(3, schedule.getStudyBlocks().size());
    }

    @Test
    void respectsMaximumSessionLength() {
        Task task = task("T1", 2, PriorityLevel.MEDIUM, start.plusDays(1));

        Schedule schedule = createSchedule(
                List.of(task), List.of(available("A1", start, start.plusHours(3))),
                preferences(45, 10));

        assertTrue(schedule.getStudyBlocks().stream()
                .allMatch(block -> block.durationHours() <= 0.75));
    }

    @Test
    void reportsWorkloadDeficitWhenWorkCannotFit() {
        Task task = task("T1", 3, PriorityLevel.MEDIUM, start.plusDays(1));

        ScheduleInfeasibleException exception = assertThrows(
                ScheduleInfeasibleException.class,
                () -> createSchedule(
                        List.of(task),
                        List.of(available("A1", start, start.plusHours(1))),
                        preferences(60, 0)));

        assertEquals(2.0, exception.getWorkloadDeficitHours(), 0.0001);
    }

    @Test
    void validGeneratedSchedulePassesConflictDetector() {
        List<AvailabilityBlock> availability = List.of(
                available("A1", start, start.plusHours(2)));
        Schedule schedule = createSchedule(
                List.of(task("T1", 1, PriorityLevel.MEDIUM, start.plusDays(1))),
                availability, preferences(60, 0));

        assertTrue(conflictDetector.isScheduleFeasible(schedule, availability));
    }

    private Schedule createSchedule(
            List<? extends Task> tasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences) {
        return planner.createSchedule(tasks, availability, preferences, date, date);
    }

    private Task task(
            String id, double hours, PriorityLevel priority,
            LocalDateTime deadline) {
        return new GeneralTask(id, "Task " + id, deadline, hours, 3, priority);
    }

    private AvailabilityBlock available(
            String id, LocalDateTime slotStart, LocalDateTime slotEnd) {
        return new AvailabilityBlock(
                id, slotStart, slotEnd, AvailabilityType.AVAILABLE);
    }

    private UserPreferences preferences(int maxSessionMinutes, int breakMinutes) {
        return new UserPreferences(maxSessionMinutes, StudyPeriod.ANY, breakMinutes);
    }
}
