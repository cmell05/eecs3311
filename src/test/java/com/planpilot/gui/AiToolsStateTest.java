package com.planpilot.gui;

import com.planpilot.ai.PlanningAgent;
import com.planpilot.ai.ReplanRecommendation;
import com.planpilot.config.PlanPilotApplicationFactory;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import javafx.beans.property.SimpleStringProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class AiToolsStateTest {
    @TempDir Path directory;
    private final LocalDate day = LocalDate.of(2026, 11, 10);
    private final Clock clock = Clock.fixed(day.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final PlanningEventPublisher publisher = new PlanningEventPublisher();
    private final CourseService courses = new CourseService();
    private final TaskService tasks = new TaskService(courses, publisher);
    private final AvailabilityService availability = new AvailabilityService();
    private final ProgressService progress = new ProgressService(publisher, clock, null);
    private final ScheduleService schedules = new ScheduleService(tasks, availability,
            new SchedulePlanner(task -> 1, new ConflictDetector(), clock), new UserPreferences(60, StudyPeriod.ANY, 0), publisher);
    private final PlanningAgent agent = new PlanningAgent((system, prompt) -> system.startsWith("Explain the scheduling")
            ? "The task uses the available study time."
            : "{\"impactSummary\":\"Review remaining work.\",\"recommendedOption\":\"RESCHEDULE_INCOMPLETE_WORK\",\"rationale\":\"Workload changed.\"}");
    private final PlanPilotController controller = new PlanPilotController(courses, tasks, availability, schedules, progress,
            new EffortEstimator(agent), agent);
    private final AiToolsState state = new AiToolsState(controller);

    @BeforeEach void observePlanningChanges() { publisher.subscribe(state::planningChanged); }
    private void addTask() { controller.addTask(new GeneralTask("Report", day.plusDays(1).atTime(17, 0), 2, 3, PriorityLevel.MEDIUM)); }
    private void addTime() { controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE)); }
    private Schedule generate() { addTask(); addTime(); return controller.generateSchedule(day, day); }
    private ReplanRecommendation recommendation() { return controller.requestReplanRecommendation(); }

    @Test void noScheduleIsSafeToQueryAndAnInvokedRequestGivesTheRequiredGuidance() {
        assertFalse(controller.hasCurrentSchedule());
        assertFalse(state.hasScheduleProperty().get());
        assertFalse(state.hasRecoveryProperty().get());
        assertFalse(state.canApplyRecommendationProperty().get());
        assertEquals("Generate a schedule first.", assertThrows(IllegalStateException.class, state::captureRequest).getMessage());
    }

    @Test void generationOutsideTheGuiImmediatelyUpdatesTheBoundExplainReadiness() {
        var disabled = state.hasScheduleProperty().not();
        assertTrue(disabled.get());
        Schedule schedule = generate();
        assertTrue(controller.hasCurrentSchedule()); assertFalse(disabled.get());
        assertEquals(schedule.getId(), state.captureRequest().scheduleId());
        assertFalse(state.hasRecoveryProperty().get());
        var blankQuestion = new SimpleStringProperty("");
        assertTrue(blankQuestion.get().isBlank());
        assertFalse(disabled.get(), "A blank question is validated on click, not used to hide schedule readiness");
    }

    @Test void openingAiToolsAfterGenerationUsesTheActualControllerSchedule() {
        Schedule schedule = generate();
        var newlyOpened = new AiToolsState(controller);
        assertTrue(newlyOpened.hasScheduleProperty().get());
        assertEquals(schedule.getId(), newlyOpened.captureRequest().scheduleId());
        assertEquals("The task uses the available study time.", controller.explainSchedule("Why here?"));
    }

    @Test void restoredScheduleIsReadyWithoutAnyGuiGenerationOrReplan() {
        Path file = directory.resolve("workspace.json");
        var first = PlanPilotApplicationFactory.create(file, clock).controller();
        first.addTask(new GeneralTask("Reading", day.plusDays(1).atTime(17, 0), 1, 3, PriorityLevel.MEDIUM));
        first.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(12, 0), AvailabilityType.AVAILABLE));
        Schedule original = first.generateSchedule(day, day);
        var restored = PlanPilotApplicationFactory.create(file, clock).controller();
        var opened = new AiToolsState(restored);
        assertTrue(opened.hasScheduleProperty().get());
        assertEquals(original.getId(), opened.captureRequest().scheduleId());
        assertFalse(opened.canApplyRecommendationProperty().get());
    }

    @Test void partialScheduleEnablesExplanationAndRecoveryEvenWithNoAllocatedBlocks() {
        addTask();
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
        assertTrue(failure.getPartialSchedule().getStudyBlocks().isEmpty());
        assertTrue(controller.hasCurrentSchedule()); assertTrue(state.hasScheduleProperty().get());
        assertTrue(state.hasRecoveryProperty().get());
        var request = state.captureRequest(); state.refresh();
        assertTrue(state.isCurrent(request), "Equivalent recovery objects must not look like changed planning data");
        assertEquals("The task uses the available study time.", controller.explainSchedule("Why did work not fit?"));
        addTime();
        assertTrue(state.hasScheduleProperty().get()); assertFalse(state.hasRecoveryProperty().get());
        controller.generateSchedule(day, day);
        assertTrue(state.hasScheduleProperty().get()); assertFalse(state.hasRecoveryProperty().get());
    }

    @Test void applyingRequiresAnActualRecommendationForTheSamePlanningState() {
        Schedule original = generate(); assertFalse(state.canApplyRecommendationProperty().get());
        var request = state.captureRequest(); var recommendation = recommendation();
        assertTrue(state.acceptRecommendation(request, recommendation));
        assertTrue(state.canApplyRecommendationProperty().get()); assertSame(recommendation, state.recommendation());
        assertSame(original, controller.getCurrentSchedule().orElseThrow(), "Accepting advice into UI state must not replan");
        state.refresh(); assertTrue(state.canApplyRecommendationProperty().get(), "Opening the page must preserve a valid recommendation");
        controller.updateProgress(original.getStudyBlocks().get(0), ProgressStatus.SKIPPED, 0);
        assertTrue(state.hasScheduleProperty().get()); assertFalse(state.canApplyRecommendationProperty().get());
        assertNull(state.recommendation()); assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
    }

    @Test void lateRecommendationsAreRejectedAfterRegenerationOrWorkloadChanges() {
        generate(); var request = state.captureRequest(); var recommendation = recommendation();
        controller.generateSchedule(day, day);
        assertFalse(state.acceptRecommendation(request, recommendation)); assertFalse(state.canApplyRecommendationProperty().get());
        request = state.captureRequest(); recommendation = recommendation();
        controller.addTask(new GeneralTask("New work", day.plusDays(1).atTime(18, 0), 1, 3, PriorityLevel.MEDIUM));
        assertFalse(state.acceptRecommendation(request, recommendation)); assertNull(state.recommendation());
        assertTrue(state.hasScheduleProperty().get());
    }

    @Test void refreshingDetectsAnAuthoritativeScheduleReplacementEvenWithoutALocalNotification() {
        generate(); var request = state.captureRequest();
        assertTrue(state.acceptRecommendation(request, recommendation()));
        var replacement = new Schedule("restored-other-plan", day, day);
        schedules.restoreWorkspace(new WorkspaceSnapshot(courses.getCourses(),
                availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()),
                schedules.getPreferences(), replacement, 0, ""));
        state.refresh();
        assertEquals(replacement.getId(), state.captureRequest().scheduleId());
        assertFalse(state.canApplyRecommendationProperty().get()); assertTrue(state.hasScheduleProperty().get());
    }

    @Test void outdatedPlansStillAllowExplanationAndAFreshRecommendationWithoutAutoReplanning() {
        Schedule original = generate(); controller.markScheduleOutdated();
        assertTrue(state.hasScheduleProperty().get()); assertFalse(state.hasRecoveryProperty().get());
        var request = state.captureRequest();
        assertTrue(state.acceptRecommendation(request, recommendation()));
        assertSame(original, controller.getCurrentSchedule().orElseThrow());
        state.clearRecommendation(); assertFalse(state.canApplyRecommendationProperty().get());
    }
    @Test void failedAiAdviceLeavesManualRecoveryAvailableAndChangesNothingUntilApplied() {
        addTask();
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
        var unavailableAgent = new PlanningAgent((system, prompt) -> {
            throw new com.planpilot.ai.LLMClientException("Gemini is temporarily unavailable.");
        });
        var aiController = new PlanPilotController(courses, tasks, availability, schedules, progress,
                new EffortEstimator(unavailableAgent), unavailableAgent);
        assertThrows(com.planpilot.ai.LLMClientException.class, () -> aiController.requestRecoverySuggestions(failure));
        state.refresh();
        assertTrue(state.hasRecoveryProperty().get()); assertSame(failure.getPartialSchedule(), controller.getCurrentSchedule().orElseThrow());
        assertEquals(2, controller.getTasks().get(0).getRemainingHours());
        assertTrue(controller.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).isEmpty());
        Schedule recovered = controller.applyRecovery(new RecoveryRequest(failure.getPartialSchedule().getId(),
                com.planpilot.ai.RecoveryAction.ADD_AVAILABILITY,
                new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE), null, null));
        assertNotEquals(failure.getPartialSchedule().getId(), recovered.getId());
        assertEquals(2, recovered.getStudyBlocks().stream().mapToDouble(StudyBlock::durationHours).sum());
        assertFalse(state.hasRecoveryProperty().get());
    }

    @Test void manualRecoveryDoesNotRequireAnyConfiguredAiProvider() {
        addTask(); var failure = assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
        var noAi = new PlanPilotController(courses, tasks, availability, schedules, progress);
        var noAiState = new AiToolsState(noAi);
        assertFalse(noAi.isAiAvailable()); assertTrue(noAiState.hasRecoveryProperty().get());
        Schedule kept = noAi.applyRecovery(new RecoveryRequest(failure.getPartialSchedule().getId(),
                com.planpilot.ai.RecoveryAction.KEEP_CURRENT_CONSTRAINTS, null, null, null));
        assertSame(failure.getPartialSchedule(), kept);
        assertEquals(2, noAi.getTasks().get(0).getRemainingHours());
        assertTrue(noAi.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).isEmpty());
    }

    @Test void malformedAiAdviceAlsoLeavesActualRecoveryActionsAvailable() {
        addTask(); var failure = assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
        var invalidAgent = new PlanningAgent((system, prompt) -> "I cannot provide a usable plan.");
        var ai = new PlanPilotController(courses, tasks, availability, schedules, progress, null, invalidAgent);
        assertThrows(com.planpilot.ai.LLMClientException.class, () -> ai.requestRecoverySuggestions(failure));
        assertTrue(state.hasRecoveryProperty().get());
        assertSame(failure.getPartialSchedule(), controller.getCurrentSchedule().orElseThrow());
        assertTrue(PlanPilotGUI.recoveryAdviceUnavailableMessage().contains("Review Recovery Actions"));
        assertTrue(PlanPilotGUI.recoveryAdviceUnavailableMessage().contains("Nothing has been changed"));
    }

    @Test void recoveryFailureGuidanceDistinguishesTimeoutAndUnavailableAdviceWithoutEchoingRawDetails() {
        assertTrue(PlanPilotGUI.recoveryAdviceUnavailableMessage(new com.planpilot.ai.LLMClientException(
                "Gemini took too long to respond.")).startsWith("Gemini took too long"));
        assertTrue(PlanPilotGUI.recoveryAdviceUnavailableMessage(new com.planpilot.ai.LLMClientException(
                "Gemini is temporarily unavailable.")).startsWith("Gemini is temporarily unavailable"));
        assertTrue(PlanPilotGUI.recoveryAdviceUnavailableMessage(new com.planpilot.ai.LLMClientException(
                "The LLM returned invalid recovery suggestions.")).startsWith("AI could not prepare usable recovery advice"));
        String message = PlanPilotGUI.recoveryAdviceUnavailableMessage(new RuntimeException("private raw response"));
        assertFalse(message.contains("private raw response")); assertTrue(message.contains("Review Recovery Actions"));
    }

}
