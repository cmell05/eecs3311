package com.planpilot.gui;

import com.planpilot.ai.RecoveryAction;
import com.planpilot.ai.RecoveryPlan;
import com.planpilot.ai.RecoverySuggestion;
import com.planpilot.ai.ReplanRecommendation;
import com.planpilot.event.PlanningObserver;
import com.planpilot.service.ReplanOption;
import javafx.application.Application;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPilotGUITest {
    @Test
    void guiIsJavaFxApplicationAndPlanningObserver() {
        assertTrue(Application.class.isAssignableFrom(PlanPilotGUI.class));
        assertTrue(PlanningObserver.class.isAssignableFrom(PlanPilotGUI.class));
    }

    @Test
    void recoveryFormattingMakesNonApplicationExplicit() {
        RecoveryPlan recovery = new RecoveryPlan(
                "Two hours do not fit.",
                List.of(new RecoverySuggestion(
                        RecoveryAction.EXTEND_PLANNING_WINDOW,
                        "Add another day.")));

        String text = PlanPilotGUI.formatRecoveryPlan(recovery);

        assertTrue(text.contains("EXTEND_PLANNING_WINDOW: Add another day."));
        assertTrue(text.contains("No action has been applied."));
    }

    @Test
    void replanFormattingRequiresReview() {
        ReplanRecommendation recommendation = new ReplanRecommendation(
                "The schedule is outdated.",
                ReplanOption.REPLAN_ENTIRE_WINDOW,
                "Include the new task.");

        String text = PlanPilotGUI.formatReplanRecommendation(recommendation);

        assertTrue(text.contains("Recommended: REPLAN_ENTIRE_WINDOW"));
        assertTrue(text.contains("Review before applying."));
        assertEquals(4, text.lines().count());
    }
}
