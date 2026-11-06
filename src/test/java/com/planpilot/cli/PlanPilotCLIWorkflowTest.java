package com.planpilot.cli;

import com.planpilot.ai.LLMClient;
import com.planpilot.ai.PlanningAgent;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.*;

class PlanPilotCLIWorkflowTest {
    @TempDir Path directory;
    private final LocalDate day = LocalDate.of(2026, 11, 5);
    private final Clock clock = Clock.fixed(day.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private PlanPilotController controller(Path journal, LLMClient client) {
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService();
        var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, clock, journal);
        for (Task task : progress.getRestoredTasks()) {
            if (task instanceof AcademicTask academic) courses.addCourse(academic.getCourse());
            tasks.createTask(task);
        }
        var schedules = new ScheduleService(tasks, availability,
                new SchedulePlanner(task -> task.getUserImportance().ordinal(), new ConflictDetector(), clock),
                new UserPreferences(60, StudyPeriod.ANY, 0), publisher);
        var agent = client == null ? null : new PlanningAgent(client);
        return new PlanPilotController(courses, tasks, availability, schedules, progress,
                agent == null ? null : new EffortEstimator(agent), agent);
    }

    private String run(PlanPilotController controller, String... lines) {
        var bytes = new ByteArrayOutputStream();
        new PlanPilotCLI(controller, new Scanner(String.join("\n", lines) + "\n"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private Task addTask(PlanPilotController controller, double hours) {
        return controller.addTask(new GeneralTask("Reading", day.plusDays(1).atTime(17, 0), hours, 3, PriorityLevel.MEDIUM));
    }

    private Schedule generate(PlanPilotController controller) {
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE));
        return controller.generateSchedule(day, day);
    }

    @Test void menuIsPrintedBeforeWaitingForTheFirstInput() {
        var bytes = new ByteArrayOutputStream();
        Readable terminal = buffer -> {
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Choose an option:"));
            buffer.put("0\n");
            return 2;
        };
        new PlanPilotCLI(controller(null, null), new Scanner(terminal), new PrintStream(bytes)).run();
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Goodbye."));
    }

    @Test void completeDeterministicWorkflowCreatesBothTaskKindsAndReplansPartialWork() {
        var controller = controller(null, (system, prompt) -> { throw new AssertionError("Deterministic workflow called AI"); });
        String output = run(controller,
                "2", "EECS3311", "Software Design",
                "4", "academic", "Design report", "2026-11-06T17:00", "2", "3", "HIGH", "1", "20", "ASSIGNMENT",
                "4", "general", "Reading", "2026-11-06T17:00", "1", "2", "LOW",
                "5", "2026-11-05T09:00", "2026-11-05T17:00", "AVAILABLE",
                "6", "2026-11-05", "2026-11-05",
                "7", "8", "1", "PARTIAL", "0.5", "16", "yes", "9", "0");
        assertFalse(output.contains("Error:"), output);
        assertEquals(1, controller.getCourses().size());
        assertEquals(2, controller.getTasks().size());
        Task academic = controller.getTasks().get(0);
        assertInstanceOf(AcademicTask.class, academic);
        assertEquals(.5, controller.getConfirmedHours(academic));
        assertEquals(1.5, academic.getRemainingHours());
        var schedule = controller.getCurrentSchedule().orElseThrow();
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus());
        assertEquals(2.5, schedule.getStudyBlocks().stream().filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .mapToDouble(StudyBlock::durationHours).sum());
        assertEquals(1, schedule.getStudyBlocks().stream().filter(b -> b.getProgressStatus() == ProgressStatus.PARTIAL).count());
        assertTrue(output.contains("EECS3311: Design report"));
        assertTrue(output.contains("Remaining work replanned."));
        assertTrue(output.contains("Recorded sessions (history, not new allocations)"));
    }

