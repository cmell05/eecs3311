package com.planpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planpilot.ai.RecoveryAction;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePersistenceTest {
    @TempDir Path directory;
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private final Clock clock = Clock.fixed(day.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private PlanPilotController open(Path path) { return PlanPilotApplicationFactory.create(path, clock).controller(); }
    private Task task(PlanPilotController controller, String title, double hours) {
        return controller.addTask(new GeneralTask(title, day.plusDays(1).atTime(18, 0), hours, 3, PriorityLevel.MEDIUM));
    }
    private AvailabilityBlock available(int start, int end) {
        return new AvailabilityBlock(day.atTime(start, 0), day.atTime(end, 0), AvailabilityType.AVAILABLE);
    }

    @Test void standaloneCoursesAndTasksWithoutAnyScheduleSurviveRestart() {
        Path path = directory.resolve("standalone.json"); var first = open(path);
        var course = first.addCourse(new Course("EECS3311", "Design"));
        var task = task(first, "Reading", 2);
        var second = open(path);
        assertEquals(course.getId(), second.getCourses().get(0).getId());
        assertEquals(task.getId(), second.getTasks().get(0).getId());
        assertTrue(second.getCurrentSchedule().isEmpty());
        assertTrue(second.getProgressSessions().isEmpty());
    }

    @Test void windowPreferencesAvailabilityAndSessionIdentityAreRestoredWithoutRegeneration() {
        Path path = directory.resolve("plan.json"); var first = open(path);
        Course course = first.addCourse(new Course("EECS3311", "Design"));
        Task academic = first.addTask(new AcademicTask("Report", day.plusDays(1).atTime(17, 0), 2,
                3, PriorityLevel.HIGH, course, 20, AcademicTaskType.ASSIGNMENT));
        task(first, "Reading", 1);
        first.addAvailability(available(9, 15));
        first.addAvailability(new AvailabilityBlock(day.atTime(15, 0), day.atTime(16, 0), AvailabilityType.WORK));
        first.updateUserPreferences(new UserPreferences(45, StudyPeriod.AFTERNOON, 5));
        Schedule original = first.generateSchedule(day, day.plusDays(1));
        var second = open(path); Schedule restored = second.getCurrentSchedule().orElseThrow();
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getStartDate(), restored.getStartDate());
        assertEquals(original.getEndDate(), restored.getEndDate());
        assertEquals(ScheduleStatus.CURRENT, restored.getStatus());
        assertEquals(original.getStudyBlocks().stream().map(StudyBlock::getId).toList(),
                restored.getStudyBlocks().stream().map(StudyBlock::getId).toList());
        assertEquals(2, second.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
        assertEquals(45, second.getUserPreferences().getMaxSessionLength());
        assertEquals(5, second.getUserPreferences().getBreakLength());
        assertEquals(StudyPeriod.AFTERNOON, second.getUserPreferences().getPreferredStudyPeriod());
        AcademicTask restoredTask = (AcademicTask) second.getTasks().stream().filter(t -> t.getId().equals(academic.getId())).findFirst().orElseThrow();
        assertSame(second.getCourses().get(0), restoredTask.getCourse());
        restored.getStudyBlocks().forEach(block -> assertSame(block, second.getProgressSessions().stream()
                .filter(b -> b.getId().equals(block.getId())).findFirst().orElseThrow()));
    }

    @Test void editsDeletesAndCourseReassignmentStayDeletedOrUpdatedAfterRestart() {
        Path path = directory.resolve("edits.json"); var first = open(path);
        Course removed = first.addCourse(new Course("EECS3311", "Old"));
        Course kept = first.addCourse(new Course("EECS3342", "Methods"));
        Task academic = first.addTask(new AcademicTask("Report", day.atTime(17, 0), 2,
                3, PriorityLevel.HIGH, removed, 20, AcademicTaskType.PROJECT));
        first.addAvailability(available(9, 15)); first.addAvailability(available(16, 18));
        var entries = first.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        first.updateAvailability(new AvailabilityBlock(entries.get(0).getId(), day.atTime(9, 0), day.atTime(14, 0), AvailabilityType.AVAILABLE));
        first.deleteAvailability(entries.get(1).getId());
        first.updateTask(new AcademicTask(academic.getId(), "Updated", academic.getDeadline(), 2,
                3, PriorityLevel.HIGH, kept, 25, AcademicTaskType.PROJECT));
        first.deleteCourse(removed.getId());
        first.updateCourse(new Course(kept.getId(), "EECS3343", "Updated methods"));
        var second = open(path);
        assertEquals(1, second.getCourses().size());
        assertEquals("EECS3343", second.getCourses().get(0).getCode());
        assertEquals("Updated", second.getTasks().get(0).getTitle());
        assertSame(second.getCourses().get(0), ((AcademicTask) second.getTasks().get(0)).getCourse());
        var restoredEntries = second.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        assertEquals(1, restoredEntries.size()); assertEquals(day.atTime(14, 0), restoredEntries.get(0).getEnd());
        assertThrows(IllegalArgumentException.class, () -> second.addCourse(new Course("eecs3343", "Duplicate")));
    }

    @Test void correctedAndInferredProgressRemainCorrectableWithoutDoubleCountingAfterRestart() {
        Path path = directory.resolve("history.json"); var first = open(path);
        Task task = task(first, "Reading", 4); first.addAvailability(available(9, 17));
        first.generateSchedule(day, day);
        var block = first.getProgressSessions().get(0);
        first.updateProgress(block, ProgressStatus.PARTIAL, .5);
        first.inferProgress(new StudyBlock("past", task, day.atTime(6, 0), day.atTime(7, 0)));
        var second = open(path); Task restored = second.getTasks().get(0);
        assertEquals(.5, second.getConfirmedHours(restored)); assertEquals(1, second.getInferredHours(restored));
        assertEquals(2.5, restored.getRemainingHours());
        var partial = second.getProgressSessions().stream().filter(b -> b.getId().equals(block.getId())).findFirst().orElseThrow();
        second.updateProgress(partial, ProgressStatus.COMPLETED, 1);
        second.updateProgress(partial, ProgressStatus.COMPLETED, 1);
        var third = open(path); assertEquals(1, third.getConfirmedHours(third.getTasks().get(0)));
        assertEquals(1, third.getInferredHours(third.getTasks().get(0)));
        assertEquals(2, third.getTasks().get(0).getRemainingHours());
    }

    @Test void deletedTasksAndTheirStandaloneCoursesAreNotResurrectedFromArchivedHistory() {
        Path path = directory.resolve("archive.json"); var first = open(path);
        var course = first.addCourse(new Course("EECS3311", "Design"));
        Task task = first.addTask(new AcademicTask("Report", day.atTime(17, 0), 2, 3,
                PriorityLevel.HIGH, course, 20, AcademicTaskType.PROJECT));
        first.updateProgress(new StudyBlock("old", task, day.atTime(6, 0), day.atTime(7, 0)), ProgressStatus.COMPLETED, 1);
        first.deleteTask(task.getId()); first.deleteCourse(course.getId());
        var second = open(path);
        assertTrue(second.getCourses().isEmpty()); assertTrue(second.getTasks().isEmpty());
        assertEquals(1, second.getProgressSessions().size());
        assertEquals(1, second.getProgressHistory(second.getProgressSessions().get(0).getTask()).size());
        second.addCourse(new Course("EECS3311", "New course with reused code"));
        var third = open(path); assertEquals(1, third.getCourses().size()); assertTrue(third.getTasks().isEmpty());
    }

    @Test void infeasiblePlanRestoresWarningsAndSupportsExplicitRecoveryAfterRestart() {
        Path path = directory.resolve("partial.json"); var first = open(path); task(first, "Essay", 2);
        first.addAvailability(available(9, 10));
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> first.generateSchedule(day, day));
        var second = open(path); var restoredFailure = second.getCurrentInfeasibility().orElseThrow();
        assertEquals(failure.getPartialSchedule().getId(), restoredFailure.getPartialSchedule().getId());
        assertEquals(failure.getWorkloadDeficitHours(), restoredFailure.getWorkloadDeficitHours());
        assertEquals(failure.getMessage(), restoredFailure.getMessage());
        var recovered = second.applyRecovery(new RecoveryRequest(restoredFailure.getPartialSchedule().getId(),
                RecoveryAction.ADD_AVAILABILITY, available(10, 13), null, null));
        assertFalse(recovered.getStudyBlocks().isEmpty()); assertTrue(second.getCurrentInfeasibility().isEmpty());
        var third = open(path); assertEquals(recovered.getId(), third.getCurrentSchedule().orElseThrow().getId());
        assertTrue(third.getCurrentInfeasibility().isEmpty());
    }

    @Test void oldVersionOneJournalLoadsUnchangedAndUpgradesOnTheNextMutation() throws Exception {
        Path path = directory.resolve("legacy.json"); var progress = new ProgressService(new PlanningEventPublisher(), clock, path);
        Course course = new Course("C", "EECS3311", "Design");
        Task task = new AcademicTask("T", "Report", day.atTime(17, 0), 2, 3, PriorityLevel.HIGH, course, 20, AcademicTaskType.PROJECT);
        progress.recordProgress(new StudyBlock("B", task, day.atTime(6, 0), day.atTime(7, 0)), ProgressStatus.PARTIAL, .5);
        ObjectMapper json = new ObjectMapper(); ObjectNode legacy = (ObjectNode) json.readTree(path.toFile());
        legacy.put("version", 1); legacy.remove("workspace"); json.writeValue(path.toFile(), legacy);
        byte[] before = Files.readAllBytes(path); var restored = open(path);
        assertArrayEquals(before, Files.readAllBytes(path), "Loading must not rewrite the original journal");
        assertEquals(.5, restored.getConfirmedHours(restored.getTasks().get(0)));
        assertEquals("EECS3311", restored.getCourses().get(0).getCode());
        restored.addAvailability(available(9, 13));
        assertEquals(2, json.readTree(path.toFile()).path("version").asInt());
        var restarted = open(path); assertEquals(.5, restarted.getConfirmedHours(restarted.getTasks().get(0)));
        assertEquals(1, restarted.getProgressHistory(restarted.getTasks().get(0)).size());
    }

    @Test void malformedOrUnknownSaveFilesAreNotSilentlyReset() throws Exception {
        for (String content : List.of("{broken", "{\"version\":99}", "{\"version\":2}")) {
            Path path = directory.resolve(java.util.UUID.randomUUID() + ".json"); Files.writeString(path, content);
            assertThrows(IllegalStateException.class, () -> open(path));
            assertEquals(content, Files.readString(path));
        }
    }

    @Test void preferenceOnlyWorkspaceAndDeletionsPersistWithoutTasksOrSessions() {
        Path path = directory.resolve("preferences.json"); var first = open(path);
        first.updateUserPreferences(new UserPreferences(30, StudyPeriod.MORNING, 0));
        var course = first.addCourse(new Course("EECS3311", "Design")); first.deleteCourse(course.getId());
        var second = open(path); assertTrue(second.getCourses().isEmpty());
        assertEquals(30, second.getUserPreferences().getMaxSessionLength());
        assertEquals(StudyPeriod.MORNING, second.getUserPreferences().getPreferredStudyPeriod());
        assertTrue(second.getCurrentSchedule().isEmpty());
    }
}
