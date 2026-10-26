package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.strategy.PriorityStrategy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class SchedulePlanner {
    private static final double EPSILON = 0.000001;

    private final PriorityStrategy priorityStrategy;
    private final ConflictDetector conflictDetector;

    public SchedulePlanner(PriorityStrategy priorityStrategy) {
        this(priorityStrategy, new ConflictDetector());
    }

    public SchedulePlanner(
            PriorityStrategy priorityStrategy, ConflictDetector conflictDetector) {
        this.priorityStrategy = Objects.requireNonNull(
                priorityStrategy, "Priority strategy cannot be null.");
        this.conflictDetector = Objects.requireNonNull(
                conflictDetector, "Conflict detector cannot be null.");
    }

    public Schedule createSchedule(
            List<? extends Task> tasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences) {
        Objects.requireNonNull(availability, "Availability cannot be null.");
        if (availability.isEmpty()) {
            throw new IllegalArgumentException(
                    "An explicit planning window is required when availability is empty.");
        }
        LocalDate startDate = availability.stream()
                .map(AvailabilityBlock::getStart)
                .map(LocalDateTime::toLocalDate)
                .min(LocalDate::compareTo)
                .orElseThrow();
        LocalDate endDate = availability.stream()
                .map(AvailabilityBlock::getEnd)
                .map(LocalDateTime::toLocalDate)
                .max(LocalDate::compareTo)
                .orElseThrow();
        return createSchedule(tasks, availability, preferences, startDate, endDate);
    }

    public Schedule createSchedule(
            List<? extends Task> tasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences,
            LocalDate startDate,
            LocalDate endDate) {
        validateInputs(tasks, availability, preferences, startDate, endDate);

        String scheduleId = "schedule-" + startDate + "-" + endDate;
        Schedule schedule = new Schedule(scheduleId, startDate, endDate);
        List<TimeSlot> slots = buildFreeTimeSlots(availability, startDate, endDate);
        List<Task> prioritizedTasks = tasks.stream()
                .map(Objects::requireNonNull)
                .filter(task -> task.getRemainingHours() > EPSILON)
                .sorted(Comparator
                        .comparingDouble(priorityStrategy::calculatePriority)
                        .reversed()
                        .thenComparing(Task::getDeadline)
                        .thenComparing(Task::getId))
                .toList();

        long unscheduledMinutes = 0;
        int blockNumber = 1;
        for (Task task : prioritizedTasks) {
            long remainingMinutes = (long) Math.ceil(task.getRemainingHours() * 60.0);
            for (TimeSlot slot : slots) {
                if (remainingMinutes == 0) {
                    break;
                }
                LocalDateTime latestEnd = earlierOf(slot.end, task.getDeadline());
                while (remainingMinutes > 0 && slot.cursor.isBefore(latestEnd)) {
                    long availableMinutes = Duration.between(
                            slot.cursor, latestEnd).toMinutes();
                    long sessionMinutes = Math.min(
                            Math.min(availableMinutes, preferences.getMaxSessionLength()),
                            remainingMinutes);
                    if (sessionMinutes <= 0) {
                        break;
                    }

                    LocalDateTime blockEnd = slot.cursor.plusMinutes(sessionMinutes);
                    schedule.addStudyBlock(new StudyBlock(
                            scheduleId + "-block-" + blockNumber,
                            task, slot.cursor, blockEnd));
                    blockNumber++;
                    remainingMinutes -= sessionMinutes;
                    slot.cursor = blockEnd.plusMinutes(preferences.getBreakLength());
                }
            }
            unscheduledMinutes += remainingMinutes;
        }

        if (!conflictDetector.isScheduleFeasible(schedule, availability)) {
            throw new IllegalStateException(
                    "Generated schedule contains a time conflict.");
        }

        double detectorDeficit = conflictDetector.calculateWorkloadDeficit(
                prioritizedTasks, availability);
        double allocationDeficit = unscheduledMinutes / 60.0;
        double workloadDeficit = Math.max(detectorDeficit, allocationDeficit);
        if (workloadDeficit > EPSILON) {
            throw new ScheduleInfeasibleException(schedule, workloadDeficit);
        }
        return schedule;
    }

    private List<TimeSlot> buildFreeTimeSlots(
            List<AvailabilityBlock> availability,
            LocalDate startDate,
            LocalDate endDate) {
        LocalDateTime windowStart = startDate.atStartOfDay();
        LocalDateTime windowEnd = endDate.plusDays(1).atStartOfDay();
        List<TimeSlot> slots = availability.stream()
                .filter(block -> block.getType() == AvailabilityType.AVAILABLE)
                .map(block -> new TimeSlot(
                        laterOf(block.getStart(), windowStart),
                        earlierOf(block.getEnd(), windowEnd)))
                .filter(slot -> slot.cursor.isBefore(slot.end))
                .toList();

        List<AvailabilityBlock> commitments = availability.stream()
                .filter(block -> block.getType() != AvailabilityType.AVAILABLE)
                .toList();
        List<TimeSlot> availableSegments = new ArrayList<>(slots);
        for (AvailabilityBlock commitment : commitments) {
            availableSegments = subtractCommitment(availableSegments, commitment);
        }
        return mergeSlots(availableSegments);
    }

    private List<TimeSlot> subtractCommitment(
            List<TimeSlot> slots, AvailabilityBlock commitment) {
        List<TimeSlot> result = new ArrayList<>();
        for (TimeSlot slot : slots) {
            if (!overlaps(slot.cursor, slot.end,
                    commitment.getStart(), commitment.getEnd())) {
                result.add(slot);
                continue;
            }
            if (slot.cursor.isBefore(commitment.getStart())) {
                result.add(new TimeSlot(slot.cursor,
                        earlierOf(slot.end, commitment.getStart())));
            }
            if (commitment.getEnd().isBefore(slot.end)) {
                result.add(new TimeSlot(
                        laterOf(slot.cursor, commitment.getEnd()), slot.end));
            }
        }
        return result;
    }

    private List<TimeSlot> mergeSlots(List<TimeSlot> slots) {
        List<TimeSlot> sortedSlots = slots.stream()
                .filter(slot -> slot.cursor.isBefore(slot.end))
                .sorted(Comparator.comparing(slot -> slot.cursor))
                .toList();
        List<TimeSlot> merged = new ArrayList<>();
        for (TimeSlot slot : sortedSlots) {
            if (merged.isEmpty()) {
                merged.add(new TimeSlot(slot.cursor, slot.end));
                continue;
            }
            TimeSlot previous = merged.get(merged.size() - 1);
            if (!slot.cursor.isAfter(previous.end)) {
                previous.end = laterOf(previous.end, slot.end);
            } else {
                merged.add(new TimeSlot(slot.cursor, slot.end));
            }
        }
        return merged;
    }

    private void validateInputs(
            List<? extends Task> tasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences,
            LocalDate startDate,
            LocalDate endDate) {
        Objects.requireNonNull(tasks, "Tasks cannot be null.");
        Objects.requireNonNull(availability, "Availability cannot be null.");
        Objects.requireNonNull(preferences, "User preferences cannot be null.");
        Objects.requireNonNull(startDate, "Start date cannot be null.");
        Objects.requireNonNull(endDate, "End date cannot be null.");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "Planning end date cannot be before start date.");
        }
        availability.forEach(block -> Objects.requireNonNull(
                block, "Availability block cannot be null."));
    }

    private boolean overlaps(
            LocalDateTime firstStart, LocalDateTime firstEnd,
            LocalDateTime secondStart, LocalDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private LocalDateTime earlierOf(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static final class TimeSlot {
        private LocalDateTime cursor;
        private LocalDateTime end;

        private TimeSlot(LocalDateTime start, LocalDateTime end) {
            this.cursor = start;
            this.end = end;
        }
    }
}