    @Test void invalidFieldsAreRetriedWithoutDiscardingTheForm() {
        var controller = controller(null, null);
        String output = run(controller,
                "4", "wrong", "general", "", "Reading", "bad date", "2026-11-06T17:00",
                "NaN", "-1", "2", "9", "3", "unknown", "MEDIUM",
                "5", "bad", "2026-11-05T09:00", "2026-11-05T08:00", "2026-11-05T12:00", "AVAILABLE", "0");
        assertEquals(1, controller.getTasks().size());
        assertEquals(2, controller.getTasks().get(0).getEstimatedHours());
        assertEquals(1, controller.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
        assertTrue(output.contains("Try again, or type /cancel."));
        assertTrue(output.contains("End must be after start."));
    }

    @Test void cancellationAndEndOfInputNeverSaveAnIncompleteTask() {
        var controller = controller(null, null);
        String output = run(controller, "4", "general", "Reading", "/cancel", "0");
        assertTrue(output.contains("Action cancelled."));
        assertTrue(controller.getTasks().isEmpty());
        output = run(controller, "4", "general", "Reading");
        assertFalse(output.contains("Error:"));
        assertTrue(output.contains("Goodbye."));
        assertTrue(controller.getTasks().isEmpty());
    }

    @Test void partialValidationCorrectionsAndRepeatedConfirmationDoNotDoubleCount() {
        var controller = controller(null, null);
        Task task = addTask(controller, 2);
        var original = generate(controller);
        String output = run(controller,
                "8", "999", "1", "PLANNED", "PARTIAL", "NaN", "-1", "1.5", "0.5",
                "8", "1", "COMPLETED", "8", "1", "COMPLETED", "9", "0");
        assertEquals(1, controller.getConfirmedHours(task));
        assertEquals(1, task.getRemainingHours());
        assertEquals(2, controller.getProgressHistory(task).size());
        assertSame(original, controller.getCurrentSchedule().orElseThrow());
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertTrue(output.contains("Corrections replace this session"));
    }

    @Test void savedInferredSessionCanBeCorrectedWithoutGeneratingANewSchedule() {
        Path journal = directory.resolve("progress.json");
        var first = controller(journal, null);
        Task task = addTask(first, 2);
        var past = new StudyBlock("past", task, day.atTime(8, 0), day.atTime(9, 0));
        first.inferProgress(past);
        var restored = controller(journal, null);
        assertTrue(restored.getCurrentSchedule().isEmpty());
        String output = run(restored, "9", "8", "1", "SKIPPED", "9", "0");
        assertTrue(output.contains("INFERRED — awaiting confirmation"));
        assertEquals(0, restored.getConfirmedHours(restored.getTasks().get(0)));
        assertEquals(0, restored.getInferredHours(restored.getTasks().get(0)));
        assertEquals(2, restored.getTasks().get(0).getRemainingHours());
        var reloaded = controller(journal, null);
        assertEquals(ProgressStatus.SKIPPED, reloaded.getProgressSessions().get(0).getProgressStatus());
        assertFalse(reloaded.getProgressSessions().get(0).isInferred());
    }

    @Test void preferencesMarkThePlanOutdatedButDoNotAutomaticallyReplan() {
        var controller = controller(null, null); addTask(controller, 2);
        var original = generate(controller);
        String output = run(controller, "17", "yes", "0", "30", "-5", "5", "ANY", "yes", "0");
        assertEquals(30, controller.getUserPreferences().getMaxSessionLength());
        assertEquals(5, controller.getUserPreferences().getBreakLength());
        assertSame(original, controller.getCurrentSchedule().orElseThrow());
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertTrue(output.contains("no schedule was regenerated"));
    }

    @Test void infeasibilityDoesNotCallAIUnlessExplicitlyRequested() {
        var controller = controller(null, (system, prompt) -> { throw new AssertionError("AI should be opt-in"); });
        addTask(controller, 2);
        String output = run(controller, "6", "2026-11-05", "2026-11-05", "0", "0");
        assertTrue(output.contains("No available study time"), output);
        assertTrue(output.contains("Current constraints and partial plan kept."));
        assertTrue(controller.getCurrentSchedule().orElseThrow().getStudyBlocks().isEmpty());
    }

    @Test void editingAIProposalRequiresASecondExplicitAcceptance() {
        var controller = controller(null, (system, prompt) -> """
                {"title":"Reading","deadline":"2026-11-06T17:00","estimatedHours":2,
                "difficulty":3,"importance":"MEDIUM","taskKind":"GENERAL"}
                """);
        String output = run(controller, "10", "Read tomorrow", "edit", "Edited title", "", "", "", "", "", "cancel", "0");
        assertTrue(controller.getTasks().isEmpty());
        assertTrue(output.contains("Edited title"));
        assertTrue(output.contains("nothing was saved"));
    }

    @Test void taskListsUseCurrentControllerDataAfterEditsAndDeletion() {
        var controller = controller(null, null); addTask(controller, 2);
        String output = run(controller, "3", "14", "3", "1", "Edited", "", "", "", "",
                "3", "14", "4", "1", "yes", "3", "0");
        assertTrue(output.contains("Edited\n  Status:"));
        assertTrue(output.contains("No tasks have been added."));
        assertTrue(controller.getTasks().isEmpty());
    }
    @Test void effortChangesRejectValuesBelowRecordedWorkWithoutMutatingTheTask() {
        var controller = controller(null, (system, prompt) -> "{\"estimatedHours\":3}");
        Task task = addTask(controller, 2);
        var block = generate(controller).getStudyBlocks().get(0);
        controller.updateProgress(block, ProgressStatus.COMPLETED, 1);
        String output = run(controller, "11", "1", "0.5", "/cancel", "0");
        assertEquals(2, controller.getTasks().get(0).getEstimatedHours());
        assertEquals(1, controller.getConfirmedHours(task));
        assertTrue(output.contains("Hours must be at least 1.0"));
        assertTrue(output.contains("Action cancelled."));
    }

    @Test void unmatchedAICourseIsMappedToAnExistingCourseByTheUser() {
        var controller = controller(null, (system, prompt) -> """
                {"title":"Report","deadline":"2026-11-06T17:00","estimatedHours":2,
                "difficulty":3,"importance":"MEDIUM","taskKind":"ACADEMIC",
                "courseCode":"UNKNOWN","assessmentWeight":20,"academicTaskType":"ASSIGNMENT"}
                """);
        Course course = controller.addCourse(new Course("EECS3311", "Design"));
        String output = run(controller, "10", "Report for design", "accept", "1", "0");
        assertEquals(1, controller.getTasks().size());
        assertSame(course, ((AcademicTask) controller.getTasks().get(0)).getCourse());
        assertEquals(1, controller.getCourses().size());
        assertTrue(output.contains("Choose an existing course"));
    }

}
