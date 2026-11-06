package com.planpilot.cli;

import com.planpilot.ai.PlanningAgent;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Scanner;
import static org.junit.jupiter.api.Assertions.*;

class PlanPilotCLIManagementTest {
    private final LocalDate day = LocalDate.of(2026, 11, 5);
    private final Clock clock = Clock.fixed(day.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private PlanPilotController controller() {
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService(); var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, clock, null);
        var schedules = new ScheduleService(tasks, availability,
                new SchedulePlanner(t -> t.getUserImportance() == PriorityLevel.HIGH ? 100 : 10, new ConflictDetector(), clock),
                new UserPreferences(60, StudyPeriod.ANY, 0), publisher);
        var agent = new PlanningAgent((system, prompt) ->
                "{\"summary\":\"Add time or defer work.\",\"suggestions\":[{\"action\":\"ADD_AVAILABILITY\",\"explanation\":\"Another hour is required.\"}]}");
        return new PlanPilotController(courses, tasks, availability, schedules, progress, null, agent);
    }
    private String run(PlanPilotController controller, String... lines) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new PlanPilotCLI(controller, new Scanner(String.join("\n", lines) + "\n"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run();
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private Task task(PlanPilotController controller, String title, double hours) {
        return controller.addTask(new GeneralTask(title, day.atTime(17, 0), hours, 3, PriorityLevel.MEDIUM));
    }
    private void availability(PlanPilotController controller) {
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(10, 0), AvailabilityType.AVAILABLE));
    }

    @Test void linkedCourseDeletionIsBlockedThenSucceedsAfterTaskDeletion() {
        var controller = controller();
        var course = controller.addCourse(new Course("EECS3311", "Design"));
        controller.addTask(new AcademicTask("Report", day.atTime(17, 0), 1, 3, PriorityLevel.MEDIUM,
                course, 10, AcademicTaskType.ASSIGNMENT));
        String output = run(controller,
                "14", "2", "1", "yes",
                "14", "1", "1", "EECS3312", "Design updated",
                "14", "4", "1", "yes",
                "14", "2", "1", "yes", "0");
        assertTrue(output.contains("Cannot delete a course while academic tasks reference it"));
        assertTrue(output.contains("Course updated.")); assertTrue(output.contains("Task deleted."));
        assertTrue(output.contains("Course deleted."));
        assertTrue(controller.getTasks().isEmpty()); assertTrue(controller.getCourses().isEmpty());
    }

    @Test void taskAndAvailabilityEditsUseTheControllerAndPreserveProgress() {
        var controller = controller(); var task = task(controller, "Before", 2);
        controller.updateProgress(new StudyBlock("B", task, day.atTime(9, 0), day.atTime(10, 0)), ProgressStatus.PARTIAL, .5);
        availability(controller);
        String output = run(controller,
                "14", "3", "1", "Updated", "2026-11-06T17:00", "3", "4", "HIGH",
                "14", "6", "1", "", "2026-11-05T12:00", "AVAILABLE",
                "14", "5", "14", "7", "1", "yes", "0");
        Task updated = controller.getTasks().get(0);
        assertEquals("Updated", updated.getTitle()); assertEquals(3, updated.getEstimatedHours());
        assertEquals(.5, updated.getCompletedHours()); assertEquals(2.5, updated.getRemainingHours());
        assertTrue(output.contains("Task updated.")); assertTrue(output.contains("Availability updated."));
        assertTrue(output.contains("Availability deleted."));
        assertTrue(controller.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).isEmpty());
    }

    @Test void recoveryAddsTimeAndReplansOnlyAfterConfirmation() {
        var controller = controller(); task(controller, "Essay", 2); availability(controller);
        String output = run(controller, "6", "2026-11-05", "2026-11-05",
                "1", "2026-11-05T10:00", "2026-11-05T11:00", "yes", "0");
        assertTrue(output.contains("Recovery applied and schedule regenerated."));
        assertEquals(2, controller.getProgressSessions().stream().mapToDouble(StudyBlock::durationHours).sum());
        assertEquals(0, controller.getTasks().get(0).getCompletedHours());
    }

    @Test void cancelledRecoveryDoesNotAddTimeOrRegenerate() {
        var controller = controller(); task(controller, "Essay", 2); availability(controller);
        String output = run(controller, "6", "2026-11-05", "2026-11-05",
                "1", "2026-11-05T10:00", "2026-11-05T11:00", "no", "0");
        assertTrue(output.contains("Recovery cancelled; nothing changed."));
        assertEquals(1, controller.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size());
        assertEquals(1, controller.getProgressSessions().size());
    }

    @Test void aDeferredTaskCanBeIncludedAgainThroughManagement() {
        var controller = controller(); task(controller, "Urgent", 1); var low = task(controller, "Reading", 1);
        availability(controller);
        String output = run(controller, "6", "2026-11-05", "2026-11-05", "3", "2", "yes",
                "14", "8", "2", "yes", "0");
        assertTrue(output.contains("Recovery applied and schedule regenerated."));
        assertTrue(output.contains("Task included again. Replan when ready."));
        assertNull(low.getDeferredUntil()); assertEquals(1, low.getRemainingHours());
        assertEquals(2, controller.getTasks().size());
    }
}
