package com.planpilot.service;

import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FutureSchedulingTest {
    private final LocalDate day = LocalDate.of(2026, 11, 5);
    private final UserPreferences preferences = new UserPreferences(60, StudyPeriod.ANY, 0);

    private Clock at(LocalDateTime time) {
        return Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }
    private SchedulePlanner planner(Clock clock) {
        return new SchedulePlanner(task -> 1, new ConflictDetector(), clock);
    }
    private Task task(double hours, LocalDateTime deadline) {
        return new GeneralTask("Work", deadline, hours, 3, PriorityLevel.MEDIUM);
    }
    private AvailabilityBlock available(LocalDateTime start, LocalDateTime end) {
        return new AvailabilityBlock(start, end, AvailabilityType.AVAILABLE);
    }

    @Test void clipsAnOngoingWindowAndRoundsUpWithoutEverBackdatingWork() {
        LocalDateTime now = day.atTime(10, 30, 15);
        var result = planner(at(now)).createSchedule(List.of(task(1, day.atTime(17, 0))),
                List.of(available(day.atTime(9, 0), day.atTime(13, 0))), preferences, day, day);
        assertEquals(day.atTime(10, 31), result.getStudyBlocks().get(0).getStart());
        assertTrue(result.getStudyBlocks().stream().allMatch(b -> !b.getStart().isBefore(now)));
    }

    @Test void retainsAnExactMinuteBoundaryAndFutureWindowStarts() {
        var p = planner(at(day.atTime(10, 30)));
        var result = p.createSchedule(List.of(task(1, day.atTime(17, 0))),
                List.of(available(day.atTime(9, 0), day.atTime(13, 0))), preferences, day, day);
        assertEquals(day.atTime(10, 30), result.getStudyBlocks().get(0).getStart());
        LocalDate tomorrow = day.plusDays(1);
        var future = p.createSchedule(List.of(task(1, tomorrow.atTime(17, 0))),
                List.of(available(tomorrow.atTime(9, 0), tomorrow.atTime(11, 0))), preferences, tomorrow, tomorrow);
        assertEquals(tomorrow.atTime(9, 0), future.getStudyBlocks().get(0).getStart());
    }

    @Test void excludesExpiredTimeFromCapacityAndReportsTheRealDeficit() {
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> planner(at(day.atTime(11, 0)))
                .createSchedule(List.of(task(2, day.atTime(17, 0))),
                        List.of(available(day.atTime(9, 0), day.atTime(12, 0))), preferences, day, day));
        assertEquals(1, failure.getWorkloadDeficitHours(), .00001);
        assertEquals(1, failure.getPartialSchedule().getStudyBlocks().stream().mapToDouble(StudyBlock::durationHours).sum(), .00001);
    }

    @Test void clipsAnOvernightWindowAtTheCurrentLocalTime() {
        var now = day.atTime(0, 15);
        var result = planner(at(now)).createSchedule(List.of(task(.5, day.atTime(2, 0))),
                List.of(available(day.minusDays(1).atTime(22, 0), day.atTime(1, 0))),
                preferences, day.minusDays(1), day);
        assertEquals(now, result.getStudyBlocks().get(0).getStart());
        assertEquals(day.atTime(0, 45), result.getStudyBlocks().get(0).getEnd());
    }

    @Test void anExpiredPlanningWindowProducesNoNewSessionsAndExplainsWhy() {
        LocalDate yesterday = day.minusDays(1);
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> planner(at(day.atTime(11, 0)))
                .createSchedule(List.of(task(2, day.plusDays(1).atTime(17, 0))),
                        List.of(available(yesterday.atTime(9, 0), yesterday.atTime(13, 0))), preferences, yesterday, yesterday));
        assertTrue(failure.getPartialSchedule().getStudyBlocks().isEmpty());
        assertTrue(failure.getMessage().contains("planning window has already ended"));
        assertEquals(2, failure.getWorkloadDeficitHours());
    }

    @Test void aPassedDeadlineCannotBeSatisfiedByHistoricalAvailability() {
        var task = task(1, day.atTime(10, 0));
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> planner(at(day.atTime(11, 0)))
                .createSchedule(List.of(task), List.of(available(day.atTime(9, 0), day.atTime(13, 0))), preferences, day, day));
        assertTrue(failure.getPartialSchedule().getStudyBlocks().isEmpty());
        assertTrue(failure.getMessage().contains("deadline has already passed"));
        assertEquals(0, task.getCompletedHours());
    }

    @Test void preferencesAndCommitmentsCannotReintroducePastTime() {
        var settings = new UserPreferences(60, StudyPeriod.MORNING, 0);
        var commitment = new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.WORK);
        var result = planner(at(day.atTime(10, 30, 15))).createSchedule(List.of(task(1, day.atTime(18, 0))),
                List.of(available(day.atTime(6, 0), day.atTime(14, 0)), commitment), settings, day, day);
        assertEquals(day.atTime(11, 0), result.getStudyBlocks().get(0).getStart());
        assertTrue(new ConflictDetector().isScheduleFeasible(result, List.of(commitment)));
    }

    @Test void regeneratedWorkIsFutureOnlyAndCannotImmediatelyBecomeInferredProgress() {
        var clock = new MutableClock(day.atTime(9, 0));
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService(); var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, clock, null);
        var scheduling = new ScheduleService(tasks, availability, planner(clock), preferences, publisher);
        var controller = new PlanPilotController(courses, tasks, availability, scheduling, progress);
        var task = controller.addTask(task(2, day.atTime(17, 0)));
        controller.addAvailability(available(day.atTime(9, 0), day.atTime(14, 0)));
        var original = controller.generateSchedule(day.minusDays(1), day);
        var completed = original.getStudyBlocks().get(0);
        controller.updateProgress(completed, ProgressStatus.COMPLETED, 1);
        clock.now = day.atTime(10, 30, 15);
        var revised = controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        assertTrue(revised.getStudyBlocks().stream().anyMatch(b -> b == completed));
        var pending = revised.getStudyBlocks().stream().filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED).toList();
        assertEquals(1, pending.size());
        assertEquals(day.atTime(10, 31), pending.get(0).getStart());
        controller.refreshProgress();
        assertEquals(1, task.getCompletedHours()); assertEquals(1, task.getRemainingHours());
        assertEquals(0, controller.getInferredHours(task));
        assertEquals(1, controller.getProgressHistory(task).size());
        var regenerated = controller.requestReplan(ReplanOption.REPLAN_ENTIRE_WINDOW);
        assertTrue(regenerated.getStudyBlocks().stream().filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .allMatch(b -> !b.getStart().isBefore(clock.now)));
    }

    @Test void partialCompletionCanLeaveARealDeficitAndExtraAvailabilityResolvesIt() {
        var clock = new MutableClock(day.atTime(9, 0));
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService(); var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, clock, null);
        var scheduling = new ScheduleService(tasks, availability, planner(clock),
                new UserPreferences(60, StudyPeriod.ANY, 10), publisher);
        var controller = new PlanPilotController(courses, tasks, availability, scheduling, progress);
        var task = controller.addTask(task(3, day.atTime(17, 0)));
        var window = available(day.atTime(9, 0), day.atTime(12, 20));
        controller.addAvailability(window);
        var initial = controller.generateSchedule(day, day);
        assertEquals(3, initial.getStudyBlocks().size());
        var first = initial.getStudyBlocks().get(0);
        clock.now = day.atTime(10, 0);
        controller.updateProgress(first, ProgressStatus.PARTIAL, .5);
        var failure = assertThrows(ScheduleInfeasibleException.class,
                () -> controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK));
        assertEquals(.5, controller.getConfirmedHours(task));
        assertEquals(2.5, task.getRemainingHours());
        assertEquals(.5, failure.getWorkloadDeficitHours(), .00001);
        double scheduled = failure.getPartialSchedule().getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .mapToDouble(StudyBlock::durationHours).sum();
        assertEquals(2, scheduled, .00001);
        assertEquals(task.getRemainingHours(), scheduled + failure.getWorkloadDeficitHours(), .00001);
        assertSame(failure.getPartialSchedule(), scheduling.getCurrentSchedule());
        assertTrue(controller.getProgressSessions().stream().anyMatch(b -> b == first));
        controller.updateAvailability(new AvailabilityBlock(window.getId(), window.getStart(),
                day.atTime(13, 0), AvailabilityType.AVAILABLE));
        var revised = controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        assertEquals(2.5, revised.getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .mapToDouble(StudyBlock::durationHours).sum(), .00001);
        assertEquals(.5, controller.getConfirmedHours(task));
        assertEquals(2.5, task.getRemainingHours());
    }

    @Test void fullDaysFitRemainingWorkDespitePreviouslyReportedFutureSessionsAndNoonDeadline() {
        var clock = at(day.atTime(21, 40));
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService(); var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, clock, null);
        var scheduling = new ScheduleService(tasks, availability, planner(clock),
                new UserPreferences(60, StudyPeriod.ANY, 10), publisher);
        var controller = new PlanPilotController(courses, tasks, availability, scheduling, progress);
        var tomorrow = day.plusDays(1);
        var task = controller.addTask(task(3, tomorrow.atTime(12, 0)));
        var partial = new StudyBlock("partial", task, tomorrow.atTime(9, 0), tomorrow.atTime(10, 0));
        var completed = new StudyBlock("completed", task, tomorrow.atTime(10, 10), tomorrow.atTime(11, 10));
        controller.updateProgress(partial, ProgressStatus.PARTIAL, .5);
        controller.updateProgress(completed, ProgressStatus.COMPLETED, 1);
        controller.addAvailability(available(day.atStartOfDay(), day.atTime(23, 59)));
        controller.addAvailability(available(tomorrow.atStartOfDay(), tomorrow.atTime(23, 59)));
        var revised = assertDoesNotThrow(() -> controller.generateSchedule(day, tomorrow));
        assertEquals(1.5, controller.getConfirmedHours(task));
        assertEquals(1.5, task.getRemainingHours());
        assertEquals(1.5, revised.getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .mapToDouble(StudyBlock::durationHours).sum(), .00001);
        assertTrue(revised.getStudyBlocks().stream().filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .allMatch(b -> !b.getEnd().isAfter(task.getDeadline())));
    }

    private static final class MutableClock extends Clock {
        private LocalDateTime now;
        private MutableClock(LocalDateTime now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.toInstant(ZoneOffset.UTC); }
    }
}
