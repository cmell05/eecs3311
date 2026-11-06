package com.planpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.ProgressService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanningProgressContextTest {
    private final LocalDate day = LocalDate.of(2026, 11, 6);
    private final Clock clock = Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final Task task = new GeneralTask("T", "Report", day.plusDays(1).atTime(17, 0), 10, 3, PriorityLevel.HIGH);
    private final Schedule schedule = new Schedule("S", day, day);
    private final ProgressService progress = new ProgressService(new PlanningEventPublisher(), clock, null);

    private StudyBlock block(String id, int hour, int minute) {
        LocalDateTime start = day.atTime(hour, minute);
        var block = new StudyBlock(id, task, start, start.plusHours(1));
        schedule.addStudyBlock(block);
        return block;
    }

    private void populate() {
        task.setCompletedHours(.5); // Confirmed baseline not represented by session records.
        progress.registerTasks(List.of(task));
        var completed = block("completed", 7, 0);
        var partial = block("partial", 8, 0);
        var skipped = block("skipped", 9, 0);
        var inferred = block("inferred", 10, 0);
        block("elapsed-unreported", 6, 0);
        block("ongoing", 11, 30);
        block("future", 14, 0);
        progress.recordProgress(completed, ProgressStatus.COMPLETED, 1);
        progress.recordProgress(partial, ProgressStatus.COMPLETED, 1);
        progress.recordProgress(partial, ProgressStatus.PARTIAL, .25);
        progress.inferProgress(skipped);
        progress.recordProgress(skipped, ProgressStatus.SKIPPED, 0);
        progress.inferProgress(inferred);
        schedule.markOutdated();
    }

    private PlanningAgent agent(FakeLLMClient client) {
        return new PlanningAgent(client, json, clock);
    }

    private JsonNode session(JsonNode context, String id) {
        for (JsonNode block : context.path("studyBlocks"))
            if (block.path("id").asText().equals(id)) return block;
        fail("Session absent: " + id);
        return null;
    }

    private void assertAccounting(JsonNode context) {
        assertEquals("2026-11-06T12:00", context.path("referenceDateTime").asText());
        assertEquals(1.5, context.path("upcomingPlannedHours").asDouble(), .00001);
        assertEquals(1, context.path("sessionsAwaitingConfirmation").asInt());
        assertEquals("REPORTED_HISTORY", session(context, "partial").path("sessionCategory").asText());
        assertEquals(.25, session(context, "partial").path("recordedHours").asDouble());
        assertEquals(.25, session(context, "partial").path("confirmedCompletedHours").asDouble());
        assertEquals(0, session(context, "skipped").path("inferredCompletedHours").asDouble());
        assertEquals(0, session(context, "skipped").path("remainingPlannedHours").asDouble());
        assertTrue(session(context, "inferred").path("isInferred").asBoolean());
        assertTrue(session(context, "inferred").path("requiresConfirmation").asBoolean());
        assertEquals(1, session(context, "inferred").path("inferredCompletedHours").asDouble());
        assertEquals(0, session(context, "inferred").path("confirmedCompletedHours").asDouble());
        assertEquals("AWAITING_INFERENCE", session(context, "elapsed-unreported").path("sessionCategory").asText());
        assertFalse(session(context, "elapsed-unreported").has("recordedHours"));
        assertEquals(.5, session(context, "ongoing").path("remainingPlannedHours").asDouble());
        assertEquals("IN_PROGRESS", session(context, "ongoing").path("sessionCategory").asText());
        assertEquals("UPCOMING", session(context, "future").path("sessionCategory").asText());
        JsonNode taskFacts = session(context, "future").path("task");
        assertEquals(2.75, taskFacts.path("completedHours").asDouble());
        assertEquals(1.75, taskFacts.path("confirmedCompletedHours").asDouble());
        assertEquals(1, taskFacts.path("inferredCompletedHours").asDouble());
        assertEquals(7.25, taskFacts.path("remainingHours").asDouble());
    }

    @Test void replanUsesLatestCorrectionsAndCountsOnlyRemainingUnreportedTime() throws Exception {
        populate();
        var client = new FakeLLMClient("""
                {"impactSummary":"Remaining workload exceeds allocations.","recommendedOption":"REPLAN_ENTIRE_WINDOW",
                "rationale":"Review the remaining work."}
                """);
        int historyCount = progress.getHistory().size();
        agent(client).suggestReplanningOption(schedule, List.of(task), List.of(),
                new UserPreferences(60, StudyPeriod.ANY, 0), progress.getHistory());
        JsonNode context = json.readTree(client.getUserPrompt());
        assertEquals(1.5, context.path("currentlyPlannedHours").asDouble());
        assertAccounting(context.path("currentSchedule"));
        assertTrue(client.getSystemInstruction().contains("Inferred hours are not user-confirmed"));
        assertEquals(historyCount, progress.getHistory().size());
        assertEquals(7, schedule.getStudyBlocks().size());
        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
        assertEquals(1.75, progress.getConfirmedHours(task));
    }

    @Test void explanationReceivesActualPartialAndInferredHours() throws Exception {
        populate();
        var client = new FakeLLMClient("Some progress still needs your confirmation.");
        agent(client).explainSchedule("How much work did I confirm?", schedule, List.of(), progress.getHistory());
        assertAccounting(json.readTree(client.getUserPrompt()).path("schedule"));
    }

    @Test void recoveryUsesTheSameAccountingWithoutChangingTheCalculatedDeficit() throws Exception {
        populate();
        var client = new FakeLLMClient("""
                {"summary":"Add study time.","suggestions":[{"action":"ADD_AVAILABILITY","explanation":"Review availability."}]}
                """);
        agent(client).suggestRecovery(1.25, schedule, List.of(task), List.of(), progress.getHistory());
        JsonNode context = json.readTree(client.getUserPrompt());
        assertEquals(1.25, context.path("workloadDeficitHours").asDouble());
        assertAccounting(context.path("partialSchedule"));
    }

    @Test void callersWithoutALedgerDoNotInventConfirmedOrPartialHours() throws Exception {
        populate();
        var client = new FakeLLMClient("Confirmation details are unavailable.");
        agent(client).explainSchedule("What did I complete?", schedule, List.of());
        JsonNode context = json.readTree(client.getUserPrompt()).path("schedule");
        assertFalse(context.path("progressDetailsAvailable").asBoolean());
        assertFalse(session(context, "partial").has("recordedHours"));
        assertFalse(session(context, "partial").path("task").has("confirmedCompletedHours"));
        assertTrue(session(context, "inferred").path("isInferred").asBoolean());
        assertEquals(1.5, context.path("upcomingPlannedHours").asDouble());
    }
}
