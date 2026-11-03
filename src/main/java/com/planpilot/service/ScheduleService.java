package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.ScheduleStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class ScheduleService {
    private static final int EXTENSION_DAYS = 7;

    private final TaskService taskService;
    private final AvailabilityService availabilityService;
    private final SchedulePlanner schedulePlanner;
    private final UserPreferences preferences;
    private final PlanningEventPublisher eventPublisher;
    private Schedule currentSchedule;

    public ScheduleService(
            TaskService taskService,
            AvailabilityService availabilityService,
            SchedulePlanner schedulePlanner,
            UserPreferences preferences) {
        this(taskService, availabilityService, schedulePlanner, preferences,
                new PlanningEventPublisher());
    }

    public ScheduleService(
            TaskService taskService,
            AvailabilityService availabilityService,
            SchedulePlanner schedulePlanner,
            UserPreferences preferences,
            PlanningEventPublisher eventPublisher) {
        this.taskService = Objects.requireNonNull(
                taskService, "Task service cannot be null.");
        this.availabilityService = Objects.requireNonNull(
                availabilityService, "Availability service cannot be null.");
        this.schedulePlanner = Objects.requireNonNull(
                schedulePlanner, "Schedule planner cannot be null.");
        this.preferences = Objects.requireNonNull(
                preferences, "User preferences cannot be null.");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "Event publisher cannot be null.");
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
        try {
            currentSchedule = schedulePlanner.createSchedule(
                    taskService.getOpenTasks(), availability, preferences, start, end);
        } catch (ScheduleInfeasibleException exception) {
            currentSchedule = exception.getPartialSchedule();
            eventPublisher.notifyObservers();
            throw exception;
        }
        eventPublisher.notifyObservers();
        return currentSchedule;
    }

    public Schedule replan(ReplanOption option) {
        Objects.requireNonNull(option, "Replan option cannot be null.");
        requireCurrentSchedule();

        return switch (option) {
            case KEEP_CURRENT_SCHEDULE -> currentSchedule;
            case REPLAN_ENTIRE_WINDOW ->
                    generateSchedule(
                            currentSchedule.getStartDate(), currentSchedule.getEndDate());
            case RESCHEDULE_INCOMPLETE_WORK -> rescheduleIncompleteWork();
            case EXTEND_PLANNING_WINDOW -> generateSchedule(
                    currentSchedule.getStartDate(),
                    currentSchedule.getEndDate().plusDays(EXTENSION_DAYS));
        };
    }

    private Schedule rescheduleIncompleteWork() {
        LocalDate start = currentSchedule.getStartDate();
        LocalDate end = currentSchedule.getEndDate();
        List<StudyBlock> completedBlocks = currentSchedule.getStudyBlocks().stream()
                .filter(block -> block.getProgressStatus() == ProgressStatus.COMPLETED)
                .toList();
        LocalDateTime windowStart = start.atStartOfDay();
        LocalDateTime windowEnd = end.plusDays(1).atStartOfDay();
        List<AvailabilityBlock> availability = new ArrayList<>(
                availabilityService.getAvailability(windowStart, windowEnd));
        for (int index = 0; index < completedBlocks.size(); index++) {
            StudyBlock block = completedBlocks.get(index);
            availability.add(new AvailabilityBlock(
                    currentSchedule.getId() + "-completed-commitment-" + (index + 1),
                    block.getStart(), block.getEnd(), AvailabilityType.UNAVAILABLE));
        }

        try {
            Schedule planned = schedulePlanner.createSchedule(
                    taskService.getOpenTasks(), availability, preferences, start, end);
            currentSchedule = mergeCompletedBlocks(planned, completedBlocks);
        } catch (ScheduleInfeasibleException exception) {
            currentSchedule = mergeCompletedBlocks(
                    exception.getPartialSchedule(), completedBlocks);
            eventPublisher.notifyObservers();
            throw new ScheduleInfeasibleException(
                    currentSchedule, exception.getWorkloadDeficitHours());
        }
        eventPublisher.notifyObservers();
        return currentSchedule;
    }

    private Schedule mergeCompletedBlocks(
            Schedule planned, List<StudyBlock> completedBlocks) {
        Schedule merged = new Schedule(
                planned.getId(), planned.getStartDate(), planned.getEndDate());
        List<StudyBlock> blocks = new ArrayList<>(planned.getStudyBlocks());
        for (int index = 0; index < completedBlocks.size(); index++) {
            StudyBlock completed = completedBlocks.get(index);
            StudyBlock preserved = new StudyBlock(
                    planned.getId() + "-completed-" + (index + 1),
                    completed.getTask(), completed.getStart(), completed.getEnd());
            preserved.markCompleted();
            blocks.add(preserved);
        }
        blocks.stream()
                .sorted(Comparator.comparing(StudyBlock::getStart)
                        .thenComparing(StudyBlock::getId))
                .forEach(merged::addStudyBlock);
        return merged;
    }

    public UserPreferences getPreferences() {
        return new UserPreferences(
                preferences.getMaxSessionLength(),
                preferences.getPreferredStudyPeriod(),
                preferences.getBreakLength());
    }

    public void updatePreferences(UserPreferences updatedPreferences) {
        Objects.requireNonNull(
                updatedPreferences, "User preferences cannot be null.");
        preferences.setMaxSessionLength(
                updatedPreferences.getMaxSessionLength());
        preferences.setPreferredStudyPeriod(
                updatedPreferences.getPreferredStudyPeriod());
        preferences.setBreakLength(updatedPreferences.getBreakLength());
        if (currentSchedule != null) {
            currentSchedule.markOutdated();
        }
        eventPublisher.notifyObservers();
    }

    public void markScheduleOutdated() {
        requireCurrentSchedule();
        currentSchedule.markOutdated();
        eventPublisher.notifyObservers();
    }

    public void markScheduleOutdatedIfPresent() {
        if (currentSchedule != null
                && currentSchedule.getStatus() != ScheduleStatus.OUTDATED) {
            currentSchedule.markOutdated();
            eventPublisher.notifyObservers();
        }
    }

    private void requireCurrentSchedule() {
        if (currentSchedule == null) {
            throw new IllegalStateException("No schedule has been generated.");
        }
    }
}
