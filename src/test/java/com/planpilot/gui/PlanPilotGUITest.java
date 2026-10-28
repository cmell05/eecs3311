package com.planpilot.gui;

import com.planpilot.event.PlanningObserver;
import javafx.application.Application;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPilotGUITest {
    @Test
    void guiIsJavaFxApplicationAndPlanningObserver() {
        assertTrue(Application.class.isAssignableFrom(PlanPilotGUI.class));
        assertTrue(PlanningObserver.class.isAssignableFrom(PlanPilotGUI.class));
    }
}
