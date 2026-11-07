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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadableScheduleExplanationTest {
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private final Clock clock = Clock.fixed(day.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();

    private Schedule legacySchedule() {
        var schedule = new Schedule("legacy", day, day.plusDays(3));
        var chemistry = new AcademicTask("chem", "Study for chemistry midterm", day.plusDays(3).atTime(17, 0),
                8, 5, PriorityLevel.MEDIUM, new Course("chem-course", "CHEM1000", "Chemistry"), 40, AcademicTaskType.EXAM);
        var design = new AcademicTask("eecs", "Finish assignment 2", day.plusDays(2).atTime(22, 0),
                1, 2, PriorityLevel.MEDIUM, new Course("eecs-course", "EECS3311", "Design"), 10, AcademicTaskType.ASSIGNMENT);
        schedule.addStudyBlock(new StudyBlock("chem-session", chemistry, day.atTime(10, 0), day.atTime(11, 0)));
        schedule.addStudyBlock(new StudyBlock("eecs-session", design, day.atTime(17, 0), day.atTime(18, 0)));
        return schedule;
    }

    private PlanPilotController controller(Schedule schedule, LLMClient client) {
        var courses = new CourseService();
        var publisher = new PlanningEventPublisher();
        var tasks = new TaskService(courses, publisher);
        var availability = new AvailabilityService();
        availability.addAvailability(new AvailabilityBlock(day.atTime(10, 0), day.atTime(12, 0), AvailabilityType.AVAILABLE));
        availability.addAvailability(new AvailabilityBlock(day.atTime(17, 0), day.atTime(22, 0), AvailabilityType.AVAILABLE));
        for (StudyBlock block : schedule.getStudyBlocks()) {
            var task = (AcademicTask) block.getTask(); courses.addCourse(task.getCourse()); tasks.createTask(task);
        }
        var progress = new ProgressService(publisher, clock, null);
        progress.registerTasks(tasks.getTasks()); progress.trackSessions(schedule.getStudyBlocks());
        var preferences = new UserPreferences(60, StudyPeriod.EVENING, 10);
        var schedules = new ScheduleService(tasks, availability,
                new SchedulePlanner(new BalancedPriorityStrategy(clock), new ConflictDetector(), clock), preferences, publisher);
        schedules.restoreWorkspace(new WorkspaceSnapshot(courses.getCourses(),
                availability.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()), preferences, schedule, 0, ""));
        var agent = new PlanningAgent(client, json, clock);
        return new PlanPilotController(courses, tasks, availability, schedules, progress, new EffortEstimator(agent), agent);
    }

    @Test void olderPlanStillProvidesCurrentUrgencyAndFocusAdviceThroughTheController() throws Exception {
        Schedule schedule = legacySchedule();
        var prompts = new ArrayList<String>(); var instructions = new ArrayList<String>();
        var controller = controller(schedule, (system, prompt) -> {
            instructions.add(system); prompts.add(prompt);
            return "Chemistry starts first. EECS is due sooner, but chemistry currently has more remaining work and a heavier assessment.";
        });
        String explanation = controller.explainSchedule("Which is scheduled first and which should I focus on? Why?");
        var context = json.readTree(prompts.get(0));
        assertFalse(context.path("schedule").path("decisionEvidenceAvailable").asBoolean());
        var assessment = context.path("currentPriorityAssessment");
        assertEquals("2026-11-08T08:00", assessment.path("assessedAt").asText());
        var chem = assessment.path("tasks").path(0); var eecs = assessment.path("tasks").path(1);
        assertEquals("chem", chem.path("taskId").asText());
        assertTrue(chem.path("hoursUntilDeadline").asDouble() > eecs.path("hoursUntilDeadline").asDouble());
        assertTrue(chem.path("scoreContributions").path("deadlineUrgency").asDouble()
                < eecs.path("scoreContributions").path("deadlineUrgency").asDouble());
        assertTrue(chem.path("priorityScore").asDouble() > eecs.path("priorityScore").asDouble());
        assertEquals("MEDIUM", chem.path("importance").asText()); assertEquals("MEDIUM", eecs.path("importance").asText());
        assertTrue(instructions.get(0).contains("Never expose raw data payloads, boolean flags"));
        assertTrue(instructions.get(0).contains("Still answer what the available facts support"));
        assertTrue(explanation.contains("EECS is due sooner"));
        assertSame(schedule, controller.getCurrentSchedule().orElseThrow());
        assertTrue(schedule.getDecisionEvidence().isEmpty(), "Current advice must not fabricate an original decision record");
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus()); assertEquals(2, schedule.getStudyBlocks().size());
        assertTrue(controller.getProgressHistory(controller.getTasks().get(0)).isEmpty());
    }

    @Test void currentComparisonTracksEditsWithoutRewritingTheOldSchedule() throws Exception {
        Schedule schedule = legacySchedule(); var prompts = new ArrayList<String>();
        var controller = controller(schedule, (system, prompt) -> { prompts.add(prompt); return "Review EECS first based on your current workload."; });
        var design = (AcademicTask) controller.getTasks().stream().filter(task -> task.getId().equals("eecs")).findFirst().orElseThrow();
        controller.updateTask(new AcademicTask(design.getId(), design.getTitle(), design.getDeadline(), 15,
                5, PriorityLevel.HIGH, design.getCourse(), 70, design.getTaskType()));
        controller.explainSchedule("What should I focus on now?");
        var context = json.readTree(prompts.get(0));
        assertEquals("eecs", context.path("currentPriorityAssessment").path("tasks").path(0).path("taskId").asText());
        assertTrue(context.path("schedule").path("generationEvidenceMayBeOutdated").asBoolean());
        assertSame(schedule, controller.getCurrentSchedule().orElseThrow());
        assertEquals(day.atTime(10, 0), schedule.getStudyBlocks().get(0).getStart());
        assertTrue(schedule.getDecisionEvidence().isEmpty());
    }

    @Test void technicalDraftIsRewrittenOnceBeforeItReachesTheUser() {
        var replies = new ArrayDeque<>(List.of(
                "decisionEvidenceAvailable is false; priorityScore is unavailable.",
                "Chemistry starts first. I can compare your tasks now, but this older plan did not save why its times were chosen."));
        AtomicInteger calls = new AtomicInteger(); var prompts = new ArrayList<String>();
        var controller = controller(legacySchedule(), (system, prompt) -> { calls.incrementAndGet(); prompts.add(prompt); return replies.remove(); });
        String response = controller.explainSchedule("Why Chemistry first?");
        assertEquals(2, calls.get()); assertFalse(response.contains("decisionEvidenceAvailable"));
        assertTrue(response.startsWith("Chemistry starts first."));
        assertTrue(prompts.get(1).startsWith(prompts.get(0)), "The rewrite must use the same captured facts");
        assertTrue(prompts.get(1).contains("Remove implementation terminology"));
    }

    @Test void repeatedlyTechnicalResponsesAreNotDisplayedAndRetriesAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        var controller = controller(legacySchedule(), (system, prompt) -> {
            calls.incrementAndGet(); return "The backend decisionEvidenceAvailable flag is false.";
        });
        var failure = assertThrows(LLMClientException.class, () -> controller.explainSchedule("Why first?"));
        assertEquals(2, calls.get()); assertEquals("The explanation could not be presented clearly. Please try again.", failure.getMessage());
        assertFalse(failure.getMessage().contains("backend"));
    }

    @Test void normalTechnicalTaskNamesArePreservedWithoutAnUnnecessaryRewrite() {
        AtomicInteger calls = new AtomicInteger();
        var controller = controller(legacySchedule(), (system, prompt) -> {
            calls.incrementAndGet(); return "For your JSON parsing and Boolean algebra exercise, focus on the approaching deadline.";
        });
        assertTrue(controller.explainSchedule("How should I approach my JSON parsing exercise?").contains("JSON parsing and Boolean algebra exercise"));
        assertEquals(1, calls.get());
    }

    @Test void currentAssessmentExcludesFinishedWorkAndKeepsOverdueAndDeferralFactsExplicit() {
        var planner = new SchedulePlanner(new BalancedPriorityStrategy(clock), new ConflictDetector(), clock);
        Task done = new GeneralTask("done", "Done", day.plusDays(1).atStartOfDay(), 1, 3, PriorityLevel.MEDIUM); done.setCompletedHours(1);
        Task overdue = new GeneralTask("late", "Late", day.minusDays(1).atTime(17, 0), 2, 3, PriorityLevel.MEDIUM);
        Task deferred = new GeneralTask("deferred", "Deferred", day.plusDays(3).atTime(17, 0), 2, 3, PriorityLevel.MEDIUM);
        deferred.setDeferredUntil(day.plusDays(1).atStartOfDay());
        var assessment = planner.assessPriorities(List.of(done, overdue, deferred));
        assertEquals(2, assessment.tasks().size());
        assertTrue(assessment.tasks().stream().filter(task -> task.taskId().equals("late")).findFirst().orElseThrow().hoursUntilDeadline() < 0);
        assertEquals(deferred.getDeferredUntil().toString(), assessment.tasks().stream()
                .filter(task -> task.taskId().equals("deferred")).findFirst().orElseThrow().deferredUntil());
        assertEquals(0, overdue.getCompletedHours()); assertEquals(2, deferred.getRemainingHours());
    }
    @Test void rawPayloadsAndOtherInternalContextKeysAlsoRequireAReadableRewrite() {
        for (String draft : List.of("estimatedHours is 2 and remainingHours is 1.",
                "The sessionsAwaitingConfirmation field is zero.", "{\"deadline\":\"2026-11-10T22:00\"}")) {
            var replies = new ArrayDeque<>(List.of(draft, "EECS is due sooner. Compare the remaining work before choosing your next session."));
            AtomicInteger calls = new AtomicInteger();
            var controller = controller(legacySchedule(), (system, prompt) -> { calls.incrementAndGet(); return replies.remove(); });
            assertTrue(controller.explainSchedule("What should I focus on?").startsWith("EECS is due sooner."));
            assertEquals(2, calls.get(), draft);
        }
    }

}
