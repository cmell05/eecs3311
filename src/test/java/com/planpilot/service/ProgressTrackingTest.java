package com.planpilot.service;

import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProgressTrackingTest {
    @TempDir Path directory;
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 4, 9, 0);
    private Clock at(LocalDateTime time) { return Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
    private Task task() { return new GeneralTask("T", "Design", start.plusDays(2), 4, 3, PriorityLevel.MEDIUM); }
    private StudyBlock block(String id, Task task, int offset) {
        return new StudyBlock(id, task, start.plusHours(offset), start.plusHours(offset + 1));
    }

    @Test void infersOnlyElapsedSessionsAndNeverOverridesUserCorrections() {
        var service = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), null);
        var task = task(); var past = block("past", task, 0); var future = block("future", task, 2);
        service.trackSessions(List.of(past, future));
        assertTrue(service.inferPastSessions());
        assertTrue(past.isInferred()); assertEquals(ProgressStatus.PLANNED, future.getProgressStatus());
        assertThrows(IllegalArgumentException.class, () -> service.inferProgress(future));
        assertEquals(1, task.getCompletedHours()); assertEquals(3, task.getRemainingHours());
        assertEquals(0, service.getConfirmedHours(task)); assertEquals(1, service.getInferredHours(task));
        assertFalse(service.inferPastSessions()); assertEquals(1, service.getHistory().size());
        service.recordProgress(past, ProgressStatus.PARTIAL, .25);
        assertFalse(past.isInferred()); assertFalse(service.inferPastSessions());
        assertEquals(.25, service.getConfirmedHours(task)); assertEquals(0, service.getInferredHours(task));
        assertEquals(3.75, task.getRemainingHours());
    }

    @Test void repeatedAndCorrectedResultsReplaceRatherThanAccumulate() {
        var service = new ProgressService(); var task = task(); var block = block("B", task, 0);
        service.recordProgress(block, ProgressStatus.COMPLETED, 1);
        service.recordProgress(block, ProgressStatus.COMPLETED, 1);
        assertEquals(1, task.getCompletedHours()); assertEquals(1, service.getHistory().size());
        service.recordProgress(block, ProgressStatus.PARTIAL, .5);
        assertEquals(.5, task.getCompletedHours());
        service.recordProgress(block, ProgressStatus.SKIPPED, 0);
        assertEquals(0, task.getCompletedHours()); assertEquals(4, task.getRemainingHours());
        service.recordProgress(block, ProgressStatus.PARTIAL, 0);
        service.recordProgress(block, ProgressStatus.PARTIAL, 1);
        assertEquals(1, task.getCompletedHours());
        assertThrows(IllegalArgumentException.class, () -> service.recordProgress(block, ProgressStatus.PARTIAL, 1.01));
    }

    @Test void stableSessionAndTaskIdsSurviveObjectReplacement() {
        var service = new ProgressService(); var first = task();
        service.recordProgress(block("B", first, 0), ProgressStatus.COMPLETED, 1);
        var edited = task(); service.registerTasks(List.of(edited));
        service.recordProgress(block("B", edited, 0), ProgressStatus.PARTIAL, .4);
        assertEquals(.4, edited.getCompletedHours());
        assertEquals(2, service.getHistoryForTask(edited).size());
        assertEquals(.4, service.getConfirmedHours(edited));
    }

    @Test void journalRestoresAcademicTaskSessionsInferenceCorrectionsAndOriginalEstimate() {
        Path file = directory.resolve("progress.json");
        var first = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), file);
        var course = new Course("C", "EECS3311", "Design");
        var task = new AcademicTask("T", "Assignment", start.plusDays(1), 4, 3,
                PriorityLevel.HIGH, course, 10, AcademicTaskType.ASSIGNMENT);
        first.registerTasks(List.of(task)); task.setEstimatedHours(5);
        first.trackSessions(List.of(block("past", task, 0), block("future", task, 3)));
        first.inferPastSessions();
        var restored = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), file);
        Task loaded = restored.getRestoredTasks().get(0);
        assertInstanceOf(AcademicTask.class, loaded);
        assertEquals("EECS3311", ((AcademicTask) loaded).getCourse().getCode());
        assertEquals(1, loaded.getCompletedHours()); assertEquals(0, restored.getConfirmedHours(loaded));
        assertEquals(4, restored.getOriginalEstimate(loaded)); assertEquals(5, loaded.getEstimatedHours());
        assertEquals(2, restored.getSessions().size()); assertTrue(restored.getSessions().get(0).isInferred());
        restored.recordProgress(restored.getSessions().get(0), ProgressStatus.SKIPPED, 0);
        var reloaded = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), file);
        assertEquals(0, reloaded.getRestoredTasks().get(0).getCompletedHours());
        assertEquals(2, reloaded.getHistory().size()); assertFalse(reloaded.inferPastSessions());
        assertEquals(ProgressStatus.SKIPPED, reloaded.getSessions().get(0).getProgressStatus());
        var later = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(5)), file);
        assertTrue(later.inferPastSessions());
        assertEquals(1, later.getRestoredTasks().get(0).getCompletedHours());
        assertEquals(0, later.getConfirmedHours(later.getRestoredTasks().get(0)));
    }

    @Test void replacingFuturePlanRetainsReportedSessionsAndTheirCorrections() {
        var service = new ProgressService(); var task = task();
        var completed = block("done", task, 0); var oldFuture = block("old", task, 3);
        service.trackSessions(List.of(completed, oldFuture));
        service.recordProgress(completed, ProgressStatus.COMPLETED, 1);
        service.trackSessions(List.of(block("new", task, 4)));
        assertEquals(List.of("done", "new"), service.getSessions().stream().map(StudyBlock::getId).toList());
        service.recordProgress(completed, ProgressStatus.SKIPPED, 0);
        assertEquals(0, task.getCompletedHours());
    }
    @Test void deletedTasksStayArchivedAfterRestartWhileHistoryRemains() {
        Path file = directory.resolve("archived.json");
        var service = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), file);
        var task = task(); service.recordProgress(block("B", task, 0), ProgressStatus.COMPLETED, 1);
        service.archiveTask(task.getId());
        var restored = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), file);
        assertTrue(restored.getRestoredTasks().isEmpty());
        assertEquals(1, restored.getHistory().size()); assertEquals(1, restored.getSessions().size());
    }

    @Test void inferenceMarksOutdatedWithoutReplanningAndCorrectionsSurviveReplan() {
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService(); var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, at(start.plusHours(3)), null);
        var schedules = new ScheduleService(tasks, availability, new SchedulePlanner(t -> 1, new com.planpilot.service.ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)),
                new UserPreferences(60, StudyPeriod.ANY, 0), publisher);
        var controller = new com.planpilot.controller.PlanPilotController(courses, tasks, availability, schedules, progress);
        var task = controller.addTask(task());
        controller.addAvailability(new AvailabilityBlock(start, start.plusHours(5), AvailabilityType.AVAILABLE));
        var original = controller.generateSchedule(start.toLocalDate(), start.toLocalDate());
        controller.refreshProgress();
        assertSame(original, schedules.getCurrentSchedule());
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertEquals(3, task.getCompletedHours()); assertEquals(0, controller.getConfirmedHours(task));
        var inferredSession = original.getStudyBlocks().get(0);
        controller.updateProgress(inferredSession, ProgressStatus.PARTIAL, .5);
        assertEquals(2.5, task.getCompletedHours());
        var revised = controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        assertTrue(revised.getStudyBlocks().stream().anyMatch(b -> b == inferredSession));
        controller.updateProgress(inferredSession, ProgressStatus.SKIPPED, 0);
        assertEquals(2, task.getCompletedHours());
        assertEquals(2, task.getRemainingHours());
    }

    @Test void applicationFactoryRestoresProgressIntoTheRealController() {
        Path file = directory.resolve("application.json");
        var service = new ProgressService(new PlanningEventPublisher(), at(start.plusHours(1)), file);
        Task task = task(); service.recordProgress(block("B", task, 0), ProgressStatus.PARTIAL, .5);
        String prior = System.getProperty("planpilot.progress.file");
        try {
            System.setProperty("planpilot.progress.file", file.toString());
            var controller = com.planpilot.config.PlanPilotApplicationFactory.createDefault().controller();
            assertEquals(1, controller.getTasks().size());
            assertEquals(.5, controller.getConfirmedHours(controller.getTasks().get(0)));
            assertEquals(1, controller.getProgressSessions().size());
            controller.updateProgress(controller.getProgressSessions().get(0), ProgressStatus.SKIPPED, 0);
            var restarted = com.planpilot.config.PlanPilotApplicationFactory.createDefault().controller();
            assertEquals(0, restarted.getTasks().get(0).getCompletedHours());
            assertEquals(2, restarted.getProgressHistory(restarted.getTasks().get(0)).size());
        } finally {
            if (prior == null) System.clearProperty("planpilot.progress.file");
            else System.setProperty("planpilot.progress.file", prior);
        }
    }

}
