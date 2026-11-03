package com.planpilot.config;

import com.planpilot.controller.PlanPilotController;
import com.planpilot.event.PlanningEventPublisher;

import java.util.Objects;

public record PlanPilotApplicationContext(
        PlanPilotController controller,
        PlanningEventPublisher eventPublisher) {

    public PlanPilotApplicationContext {
        Objects.requireNonNull(controller, "Controller cannot be null.");
        Objects.requireNonNull(eventPublisher, "Event publisher cannot be null.");
    }
}
