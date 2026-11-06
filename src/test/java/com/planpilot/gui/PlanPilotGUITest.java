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
    @Test
    void upcomingPlanIsSeparatedFromRetainedHistoryAndTaskColoursAreStable() {
        var day = java.time.LocalDate.of(2026, 11, 5);
        var task = new com.planpilot.domain.GeneralTask("task", "Design", day.plusDays(1).atStartOfDay(),
                4, 3, com.planpilot.domain.PriorityLevel.MEDIUM);
        var schedule = new com.planpilot.domain.Schedule("S", day, day);
        var completed = new com.planpilot.domain.StudyBlock("old", task, day.atTime(9, 0), day.atTime(10, 0));
        completed.markCompleted();
        var upcoming = new com.planpilot.domain.StudyBlock("new", task, day.atTime(10, 0), day.atTime(11, 0));
        schedule.addStudyBlock(completed); schedule.addStudyBlock(upcoming);
        assertEquals(List.of(upcoming), PlanPilotGUI.upcomingSessions(schedule));
        assertEquals(List.of(completed), PlanPilotGUI.historicalSessions(schedule));
        assertEquals(2, schedule.getStudyBlocks().size());
        var edited = new com.planpilot.domain.GeneralTask("task", "Renamed design", task.getDeadline(),
                4, 3, com.planpilot.domain.PriorityLevel.MEDIUM);
        assertEquals(PlanPilotGUI.taskColour(task), PlanPilotGUI.taskColour(edited));
        assertTrue(PlanPilotGUI.upcomingSessions(null).isEmpty());
    }

}
