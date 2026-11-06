package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.ScheduleStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleServiceTest {
    private ScheduleService scheduleService;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        date = LocalDate.of(2026, 10, 26);
        LocalDateTime start = date.atTime(9, 0);
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        taskService.createTask(new GeneralTask(
                "T1", "Prepare presentation", start.plusDays(1),
                1, 2, PriorityLevel.MEDIUM));

        AvailabilityService availabilityService = new AvailabilityService();
        availabilityService.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));

        ConflictDetector conflictDetector = new ConflictDetector();
        SchedulePlanner planner = new SchedulePlanner(
                task -> 1.0, conflictDetector, java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        scheduleService = new ScheduleService(
                taskService, availabilityService, planner,
                new UserPreferences(60, StudyPeriod.ANY, 0));
    }

    @Test
    void generatesScheduleFromServices() {
        Schedule schedule = scheduleService.generateSchedule(date, date);

        assertEquals(1, schedule.getStudyBlocks().size());
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus());
        assertSame(schedule, scheduleService.getCurrentSchedule());
    }

    @Test
    void currentScheduleIsUnavailableBeforeGeneration() {
        assertThrows(IllegalStateException.class,
                scheduleService::getCurrentSchedule);
    }

    @Test
    void markScheduleOutdatedChangesCurrentScheduleState() {
        Schedule schedule = scheduleService.generateSchedule(date, date);

        scheduleService.markScheduleOutdated();

        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
    }

    @Test
    void keepCurrentReplanOptionDoesNotRegenerateSchedule() {
        Schedule schedule = scheduleService.generateSchedule(date, date);

        Schedule keptSchedule = scheduleService.replan(
                ReplanOption.KEEP_CURRENT_SCHEDULE);

        assertSame(schedule, keptSchedule);
    }

    @Test
    void infeasiblePartialScheduleBecomesCurrentAndCanBeMarkedOutdated() {
        LocalDateTime start = date.atTime(9, 0);
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        taskService.createTask(new GeneralTask(
                "T2", "Large task", start.plusDays(1),
                2, 2, PriorityLevel.MEDIUM));
        AvailabilityService availabilityService = new AvailabilityService();
        availabilityService.addAvailability(new AvailabilityBlock(
                "A2", start, start.plusHours(1), AvailabilityType.AVAILABLE));
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = {0};
        publisher.subscribe(() -> notifications[0]++);
        ScheduleService infeasibleService = new ScheduleService(
                taskService, availabilityService,
                new SchedulePlanner(task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)),
                new UserPreferences(60, StudyPeriod.ANY, 0), publisher);

        ScheduleInfeasibleException exception = assertThrows(
                ScheduleInfeasibleException.class,
                () -> infeasibleService.generateSchedule(date, date));
        Schedule partialSchedule = exception.getPartialSchedule();

        assertSame(partialSchedule, infeasibleService.replan(
                ReplanOption.KEEP_CURRENT_SCHEDULE));
        assertEquals(1, notifications[0]);
        infeasibleService.markScheduleOutdated();
        assertEquals(ScheduleStatus.OUTDATED, partialSchedule.getStatus());
        assertEquals(2, notifications[0]);
    }

    @Test
    void updatingPreferencesMarksCurrentScheduleOutdated() {
        Schedule schedule = scheduleService.generateSchedule(date, date);
        UserPreferences updated = new UserPreferences(
                30, StudyPeriod.EVENING, 5);

        scheduleService.updatePreferences(updated);

        UserPreferences saved = scheduleService.getPreferences();
        assertEquals(30, saved.getMaxSessionLength());
        assertEquals(StudyPeriod.EVENING, saved.getPreferredStudyPeriod());
        assertEquals(5, saved.getBreakLength());
        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
    }

    @Test
    void rescheduleIncompleteWorkPreservesCompletedBlocks() {
        LocalDateTime start = date.atTime(9, 0);
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        GeneralTask task = new GeneralTask(
                "T2", "Two-part task", start.plusDays(1),
                2, 2, PriorityLevel.MEDIUM);
        taskService.createTask(task);
        AvailabilityService availabilityService = new AvailabilityService();
        availabilityService.addAvailability(new AvailabilityBlock(
                "A2", start, start.plusHours(3), AvailabilityType.AVAILABLE));
        ScheduleService service = new ScheduleService(
                taskService, availabilityService,
                new SchedulePlanner(candidate -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)),
                new UserPreferences(60, StudyPeriod.ANY, 0));
        Schedule original = service.generateSchedule(date, date);
        StudyBlock completed = original.getStudyBlocks().get(0);
        completed.markCompleted();
        task.setCompletedHours(completed.durationHours());

        Schedule replanned = service.replan(
                ReplanOption.RESCHEDULE_INCOMPLETE_WORK);

        assertEquals(2, replanned.getStudyBlocks().size());
        assertEquals(1, replanned.getStudyBlocks().stream()
                .filter(block -> block.getProgressStatus() == ProgressStatus.COMPLETED)
                .count());
        assertEquals(1, replanned.getStudyBlocks().stream()
                .filter(block -> block.getProgressStatus() == ProgressStatus.PLANNED)
                .count());
        assertEquals(start.plusHours(1), replanned.getStudyBlocks().get(1).getStart());

        Schedule entireWindow = service.replan(ReplanOption.REPLAN_ENTIRE_WINDOW);
        assertEquals(2, entireWindow.getStudyBlocks().size());
        assertSame(completed, entireWindow.getStudyBlocks().get(0));
        assertEquals(ProgressStatus.COMPLETED,
                entireWindow.getStudyBlocks().get(0).getProgressStatus());
    }

    @Test
    void optionalOutdatedMarkIsSafeWithoutCurrentSchedule() {
        scheduleService.markScheduleOutdatedIfPresent();
    }
}
