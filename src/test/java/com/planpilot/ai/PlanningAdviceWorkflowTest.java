package com.planpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import com.planpilot.strategy.BalancedPriorityStrategy;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the controller path used by both interfaces; only the external LLM is substituted. */
class PlanningAdviceWorkflowTest {
    @Test void adviceReceivesCorrectionsAndRemainsReadOnlyUntilExplicitReplanOrRecovery() throws Exception {
        var day = LocalDate.of(2026, 11, 6);
        Clock clock = Clock.fixed(day.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var prompts = new ArrayList<String>();
        var responses = new ArrayDeque<>(List.of(
                """
                {"title":"Design report","deadline":"2026-11-07T17:00","estimatedHours":2,
                "difficulty":3,"importance":"HIGH","taskKind":"ACADEMIC",
                "courseCode":"EECS3311","assessmentWeight":20,"academicTaskType":"ASSIGNMENT"}
                """,
                "{\"estimatedHours\":2.5}",
                "Some recorded progress is tentative and requires confirmation.",
                """
                {"impactSummary":"Remaining effort changed.","recommendedOption":"RESCHEDULE_INCOMPLETE_WORK",
                "rationale":"Review the remaining allocations."}
                """,
                """
                {"summary":"More time is required.","suggestions":[{"action":"ADD_AVAILABILITY","explanation":"Add study time."}]}
                """));
        var agent = new PlanningAgent((system, prompt) -> {
            prompts.add(prompt);
            assertFalse(responses.isEmpty(), "Unexpected LLM request");
            return responses.remove();
        }, new ObjectMapper(), clock);
        var publisher = new PlanningEventPublisher();
        var courses = new CourseService();
        var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        var progress = new ProgressService(publisher, clock, null);
        var schedules = new ScheduleService(tasks, availability,
                new SchedulePlanner(new BalancedPriorityStrategy(clock), new ConflictDetector(), clock),
                new UserPreferences(60, StudyPeriod.EVENING, 10), publisher);
        var controller = new PlanPilotController(courses, tasks, availability, schedules, progress,
                new EffortEstimator(agent), agent);
        var course = controller.addCourse(new Course("EECS3311", "Software Design"));
        ParsedTaskData proposal = controller.parseNaturalLanguageTask("Design report due tomorrow, 2 hours, worth 20%");
        assertTrue(controller.getTasks().isEmpty(), "Parsing must not create a task");
        Task academic = controller.addTask(new AcademicTask(proposal.title(), proposal.deadline(), proposal.estimatedHours(),
                proposal.difficulty(), proposal.importance(), course, proposal.assessmentWeight(), proposal.academicTaskType()));
        controller.addTask(new GeneralTask("Reading", day.plusDays(1).atTime(18, 0), 1, 2, PriorityLevel.LOW));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(13, 0), AvailabilityType.AVAILABLE));
        controller.addAvailability(new AvailabilityBlock(day.atTime(13, 0), day.atTime(14, 0), AvailabilityType.WORK));
        Schedule original = controller.generateSchedule(day, day);
        var first = original.getStudyBlocks().stream().filter(b -> b.getTask().getId().equals(academic.getId())).findFirst().orElseThrow();
        controller.updateProgress(first, ProgressStatus.COMPLETED, first.durationHours());
        controller.updateProgress(first, ProgressStatus.PARTIAL, .5);
        controller.updateProgress(first, ProgressStatus.PARTIAL, .5);
        controller.inferProgress(new StudyBlock("elapsed", academic, day.atTime(6, 0), day.atTime(7, 0)));
        assertEquals(.5, controller.getConfirmedHours(academic));
        assertEquals(1, controller.getInferredHours(academic));
        assertEquals(.5, academic.getRemainingHours());
        assertEquals(2.5, controller.requestEffortSuggestion(academic));
        assertEquals(2, academic.getEstimatedHours(), "Advice must not replace the estimate");
        controller.explainSchedule("What have I actually confirmed?");
        var json = new ObjectMapper();
        var facts = json.readTree(prompts.get(2)).path("schedule").path("studyBlocks").path(0).path("task");
        assertEquals(.5, facts.path("confirmedCompletedHours").asDouble());
        assertEquals(1, facts.path("inferredCompletedHours").asDouble(), "Inference outside the original plan must be included");
        assertEquals(.5, facts.path("remainingHours").asDouble());
        var advice = controller.requestReplanRecommendation();
        assertSame(original, controller.getCurrentSchedule().orElseThrow());
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertEquals(3, original.getStudyBlocks().size(), "Advice must not regenerate sessions");
        var replanned = controller.requestReplan(advice.recommendedOption());
        assertNotSame(original, replanned);
        assertEquals(1.5, replanned.getStudyBlocks().stream().filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED)
                .mapToDouble(StudyBlock::durationHours).sum());
        assertTrue(replanned.getStudyBlocks().contains(first));
        assertTrue(new ConflictDetector().isScheduleFeasible(replanned,
                controller.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay())));
        controller.addTask(new GeneralTask("Large project", day.plusDays(1).atTime(17, 0), 8, 3, PriorityLevel.MEDIUM));
        var failure = assertThrows(ScheduleInfeasibleException.class, () -> controller.generateSchedule(day, day));
        var partial = failure.getPartialSchedule();
        var recovery = controller.requestRecoverySuggestions(failure);
        var recoveryContext = json.readTree(prompts.get(4));
        assertEquals(failure.getWorkloadDeficitHours(), recoveryContext.path("workloadDeficitHours").asDouble());
        assertSame(partial, controller.getCurrentSchedule().orElseThrow());
        assertEquals(RecoveryAction.ADD_AVAILABILITY, recovery.suggestions().get(0).action());
        controller.applyRecovery(new RecoveryRequest(partial.getId(), RecoveryAction.KEEP_CURRENT_CONSTRAINTS, null, null, null));
        assertSame(partial, controller.getCurrentSchedule().orElseThrow());
        assertEquals(.5, controller.getConfirmedHours(academic));
        assertTrue(responses.isEmpty());
    }
}
