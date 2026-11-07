package com.planpilot.gui;

import com.planpilot.ai.*;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.*;
import javafx.beans.property.SimpleStringProperty;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskEffortEstimateModelTest {
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);
    private final AtomicReference<Task> requestedTask = new AtomicReference<>();
    private final AtomicReference<String> prompt = new AtomicReference<>();

    private PlanPilotController controller(boolean withHistory, LLMClient client) {
        var courses = new CourseService(); var publisher = new PlanningEventPublisher();
        var tasks = new TaskService(courses, publisher); var availability = new AvailabilityService();
        Clock clock = Clock.fixed(start.plusDays(7).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var progress = new ProgressService(publisher, clock, null);
        var schedules = new ScheduleService(tasks, availability, new SchedulePlanner(task -> 1, new ConflictDetector(), clock),
                new UserPreferences(60, StudyPeriod.ANY, 10), publisher);
        var agent = new PlanningAgent((system, userPrompt) -> { prompt.set(userPrompt); return client.generateText(system, userPrompt); });
        var controller = new PlanPilotController(courses, tasks, availability, schedules, progress, new EffortEstimator(agent), agent) {
            @Override public double requestEffortSuggestion(Task task) {
                requestedTask.set(task);
                return super.requestEffortSuggestion(task);
            }
        };
        if (withHistory) {
            Task completed = controller.addTask(new GeneralTask("history", "Completed reading", start.plusDays(1), 2, 3, PriorityLevel.MEDIUM));
            controller.updateProgress(new StudyBlock("past", completed, start, start.plusHours(2)), ProgressStatus.COMPLETED, 2);
        }
        return controller;
    }

    private Task draft() { return new GeneralTask("Read documentation", start.plusDays(10), 4, 3, PriorityLevel.MEDIUM); }
    private PlanPilotController controller(boolean history) { return controller(history, (system, prompt) -> "{\"estimatedHours\":3.5}"); }
    private TaskEffortEstimateModel model(PlanPilotController controller) {
        var model = new TaskEffortEstimateModel(controller);
        model.estimatedHoursProperty().set("4");
        return model;
    }
    private TaskEffortEstimateModel.Result request(TaskEffortEstimateModel model, Task task) {
        var request = model.begin(task); var result = model.fetch(request);
        assertTrue(model.accept(request, result)); return result;
    }

    @Test void existingTaskUsesTheActualSavedObjectAndDoesNotOverwriteIt() {
        var controller = controller(true); Task saved = controller.addTask(draft()); var model = model(controller);
        model.estimatedHoursProperty().set("7"); // Unsaved form edit: the saved task remains the request target.
        var result = request(model, saved);
        assertSame(saved, requestedTask.get()); assertTrue(result.personalized());
        assertEquals(4, saved.getEstimatedHours()); assertEquals("7", model.estimatedHoursProperty().get());
        assertEquals(3.5, model.suggestionProperty().get());
        assertTrue(prompt.get().contains("Current user estimate: 4.00 hours"));
        assertEquals(2, controller.getTasks().size()); assertTrue(controller.getCurrentSchedule().isEmpty());
    }

    @Test void applyingSuggestionChangesTheBoundFormFieldButRequiresASeparateSave() {
        var controller = controller(true); Task saved = controller.addTask(draft()); var model = model(controller);
        var fieldText = new SimpleStringProperty(); fieldText.bindBidirectional(model.estimatedHoursProperty());
        fieldText.set("4"); request(model, saved); model.useSuggestion();
        assertEquals("3.5", fieldText.get()); assertEquals(4, saved.getEstimatedHours());
        assertNull(model.suggestionProperty().get()); assertTrue(model.messageProperty().get().contains("Update to save"));
        assertEquals(4, controller.getTasks().get(1).getEstimatedHours());
        controller.updateTask(new GeneralTask(saved.getId(), saved.getTitle(), saved.getDeadline(),
                Double.parseDouble(fieldText.get()), saved.getDifficulty(), saved.getUserImportance()));
        assertEquals(3.5, controller.getTasks().get(1).getEstimatedHours());
    }

    @Test void unsavedDraftIsOnlyAnEstimateRequestAndIsNeverAutomaticallyCreated() {
        var controller = controller(true); var model = model(controller); Task draft = draft();
        request(model, draft); model.useSuggestion();
        assertSame(draft, requestedTask.get()); assertEquals("3.5", model.estimatedHoursProperty().get());
        assertEquals(4, draft.getEstimatedHours()); assertEquals(1, controller.getTasks().size());
        assertTrue(controller.getTasks().stream().noneMatch(task -> task.getId().equals(draft.getId())));
    }

    @Test void keepingCurrentEstimateDiscardsTheSuggestionWithoutSaving() {
        var controller = controller(true); var model = model(controller); var saved = controller.addTask(draft());
        request(model, saved); model.keepCurrentEstimate();
        assertEquals("4", model.estimatedHoursProperty().get()); assertEquals(4, saved.getEstimatedHours());
        assertNull(model.suggestionProperty().get()); assertEquals("Current estimate kept.", model.messageProperty().get());
    }

    @Test void insufficientHistoryRetainsManualEstimateAndLabelsTheExistingGeneralFallback() {
        var controller = controller(false); var model = model(controller); var saved = controller.addTask(draft());
        var result = request(model, saved);
        assertFalse(result.personalized()); assertNull(result.error());
        assertTrue(model.messageProperty().get().contains("Not enough task history for a personalized estimate"));
        assertTrue(model.messageProperty().get().contains("General AI suggestion only"));
        assertEquals("4", model.estimatedHoursProperty().get()); assertEquals(4, saved.getEstimatedHours());
        assertEquals(3.5, model.suggestionProperty().get());
        assertTrue(prompt.get().contains("Similar completed-task history: unavailable"));
    }

    @Test void aiFailureKeepsManualAndSavedEstimatesAndReturnsAClearMessage() {
        var controller = controller(true, (system, prompt) -> { throw new LLMClientException("HTTP 503 service unavailable"); });
        var model = model(controller); var saved = controller.addTask(draft());
        var result = request(model, saved);
        assertNotNull(result.error()); assertTrue(result.error().contains("Your current estimate was kept"));
        assertFalse(result.error().contains("503")); assertNull(model.suggestionProperty().get());
        assertEquals("4", model.estimatedHoursProperty().get()); assertEquals(4, saved.getEstimatedHours());
    }

    @Test void malformedAndTooSmallSuggestionsCannotCorruptTaskProgress() {
        for (String response : new String[]{"not json", "{\"estimatedHours\":0}", "{\"estimatedHours\":0.25}"}) {
            var controller = controller(true, (system, prompt) -> response); var saved = controller.addTask(draft());
            controller.updateProgress(new StudyBlock("partial", saved, start.plusHours(3), start.plusHours(4)), ProgressStatus.PARTIAL, .5);
            var model = model(controller); var result = request(model, saved);
            assertNotNull(result.error()); assertNull(model.suggestionProperty().get());
            assertEquals(4, saved.getEstimatedHours()); assertEquals(.5, controller.getConfirmedHours(saved));
            assertEquals("4", model.estimatedHoursProperty().get());
        }
    }

    @Test void manualChangesAndTaskSwitchesDiscardLateOrStaleSuggestions() {
        var controller = controller(true); var model = model(controller); Task saved = controller.addTask(draft());
        var request = model.begin(saved); var response = model.fetch(request);
        model.estimatedHoursProperty().set("6");
        assertFalse(model.accept(request, response)); assertNull(model.suggestionProperty().get());
        assertEquals("6", model.estimatedHoursProperty().get());
        request = model.begin(saved); response = model.fetch(request);
        model.invalidate(); // The GUI invalidates on a selected-task or task-detail change.
        assertFalse(model.accept(request, response)); assertEquals(4, saved.getEstimatedHours());
    }

    @Test void changingTheFormAfterAnOfferPreventsApplyingAnOutdatedSuggestion() {
        var controller = controller(true); var model = model(controller); request(model, draft());
        model.estimatedHoursProperty().set("6"); model.useSuggestion();
        assertNull(model.suggestionProperty().get()); assertEquals("6", model.estimatedHoursProperty().get());
        assertEquals(1, controller.getTasks().size());
    }
    @Test void invalidDraftFieldsStopTheRequestBeforeAnyEstimateOrSave() {
        var controller = controller(true); var model = model(controller);
        assertThrows(IllegalArgumentException.class, () -> model.begin(null, () ->
                new GeneralTask("", start.plusDays(10), 4, 3, PriorityLevel.MEDIUM)));
        assertNull(requestedTask.get()); assertNull(model.suggestionProperty().get());
        assertEquals("4", model.estimatedHoursProperty().get()); assertEquals(1, controller.getTasks().size());
    }

    @Test void selectingASavedTaskDoesNotConstructATemporaryReplacement() {
        var controller = controller(true); var model = model(controller); var saved = controller.addTask(draft());
        var request = model.begin(saved, () -> { throw new AssertionError("Saved task must be used directly"); });
        assertSame(saved, request.task());
        model.accept(request, model.fetch(request)); assertSame(saved, requestedTask.get());
        assertEquals(4, saved.getEstimatedHours());
    }

    @Test void usingASuggestionInTheFormDoesNotInvalidateOrRegenerateASavedSchedule() {
        var controller = controller(true); var saved = controller.addTask(draft()); var model = model(controller);
        LocalDate day = start.plusDays(9).toLocalDate();
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE));
        Schedule schedule = controller.generateSchedule(day, day);
        request(model, saved); model.useSuggestion();
        assertSame(schedule, controller.getCurrentSchedule().orElseThrow());
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus());
        assertEquals(4, saved.getEstimatedHours()); assertEquals("3.5", model.estimatedHoursProperty().get());
    }

}
