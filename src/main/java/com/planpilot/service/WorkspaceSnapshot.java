package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.Course;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.UserPreferences;

import java.util.List;

/** Workspace metadata stored alongside the existing task/session progress ledger. */
public record WorkspaceSnapshot(
        List<Course> courses,
        List<AvailabilityBlock> availability,
        UserPreferences preferences,
        Schedule schedule,
        double workloadDeficitHours,
        String infeasibilityReason) {
    public WorkspaceSnapshot {
        courses = List.copyOf(courses);
        availability = List.copyOf(availability);
        if (!Double.isFinite(workloadDeficitHours) || workloadDeficitHours < 0)
            throw new IllegalArgumentException("Workload deficit must be a nonnegative finite value.");
    }
}
