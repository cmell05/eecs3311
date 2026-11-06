package com.planpilot.cli;

import com.planpilot.ai.LLMClient;
import com.planpilot.ai.PlanningAgent;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.ScheduleStatus;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.UserPreferences;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.EffortEstimator;
import com.planpilot.service.ProgressService;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.TaskService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPilotCLITest {
    @Test
    void addsAndListsCourseThroughMenu() {
        String input = String.join("\n",
                "2", "EECS3311", "Software Design",
                "1", "0") + "\n";

        String output = runCli(input);

        assertTrue(output.contains("Course added."));
        assertTrue(output.contains("EECS3311 — Software Design"));
    }

    @Test
    void unknownMenuChoiceDoesNotCrashApplication() {
        String output = assertDoesNotThrow(() -> runCli("unknown\n0\n"));

        assertTrue(output.contains("Unknown option"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void invalidDateCanBeCancelledWithoutCrashing() {
        String input = String.join("\n",
                "5", "not-a-date", "/cancel", "0") + "\n";

        String output = assertDoesNotThrow(() -> runCli(input));

        assertTrue(output.contains("Error:"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void confirmsNaturalLanguageProposalBeforeSaving() {
        PlanPilotController controller = aiController("""
                {"title":"Prepare portfolio",
                "deadline":"2026-11-10T17:00:00","estimatedHours":3,
                "difficulty":3,"importance":"MEDIUM",
                "taskKind":"GENERAL"}
                """);
        String input = String.join("\n",
                "10", "Prepare my portfolio by next Tuesday",
                "accept", "3", "0") + "\n";

        String output = runCli(input, controller);

        assertTrue(output.contains("AI task proposal (not yet saved)"));
        assertTrue(output.contains("Proposed task confirmed and added."));
        assertTrue(output.contains("Prepare portfolio"));
        assertEquals(1, controller.getTasks().size());
    }

    @Test
    void cancellingNaturalLanguageProposalSavesNothing() {
        PlanPilotController controller = aiController("""
                {"title":"Prepare portfolio",
                "deadline":"2026-11-10T17:00:00","estimatedHours":3}
                """);

        String output = runCli(String.join("\n",
                "10", "Prepare my portfolio", "cancel", "3", "0") + "\n",
                controller);

        assertTrue(output.contains("nothing was saved"));
        assertTrue(output.contains("No tasks have been added."));
        assertEquals(0, controller.getTasks().size());
    }

    @Test
    void appliesEffortSuggestionOnlyAfterUserEntersEstimate() {
        PlanPilotController controller = aiController(
                "{\"estimatedHours\":3.5}");
        controller.addTask(generalTask("T1", 2));

        String output = runCli(String.join("\n",
                "11", "1", "3.25", "0") + "\n", controller);

        assertTrue(output.contains("AI suggested estimate: 3.50 hours"));
        assertTrue(output.contains("updated by user confirmation"));
        assertEquals(3.25, controller.getTasks().get(0).getEstimatedHours(),
                0.0001);
    }

    @Test
    void displaysRecoveryAdviceForInfeasibleSchedule() {
        PlanPilotController controller = aiController("""
                {"summary":"One additional hour is required.",
                "suggestions":[{"action":"EXTEND_PLANNING_WINDOW",
                "explanation":"Add another planning day."}]}
                """);
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 9, 0);
        controller.addTask(generalTask("T1", 2));
        controller.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(1),
                AvailabilityType.AVAILABLE));

        String output = runCli(String.join("\n",
                "6", "2026-11-03", "2026-11-03", "4", "0", "0") + "\n",
                controller);

        assertTrue(output.contains("1.00 hours could not be scheduled"));
        assertTrue(output.contains(
                "AI recovery summary: One additional hour is required."));
        assertTrue(output.contains("No recovery action was applied automatically."));
    }

    @Test
    void displaysExplanationAndDoesNotAutoApplyReplanAdvice() {
        PlanPilotController controller = aiController(
                "The task uses the first available morning slot.",
                """
                        {"impactSummary":"A new task is not in the plan.",
                        "recommendedOption":"REPLAN_ENTIRE_WINDOW",
                        "rationale":"Include all open work."}
                        """);
        LocalDate date = LocalDate.of(2026, 11, 3);
        LocalDateTime start = date.atTime(9, 0);
        controller.addTask(generalTask("T1", 1));
        controller.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(3),
                AvailabilityType.AVAILABLE));
        Schedule original = controller.generateSchedule(date, date);
        controller.addTask(generalTask("T2", 1));

        String output = runCli(String.join("\n",
                "12", "Why is T1 first?",
                "13", "no", "0") + "\n", controller);

        assertTrue(output.contains("AI explanation: The task uses"));
        assertTrue(output.contains("Recommended option: REPLAN_ENTIRE_WINDOW"));
        assertTrue(output.contains("Recommendation not applied."));
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertEquals(1, original.getStudyBlocks().size());
    }

    private String runCli(String input) {
        return runCli(input, PlanPilotCLI.createDefaultController());
    }

    private String runCli(String input, PlanPilotController controller) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PlanPilotCLI cli = new PlanPilotCLI(
                controller, new Scanner(input), output);

        cli.run();

        return bytes.toString(StandardCharsets.UTF_8);
    }

    private PlanPilotController aiController(String... responses) {
        Queue<String> queuedResponses = new ArrayDeque<>(
                Arrays.asList(responses));
        LLMClient fakeClient = (systemInstruction, userPrompt) -> {
            if (queuedResponses.isEmpty()) {
                throw new AssertionError("Unexpected LLM request.");
            }
            return queuedResponses.remove();
        };
        PlanningAgent planningAgent = new PlanningAgent(fakeClient);
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService,
                new SchedulePlanner(task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)),
                new UserPreferences(60, StudyPeriod.ANY, 0));
        return new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService,
                new EffortEstimator(planningAgent), planningAgent);
    }

    private GeneralTask generalTask(String id, double hours) {
        return new GeneralTask(
                id, "Task " + id,
                LocalDateTime.of(2026, 11, 4, 17, 0),
                hours, 3, PriorityLevel.MEDIUM);
    }
}
