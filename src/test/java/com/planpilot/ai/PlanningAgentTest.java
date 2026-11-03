package com.planpilot.ai;

import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.Course;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningAgentTest {
    @Test
    void returnsValidatedSuggestionFromFakeClient() {
        FakeLLMClient fakeClient = new FakeLLMClient(
                "{\"estimatedHours\":4.25}");
        PlanningAgent agent = new PlanningAgent(fakeClient);
        Task task = academicTask();

        double suggestion = agent.suggestEffort(
                task, OptionalDouble.of(3.5), 2);

        assertEquals(4.25, suggestion, 0.0001);
        assertTrue(fakeClient.getSystemInstruction().contains(
                "exactly one JSON object"));
        assertTrue(fakeClient.getUserPrompt().contains("Task title: Project"));
        assertTrue(fakeClient.getUserPrompt().contains(
                "Average actual effort from 2 similar completed task(s): 3.50"));
        assertTrue(fakeClient.getUserPrompt().contains(
                "Assessment weight: 25.00%"));
    }

    @Test
    void rejectsMalformedOrUnsafeSuggestions() {
        Task task = academicTask();

        assertThrows(LLMClientException.class,
                () -> new PlanningAgent(new FakeLLMClient("not json"))
                        .suggestEffort(task, OptionalDouble.empty(), 0));
        assertThrows(LLMClientException.class,
                () -> new PlanningAgent(new FakeLLMClient("  "))
                        .suggestEffort(task, OptionalDouble.empty(), 0));
        assertThrows(LLMClientException.class,
                () -> new PlanningAgent(new FakeLLMClient(
                        "{\"estimatedHours\":0}"))
                        .suggestEffort(task, OptionalDouble.empty(), 0));
        assertThrows(LLMClientException.class,
                () -> new PlanningAgent(new FakeLLMClient(
                        "{\"estimatedHours\":1001}"))
                        .suggestEffort(task, OptionalDouble.empty(), 0));
    }

    @Test
    void rejectsInconsistentHistorySummaryBeforeCallingClient() {
        PlanningAgent agent = new PlanningAgent(
                new FakeLLMClient("{\"estimatedHours\":2}"));

        assertThrows(IllegalArgumentException.class,
                () -> agent.suggestEffort(
                        academicTask(), OptionalDouble.of(2), 0));
    }

    @Test
    void parsesNaturalLanguageUsingReferenceTimeAndFakeClient() {
        FakeLLMClient fakeClient = new FakeLLMClient("""
                {"title":"Interview preparation",
                "deadline":"2026-11-05T14:00:00","estimatedHours":2,
                "taskKind":"GENERAL"}
                """);
        Clock clock = Clock.fixed(
                Instant.parse("2026-11-03T15:30:00Z"), ZoneOffset.UTC);
        PlanningAgent agent = new PlanningAgent(
                fakeClient, new ObjectMapper(), clock);

        ParsedTaskData proposal = agent.parseTask(
                "Prepare for interview next Thursday");

        assertEquals("Interview preparation", proposal.title());
        assertEquals(2, proposal.estimatedHours(), 0.0001);
        assertTrue(fakeClient.getSystemInstruction().contains(
                "provided reference time"));
        assertTrue(fakeClient.getUserPrompt().contains(
                "\"referenceDateTime\":\"2026-11-03T15:30\""));
        assertTrue(fakeClient.getUserPrompt().contains(
                "Prepare for interview next Thursday"));
    }

    private Task academicTask() {
        return new AcademicTask(
                "T1", "Project", LocalDateTime.of(2026, 11, 10, 17, 0),
                5, 4, PriorityLevel.HIGH,
                new Course("C1", "EECS3311", "Software Design"),
                25, AcademicTaskType.PROJECT);
    }
}
