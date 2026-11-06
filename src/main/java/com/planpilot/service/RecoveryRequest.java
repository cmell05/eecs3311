package com.planpilot.service;

import com.planpilot.ai.RecoveryAction;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import java.time.LocalDate;
import java.util.Objects;

/** A user-reviewed recovery action tied to the specific partial schedule shown. */
public record RecoveryRequest(String scheduleId, RecoveryAction action,
                              AvailabilityBlock availability, LocalDate newEndDate, String taskId) {
    public RecoveryRequest {
        if (scheduleId == null || scheduleId.isBlank()) throw new IllegalArgumentException("A current partial schedule is required.");
        Objects.requireNonNull(action, "Select a recovery action.");
        switch (action) {
            case ADD_AVAILABILITY -> {
                Objects.requireNonNull(availability, "Enter available time.");
                if (availability.getType() != AvailabilityType.AVAILABLE)
                    throw new IllegalArgumentException("Recovery must add Available time.");
            }
            case EXTEND_PLANNING_WINDOW -> Objects.requireNonNull(newEndDate, "Choose a new end date.");
            case DEFER_LOWER_PRIORITY_TASK -> {
                if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("Select the task to defer.");
            }
            case KEEP_CURRENT_CONSTRAINTS -> { }
        }
    }
}
