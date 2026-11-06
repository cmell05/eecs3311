package com.planpilot.controller;

import com.planpilot.domain.*;
import com.planpilot.service.*;
import com.planpilot.event.PlanningEventPublisher;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingWorkflowTest {
    @Test
    void guiConstructedObjectsReachPlannerAndProgressThroughFacade() {
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService();
        var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher);
        var schedules = new ScheduleService(tasks, availability,
                new SchedulePlanner(t -> 1.0, new com.planpilot.service.ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)), new UserPreferences(60, StudyPeriod.EVENING, 10), publisher);
        var controller = new PlanPilotController(courses, tasks, availability, schedules, progress);
        var day = LocalDate.of(2026, 11, 5);
        var course = controller.addCourse(new Course("EECS3311", "Design"));
        var task = controller.addTask(new AcademicTask("Design assignment", day.atTime(17, 0),
                2, 3, PriorityLevel.MEDIUM, course, 10, AcademicTaskType.ASSIGNMENT));
        controller.addTask(new GeneralTask("Personal work", day.atTime(18, 0), 1, 3, PriorityLevel.LOW));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(13, 0), AvailabilityType.AVAILABLE));
        controller.addAvailability(new AvailabilityBlock(day.atTime(13, 0), day.atTime(14, 0), AvailabilityType.WORK));
        assertEquals(2, controller.getOpenTasks().size());
        var schedule = controller.generateSchedule(day, day);
        assertFalse(schedule.getStudyBlocks().isEmpty());
        assertEquals(2, schedule.getStudyBlocks().stream().filter(b -> b.getTask() == task)
                .mapToDouble(StudyBlock::durationHours).sum(), 0.0001);
        assertTrue(new ConflictDetector().isScheduleFeasible(schedule, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay())));
        var block = schedule.getStudyBlocks().get(0);
        controller.updateProgress(block, ProgressStatus.PARTIAL, 0.5);
        assertEquals(0.5, block.getTask().getCompletedHours());
        controller.updateProgress(block, ProgressStatus.SKIPPED, 0);
        assertEquals(0, block.getTask().getCompletedHours());
        controller.updateProgress(block, ProgressStatus.COMPLETED, block.durationHours());
        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
        assertFalse(controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK).getStudyBlocks().isEmpty());
    }

    @Test
    void explainsWhenAvailabilityIsAfterDeadlineOrOutsideWindow() {
        var day = LocalDate.of(2026, 11, 5);
        var planner = new SchedulePlanner(t -> 1, new com.planpilot.service.ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        var task = new GeneralTask("Report", day.atTime(10, 0), 2, 3, PriorityLevel.MEDIUM);
        var availability = java.util.List.of(new AvailabilityBlock(day.atTime(13, 0), day.atTime(16, 0), AvailabilityType.AVAILABLE));
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> planner.createSchedule(
                java.util.List.of(task), availability, new UserPreferences(60, StudyPeriod.ANY, 0), day, day));
        assertTrue(failure.getMessage().contains("No available study time exists before this task's deadline"));
        assertTrue(failure.getPartialSchedule().getStudyBlocks().isEmpty());
    }
}
