package com.planpilot.event;

@FunctionalInterface
public interface PlanningObserver {
    void onPlanningStateChanged();
}
