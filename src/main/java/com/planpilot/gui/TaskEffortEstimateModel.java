package com.planpilot.gui;

import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.Task;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Objects;

/** Form state only. All estimation and history decisions remain behind the controller. */
final class TaskEffortEstimateModel {
    private final PlanPilotController controller;
    private final StringProperty estimatedHours = new SimpleStringProperty("");
    private final ObjectProperty<Double> suggestion = new SimpleObjectProperty<>();
    private final StringProperty message = new SimpleStringProperty("");
    private long revision;

    TaskEffortEstimateModel(PlanPilotController controller) {
        this.controller = Objects.requireNonNull(controller);
        estimatedHours.addListener((obs, oldValue, newValue) -> invalidate());
    }

    StringProperty estimatedHoursProperty() { return estimatedHours; }
    ReadOnlyObjectProperty<Double> suggestionProperty() { return suggestion; }
    ReadOnlyStringProperty messageProperty() { return message; }

    void invalidate() {
        revision++;
        suggestion.set(null);
        message.set("");
    }

    record Request(Task task, long revision) { }
    record Result(Double hours, boolean personalized, String error) { }

    Request begin(Task selectedTask, java.util.function.Supplier<Task> validatedDraft) {
        return begin(selectedTask == null ? validatedDraft.get() : selectedTask);
    }

    Request begin(Task task) {
        invalidate();
        message.set("Requesting an effort estimate…");
        return new Request(Objects.requireNonNull(task), revision);
    }

    /** Runs on the AI worker; never reads or changes JavaFX form properties. */
    Result fetch(Request request) {
        try {
            boolean personalized = controller.hasPersonalizedEffortHistory(request.task());
            double hours = controller.requestEffortSuggestion(request.task());
            if (!Double.isFinite(hours) || hours <= 0 || hours < request.task().getCompletedHours())
                return new Result(null, false, "The suggestion is below recorded work or invalid. Your current estimate was kept.");
            return new Result(hours, personalized, null);
        } catch (RuntimeException failure) {
            return new Result(null, false, "AI effort estimation is unavailable right now. Your current estimate was kept.");
        }
    }

    /** Runs on the UI thread; discard results for a form or task changed while awaiting the response. */
    boolean accept(Request request, Result result) {
        if (request.revision() != revision) return false;
        suggestion.set(result.hours());
        message.set(result.error() != null ? result.error() : result.personalized()
                ? "Based on similar completed tasks. Review before applying; your current estimate was kept."
                : "Not enough task history for a personalized estimate. General AI suggestion only; your current estimate was kept.");
        return true;
    }

    void useSuggestion() {
        Double hours = suggestion.get();
        if (hours == null) return;
        estimatedHours.set(Double.toString(hours));
        suggestion.set(null);
        message.set("Suggested estimate filled in. Choose Add Task or Update to save it.");
    }

    void keepCurrentEstimate() {
        invalidate();
        message.set("Current estimate kept.");
    }
}
