package com.planpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planpilot.config.PlanPilotApplicationFactory;
import com.planpilot.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleExplanationEvidenceTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private final Clock clock = Clock.fixed(day.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Test void explanationReceivesGenerationScoresAndImmutableFactsAfterTaskChangesAndRestart() throws Exception {
        Path file = directory.resolve("workspace.json");
        var controller = PlanPilotApplicationFactory.create(file, clock).controller();
        var course = controller.addCourse(new Course("EECS3311", "Design"));
        var task = controller.addTask(new AcademicTask("Original report", day.plusDays(1).atTime(17, 0),
                2, 3, PriorityLevel.MEDIUM, course, 20, AcademicTaskType.ASSIGNMENT));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE));
        var schedule = controller.generateSchedule(day, day);
        var evidence = schedule.getDecisionEvidence().orElseThrow();
        controller.updateTask(new AcademicTask(task.getId(), "Edited report", day.plusDays(2).atTime(17, 0),
                3, 5, PriorityLevel.HIGH, course, 40, AcademicTaskType.ASSIGNMENT));
        var restored = PlanPilotApplicationFactory.create(file, clock).controller();
        var restoredSchedule = restored.getCurrentSchedule().orElseThrow();
        assertEquals(evidence, restoredSchedule.getDecisionEvidence().orElseThrow());
        assertEquals(ScheduleStatus.OUTDATED, restoredSchedule.getStatus());
        byte[] before = Files.readAllBytes(file);
        var fake = new FakeLLMClient("The original score used MEDIUM importance; the task has changed since then.");
        new PlanningAgent(fake, json, clock).explainSchedule("Why this placement?", restoredSchedule,
                restored.getAvailability(day.atStartOfDay(), day.plusDays(1).atStartOfDay()),
                restored.getProgressHistory(restored.getTasks().get(0)));
        var context = json.readTree(fake.getUserPrompt()).path("schedule");
        assertTrue(context.path("decisionEvidenceAvailable").asBoolean());
        assertTrue(context.path("generationEvidenceMayBeOutdated").asBoolean());
        var generatedTask = context.path("generationDecisionEvidence").path("tasks").path(0);
        assertEquals("Original report", generatedTask.path("title").asText());
        assertEquals("MEDIUM", generatedTask.path("importance").asText());
        assertEquals(2, generatedTask.path("remainingHours").asDouble());
        assertEquals(evidence.tasks().get(0).priorityScore(), generatedTask.path("priorityScore").asDouble());
        assertEquals(6, generatedTask.path("scoreContributions").size());
        assertEquals("Edited report", context.path("studyBlocks").path(0).path("task").path("title").asText());
        assertTrue(fake.getSystemInstruction().contains("not the calculated priority score"));
        assertTrue(fake.getSystemInstruction().contains("Never claim the old scores are current priorities"));
        assertArrayEquals(before, Files.readAllBytes(file), "Asking for an explanation must be read-only");
    }

    @Test void explicitReplanningCreatesNewEvidenceAndKeepsHistorySeparate() {
        var controller = PlanPilotApplicationFactory.create(directory.resolve("replan.json"), clock).controller();
        var task = controller.addTask(new GeneralTask("Reading", day.plusDays(1).atTime(17, 0), 2, 3, PriorityLevel.MEDIUM));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE));
        var first = controller.generateSchedule(day, day);
        var original = first.getDecisionEvidence().orElseThrow();
        var block = first.getStudyBlocks().get(0);
        controller.updateProgress(block, ProgressStatus.PARTIAL, .5);
        var revised = controller.requestReplan(com.planpilot.service.ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        var updated = revised.getDecisionEvidence().orElseThrow();
        assertEquals(2, original.tasks().get(0).remainingHours());
        assertEquals(1.5, updated.tasks().get(0).remainingHours());
        assertEquals(.5, updated.tasks().get(0).completedHours());
        assertTrue(revised.getStudyBlocks().contains(block));
        assertTrue(updated.tasks().get(0).placements().stream().noneMatch(p -> p.sessionId().equals(block.getId())),
                "Retained history is not a fresh allocation in the revised evidence");
        assertEquals(task.getId(), updated.tasks().get(0).taskId());
    }

    @Test void olderSavedPlansRemainUsableButDoNotInventAnOriginalCalculation() throws Exception {
        Path file = directory.resolve("legacy-plan.json");
        var controller = PlanPilotApplicationFactory.create(file, clock).controller();
        controller.addTask(new GeneralTask("Reading", day.plusDays(1).atTime(17, 0), 1, 3, PriorityLevel.MEDIUM));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(12, 0), AvailabilityType.AVAILABLE));
        var original = controller.generateSchedule(day, day);
        ObjectNode saved = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) saved.path("workspace").path("schedule")).remove("decisionEvidence");
        json.writeValue(file.toFile(), saved);
        var restored = PlanPilotApplicationFactory.create(file, clock).controller();
        var schedule = restored.getCurrentSchedule().orElseThrow();
        assertEquals(original.getId(), schedule.getId()); assertTrue(schedule.getDecisionEvidence().isEmpty());
        var fake = new FakeLLMClient("The original priority calculation was not recorded.");
        new PlanningAgent(fake, json, clock).explainSchedule("Why first?", schedule, java.util.List.of());
        var context = json.readTree(fake.getUserPrompt()).path("schedule");
        assertFalse(context.path("decisionEvidenceAvailable").asBoolean());
        assertFalse(context.has("generationDecisionEvidence"));
        assertTrue(fake.getSystemInstruction().contains("do not invent a placement reason or recompute it"));
        assertEquals(original.getId(), restored.getCurrentSchedule().orElseThrow().getId());
    }
}
