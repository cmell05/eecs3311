package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.UserPreferences;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class ScheduleService {
    private static final int EXTENSION_DAYS = 7;

    private final TaskService taskService;
    private final AvailabilityService availabilityService;
    private final SchedulePlanner schedulePlanner;
    private final UserPreferences preferences;
    private Schedule currentSchedule;

    public ScheduleService(
            TaskService taskService,
            AvailabilityService availabilityService,
            SchedulePlanner schedulePlanner,
            UserPreferences preferences) {
        this.taskService = Objects.requireNonNull(
                taskService, "Task service cannot be null.");
        this.availabilityService = Objects.requireNonNull(
                availabilityService, "Availability service cannot be null.");
        this.schedulePlanner = Objects.requireNonNull(
                schedulePlanner, "Schedule planner cannot be null.");
        this.preferences = Objects.requireNonNull(
                preferences, "User preferences cannot be null.");
    }

    public Schedule generateSchedule(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "Start date cannot be null.");
        Objects.requireNonNull(end, "End date cannot be null.");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(
                    "Schedule end date cannot be before start date.");
        }

        LocalDateTime windowStart = start.atStartOfDay();
        LocalDateTime windowEnd = end.plusDays(1).atStartOfDay();
        List<AvailabilityBlock> availability = availabilityService.getAvailability(
                windowStart, windowEnd);
        currentSchedule = schedulePlanner.createSchedule(
                taskService.getOpenTasks(), availability, preferences, start, end);
        return currentSchedule;
    }

    public Schedule replan(ReplanOption option) {
        Objects.requireNonNull(option, "Replan option cannot be null.");
        requireCurrentSchedule();

        return switch (option) {
            case KEEP_CURRENT_SCHEDULE -> currentSchedule;
            case REPLAN_ENTIRE_WINDOW, RESCHEDULE_INCOMPLETE_WORK ->
                    generateSchedule(
                            currentSchedule.getStartDate(), currentSchedule.getEndDate());
            case EXTEND_PLANNING_WINDOW -> generateSchedule(
                    currentSchedule.getStartDate(),
                    currentSchedule.getEndDate().plusDays(EXTENSION_DAYS));
        };
    }

    public void markScheduleOutdated() {
        requireCurrentSchedule();
        currentSchedule.markOutdated();
    }

    private void requireCurrentSchedule() {
        if (currentSchedule == null) {
            throw new IllegalStateException("No schedule has been generated.");
        }
    }
}
