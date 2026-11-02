package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.ScheduleStatus;
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
                task -> 1.0, conflictDetector);
        scheduleService = new ScheduleService(
                taskService, availabilityService, planner,
                new UserPreferences(60, StudyPeriod.ANY, 0));
    }

    @Test
    void generatesScheduleFromServices() {
        Schedule schedule = scheduleService.generateSchedule(date, date);

        assertEquals(1, schedule.getStudyBlocks().size());
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus());
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
                new SchedulePlanner(task -> 1.0, new ConflictDetector()),
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
}
