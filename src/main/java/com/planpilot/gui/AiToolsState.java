package com.planpilot.gui;

import com.planpilot.ai.ReplanRecommendation;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.Schedule;
import com.planpilot.service.ScheduleInfeasibleException;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

import java.util.Objects;

/** Controller-backed presentation state. No generation or replanning is performed here. */
final class AiToolsState {
    private final PlanPilotController controller;
    private final ReadOnlyBooleanWrapper hasSchedule = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper hasRecovery = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper canApplyRecommendation = new ReadOnlyBooleanWrapper();
    private String signature;
    private long revision;
    private ReplanRecommendation recommendation;

    AiToolsState(PlanPilotController controller) {
        this.controller = Objects.requireNonNull(controller);
        refresh();
    }

    ReadOnlyBooleanProperty hasScheduleProperty() { return hasSchedule.getReadOnlyProperty(); }
    ReadOnlyBooleanProperty hasRecoveryProperty() { return hasRecovery.getReadOnlyProperty(); }
    ReadOnlyBooleanProperty canApplyRecommendationProperty() { return canApplyRecommendation.getReadOnlyProperty(); }
    ReplanRecommendation recommendation() { return recommendation; }

    void refresh() {
        Schedule schedule = controller.getCurrentSchedule().orElse(null);
        ScheduleInfeasibleException failure = controller.getCurrentInfeasibility().orElse(null);
        String next = schedule == null ? "none" : schedule.getId() + ":" + schedule.getStatus()
                + (failure == null ? "" : ":" + failure.getWorkloadDeficitHours() + ":" + failure.getMessage());
        if (!Objects.equals(signature, next)) {
            revision++;
            clearRecommendation();
            signature = next;
        }
        hasSchedule.set(schedule != null);
        hasRecovery.set(failure != null);
    }

    void planningChanged() {
        revision++;
        clearRecommendation();
        refresh();
    }

    void clearRecommendation() {
        recommendation = null;
        canApplyRecommendation.set(false);
    }

    record Request(String scheduleId, long revision) { }

    Request captureRequest() {
        refresh();
        Schedule schedule = controller.getCurrentSchedule()
                .orElseThrow(() -> new IllegalStateException("Generate a schedule first."));
        return new Request(schedule.getId(), revision);
    }

    boolean isCurrent(Request request) {
        refresh();
        return request.revision() == revision && controller.getCurrentSchedule()
                .map(schedule -> schedule.getId().equals(request.scheduleId())).orElse(false);
    }

    boolean acceptRecommendation(Request request, ReplanRecommendation value) {
        if (!isCurrent(request)) return false;
        recommendation = Objects.requireNonNull(value);
        canApplyRecommendation.set(true);
        return true;
    }
}
