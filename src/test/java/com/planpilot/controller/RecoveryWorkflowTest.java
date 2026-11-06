package com.planpilot.controller;

import com.planpilot.ai.*;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryWorkflowTest {
    @TempDir Path directory;
    private final LocalDate day = LocalDate.of(2026, 11, 5);
    private final Clock clock = Clock.fixed(day.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final PlanningEventPublisher publisher = new PlanningEventPublisher();
    private final CourseService courses = new CourseService();
    private final TaskService tasks = new TaskService(courses, publisher);
    private final AvailabilityService availability = new AvailabilityService();
    private final ProgressService progress = new ProgressService(publisher, clock, null);
    private final ScheduleService schedules = new ScheduleService(tasks, availability,
            new SchedulePlanner(t -> t.getUserImportance() == PriorityLevel.HIGH ? 100 : 10, new ConflictDetector(), clock),
            new UserPreferences(60, StudyPeriod.ANY, 0), publisher);
    private final PlanPilotController controller = new PlanPilotController(courses, tasks, availability, schedules, progress);

    private Task add(String title, double hours, PriorityLevel priority) {
        return controller.addTask(new GeneralTask(title, day.plusDays(1).atTime(18, 0), hours, 3, priority));
    }
    private ScheduleInfeasibleException initialFailure() {
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(10, 0), AvailabilityType.AVAILABLE));
        return assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
    }
    private RecoveryRequest choice(ScheduleInfeasibleException failure, RecoveryAction action,
                                   AvailabilityBlock block, LocalDate end, Task task) {
        return new RecoveryRequest(failure.getPartialSchedule().getId(), action, block, end, task == null ? null : task.getId());
    }

    @Test void addingAvailableTimeAppliesAndReplansWithoutChangingDeadlineOrProgress() {
        Task task = add("Essay", 2, PriorityLevel.HIGH); var deadline = task.getDeadline();
        var failure = initialFailure();
        var plan = controller.applyRecovery(choice(failure, RecoveryAction.ADD_AVAILABILITY,
                new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.AVAILABLE), null, null));
        assertEquals(2, plan.getStudyBlocks().stream().mapToDouble(StudyBlock::durationHours).sum());
        assertEquals(deadline, task.getDeadline()); assertEquals(0, task.getCompletedHours());
        assertEquals(2, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
    }

    @Test void extensionUsesExistingAvailabilityOnNewDates() {
        Task task = add("Essay", 2, PriorityLevel.MEDIUM);
        controller.addAvailability(new AvailabilityBlock(day.plusDays(1).atTime(9, 0), day.plusDays(1).atTime(10, 0), AvailabilityType.AVAILABLE));
        var failure = initialFailure();
        var plan = controller.applyRecovery(choice(failure, RecoveryAction.EXTEND_PLANNING_WINDOW, null, day.plusDays(1), null));
        assertEquals(day.plusDays(1), plan.getEndDate()); assertEquals(2, plan.getStudyBlocks().size());
        assertEquals(day.plusDays(1).atTime(18, 0), task.getDeadline());
    }

    @Test void extensionDoesNotInventTimeAndRetainsAnHonestPartialPlan() {
        add("Essay", 2, PriorityLevel.MEDIUM); var failure = initialFailure();
        var stillInfeasible = assertThrows(ScheduleInfeasibleException.class, () -> controller.applyRecovery(
                choice(failure, RecoveryAction.EXTEND_PLANNING_WINDOW, null, day.plusDays(1), null)));
        assertEquals(day.plusDays(1), stillInfeasible.getPartialSchedule().getEndDate());
        assertSame(stillInfeasible.getPartialSchedule(), schedules.getCurrentSchedule());
        assertEquals(1, stillInfeasible.getWorkloadDeficitHours());
    }

    @Test void deferralIsVisibleReversibleAndDoesNotDeleteOrChangeDeadline() {
        Task high = add("Urgent", 1, PriorityLevel.HIGH); Task low = add("Reading", 1, PriorityLevel.LOW);
        var deadline = low.getDeadline(); var failure = initialFailure();
        var plan = controller.applyRecovery(choice(failure, RecoveryAction.DEFER_LOWER_PRIORITY_TASK, null, null, low));
        assertEquals(List.of(high), plan.getStudyBlocks().stream().map(StudyBlock::getTask).toList());
        assertEquals(day.plusDays(1).atStartOfDay(), low.getDeferredUntil());
        assertEquals(deadline, low.getDeadline()); assertEquals(1, low.getRemainingHours());
        assertEquals(2, controller.getOpenTasks().size());
        controller.resumeTask(low.getId());
        assertNull(low.getDeferredUntil()); assertSame(plan, schedules.getCurrentSchedule());
        assertEquals(ScheduleStatus.OUTDATED, plan.getStatus());
        assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
    }

    @Test void keepingConstraintsAndCancellingProposalDoNotMutateThePlan() {
        add("Essay", 2, PriorityLevel.MEDIUM); var failure = initialFailure();
        var plan = controller.applyRecovery(choice(failure, RecoveryAction.KEEP_CURRENT_CONSTRAINTS, null, null, null));
        assertSame(failure.getPartialSchedule(), plan);
        PlanningAgent fakeAgent = new PlanningAgent((system, prompt) ->
                "{\"summary\":\"Add another hour.\",\"suggestions\":[{\"action\":\"ADD_AVAILABILITY\",\"explanation\":\"Add time.\"}]}");
        var aiController = new PlanPilotController(courses, tasks, availability, schedules, progress, null, fakeAgent);
        RecoveryPlan advice = aiController.requestRecoverySuggestions(failure);
        assertEquals(RecoveryAction.ADD_AVAILABILITY, advice.suggestions().get(0).action());
        assertSame(plan, schedules.getCurrentSchedule());
        assertEquals(1, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
    }

    @Test void invalidAndStaleChoicesCannotPartiallyChangeData() {
        add("Essay", 2, PriorityLevel.MEDIUM); var failure = initialFailure();
        assertThrows(IllegalArgumentException.class, () -> controller.applyRecovery(
                choice(failure, RecoveryAction.EXTEND_PLANNING_WINDOW, null, day, null)));
        assertEquals(day, schedules.getCurrentSchedule().getEndDate());
        assertThrows(IllegalArgumentException.class, () -> controller.applyRecovery(
                new RecoveryRequest(failure.getPartialSchedule().getId(), RecoveryAction.DEFER_LOWER_PRIORITY_TASK, null, null, "missing")));
        assertNull(controller.getTasks().get(0).getDeferredUntil());
        var stale = choice(failure, RecoveryAction.ADD_AVAILABILITY,
                new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.AVAILABLE), null, null);
        add("Changed workload", 1, PriorityLevel.LOW);
        assertThrows(IllegalStateException.class, () -> controller.applyRecovery(stale));
        assertEquals(1, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
    }

    @Test void invalidAvailabilityIsRejectedWithoutAddingCommitments() {
        add("Essay", 2, PriorityLevel.MEDIUM); var failure = initialFailure();
        assertThrows(IllegalArgumentException.class, () -> new RecoveryRequest(failure.getPartialSchedule().getId(),
                RecoveryAction.ADD_AVAILABILITY, new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.WORK), null, null));
        assertThrows(IllegalArgumentException.class, () -> controller.applyRecovery(choice(failure, RecoveryAction.ADD_AVAILABILITY,
                new AvailabilityBlock(day.plusDays(2).atTime(10, 0), day.plusDays(2).atTime(11, 0), AvailabilityType.AVAILABLE), null, null)));
        assertEquals(1, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
    }

    @Test void deferredTaskCanUseLaterTimeWithoutBlockingEarlierWorkForOtherTasks() {
        Task deferred = add("Later", 1, PriorityLevel.HIGH); Task early = add("Earlier", 1, PriorityLevel.LOW);
        deferred.setDeferredUntil(day.atTime(10, 0));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(12, 0), AvailabilityType.AVAILABLE));
        var plan = controller.generateSchedule(day, day);
        assertEquals(day.atTime(9, 0), plan.getStudyBlocks().stream().filter(b -> b.getTask() == early).findFirst().orElseThrow().getStart());
        assertEquals(day.atTime(10, 0), plan.getStudyBlocks().stream().filter(b -> b.getTask() == deferred).findFirst().orElseThrow().getStart());
    }

    @Test void deferralSurvivesProgressJournalRestoration() {
        Path file = directory.resolve("progress.json");
        var store = new ProgressService(publisher, clock, file);
        var persistentController = new PlanPilotController(courses, tasks, availability, schedules, store);
        Task task = persistentController.addTask(new GeneralTask("Later", day.atTime(17, 0), 2, 3, PriorityLevel.LOW));
        persistentController.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(10, 0), AvailabilityType.AVAILABLE));
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> persistentController.generateSchedule(day, day));
        persistentController.applyRecovery(choice(failure, RecoveryAction.DEFER_LOWER_PRIORITY_TASK, null, null, task));
        var restored = new ProgressService(publisher, clock, file).getRestoredTasks().get(0);
        assertEquals(day.plusDays(1).atStartOfDay(), restored.getDeferredUntil());
        assertEquals(day.atTime(17, 0), restored.getDeadline()); assertEquals(2, restored.getRemainingHours());
    }

    @Test void linkedCoursesCannotBeDeletedEvenWhenTaskIsCompletedOrDeferred() {
        Course course = controller.addCourse(new Course("EECS3311", "Design"));
        Task task = controller.addTask(new AcademicTask("Report", day.atTime(17, 0), 1, 3,
                PriorityLevel.MEDIUM, course, 10, AcademicTaskType.ASSIGNMENT));
        assertThrows(IllegalStateException.class, () -> controller.deleteCourse(course.getId()));
        task.setDeferredUntil(day.plusDays(1).atStartOfDay());
        assertThrows(IllegalStateException.class, () -> controller.deleteCourse(course.getId()));
        task.markCompleted();
        assertThrows(IllegalStateException.class, () -> controller.deleteCourse(course.getId()));
        assertEquals(1, controller.getCourses().size()); assertEquals(1, controller.getTasks().size());
        controller.deleteTask(task.getId()); controller.deleteCourse(course.getId());
        assertTrue(controller.getTasks().isEmpty()); assertTrue(controller.getCourses().isEmpty());
    }

    @Test void reassigningTaskReleasesCourseAndRejectedDuplicateCourseEditIsAtomic() {
        Course first = controller.addCourse(new Course("EECS3311", "Design"));
        Course second = controller.addCourse(new Course("EECS4413", "Web"));
        var task = controller.addTask(new AcademicTask("Report", day.atTime(17, 0), 1, 3,
                PriorityLevel.MEDIUM, first, 10, AcademicTaskType.ASSIGNMENT));
        assertThrows(IllegalArgumentException.class, () -> controller.updateCourse(new Course(second.getId(), "eecs3311", "Duplicate")));
        assertEquals("EECS4413", second.getCode());
        var reassigned = new AcademicTask(task.getId(), task.getTitle(), task.getDeadline(), 1, 3,
                PriorityLevel.MEDIUM, second, 10, AcademicTaskType.ASSIGNMENT);
        controller.updateTask(reassigned); controller.deleteCourse(first.getId());
        assertEquals(List.of(second), controller.getCourses());
        assertSame(second, ((AcademicTask) controller.getTasks().get(0)).getCourse());
    }

    @Test void taskEditsPreserveProgressAndRejectAnEstimateBelowCompletedEffort() {
        Task task = add("Essay", 2, PriorityLevel.MEDIUM);
        var block = new StudyBlock("reported", task, day.atTime(9, 0), day.atTime(10, 0));
        controller.updateProgress(block, ProgressStatus.PARTIAL, .5);
        var invalid = new GeneralTask(task.getId(), "Invalid", task.getDeadline(), .25, 3, PriorityLevel.MEDIUM);
        assertThrows(IllegalArgumentException.class, () -> controller.updateTask(invalid));
        assertSame(task, controller.getTasks().get(0)); assertEquals(.5, task.getCompletedHours());
        task.setDeferredUntil(day.plusDays(1).atStartOfDay());
        var edited = new GeneralTask(task.getId(), "Updated", task.getDeadline(), 3, 3, PriorityLevel.HIGH);
        controller.updateTask(edited);
        assertEquals(.5, edited.getCompletedHours()); assertEquals(2.5, edited.getRemainingHours());
        assertEquals(task.getDeferredUntil(), edited.getDeferredUntil());
        assertEquals(1, controller.getProgressHistory(edited).size());
    }
    @Test void recoveryCannotAddAvailableTimeOverAFixedCommitment() {
        add("Essay", 2, PriorityLevel.MEDIUM);
        controller.addAvailability(new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.CLASS));
        var failure = initialFailure();
        assertThrows(IllegalArgumentException.class, () -> controller.applyRecovery(choice(failure,
                RecoveryAction.ADD_AVAILABILITY, new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.AVAILABLE), null, null)));
        assertSame(failure.getPartialSchedule(), schedules.getCurrentSchedule());
        assertEquals(ScheduleStatus.CURRENT, schedules.getCurrentSchedule().getStatus());
        assertEquals(2, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
    }

    @Test void aNewGenerationInvalidatesTheOldRecoveryRequest() {
        add("Essay", 2, PriorityLevel.MEDIUM); var first = initialFailure();
        var oldRequest = choice(first, RecoveryAction.ADD_AVAILABILITY,
                new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.AVAILABLE), null, null);
        var next = assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
        assertNotEquals(first.getPartialSchedule().getId(), next.getPartialSchedule().getId());
        assertThrows(IllegalStateException.class, () -> controller.applyRecovery(oldRequest));
        assertSame(next.getPartialSchedule(), schedules.getCurrentSchedule());
        assertEquals(1, availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
    }

}
