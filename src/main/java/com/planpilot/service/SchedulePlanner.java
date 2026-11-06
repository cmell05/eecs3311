package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.strategy.PriorityStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
    private final Clock clock;

    public SchedulePlanner(PriorityStrategy priorityStrategy) {
        this(priorityStrategy, new ConflictDetector());
    }

    public SchedulePlanner(
            PriorityStrategy priorityStrategy, ConflictDetector conflictDetector) {
        this(priorityStrategy, conflictDetector, Clock.systemDefaultZone());
    }

    public SchedulePlanner(PriorityStrategy priorityStrategy, ConflictDetector conflictDetector, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null.");
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

        // Capture the cutoff once. Round up because effort and sessions use whole minutes.
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime earliestStart = now.truncatedTo(ChronoUnit.MINUTES);
        if (earliestStart.isBefore(now)) earliestStart = earliestStart.plusMinutes(1);
        List<TimeSlot> freeSlots = buildFreeTimeSlots(availability, startDate, endDate, earliestStart);
        List<AvailabilityBlock> usableAvailability = freeSlots.stream()
                .map(slot -> new AvailabilityBlock(slot.cursor, slot.end, AvailabilityType.AVAILABLE))
                .toList();

        String scheduleId = "schedule-" + java.util.UUID.randomUUID();
        Schedule schedule = new Schedule(scheduleId, startDate, endDate);
        List<Task> prioritizedTasks = tasks.stream()
                .<Task>map(Objects::requireNonNull)
                .filter(task -> task.getRemainingHours() > EPSILON)
                .filter(task -> task.getDeferredUntil() == null
                        || task.getDeferredUntil().isBefore(endDate.plusDays(1).atStartOfDay()))
                .sorted(Comparator
                        .comparingDouble(priorityStrategy::calculatePriority)
                        .reversed()
                        .thenComparing(Task::getDeadline)
                        .thenComparing(Task::getId))
                .toList();

        // Partition at release times so deferred work cannot consume earlier slots
        // that remain usable by other tasks.
        List<TimeSlot> releasedSlots = new ArrayList<>(freeSlots);
        for (Task task : prioritizedTasks) {
            if (task.getDeferredUntil() == null) continue;
            List<TimeSlot> partitioned = new ArrayList<>();
            for (TimeSlot slot : releasedSlots) {
                LocalDateTime release = task.getDeferredUntil();
                if (slot.cursor.isBefore(release) && release.isBefore(slot.end)) {
                    partitioned.add(new TimeSlot(slot.cursor, release));
                    partitioned.add(new TimeSlot(release, slot.end));
                } else partitioned.add(slot);
            }
            releasedSlots = partitioned;
        }
        List<TimeSlot> slots = prioritizeStudyPeriod(releasedSlots, preferences.getPreferredStudyPeriod());
        List<String> reasons = new ArrayList<>();
        long unscheduledMinutes = 0;
        int blockNumber = 1;
        for (Task task : prioritizedTasks) {
            long remainingMinutes = (long) Math.ceil(task.getRemainingHours() * 60.0);
            for (TimeSlot slot : slots) {
                if (task.getDeferredUntil() != null && slot.cursor.isBefore(task.getDeferredUntil())) continue;
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
            if (remainingMinutes > 0) {
                String reason;
                if (!task.getDeadline().isAfter(now)) {
                    reason = "The task's deadline has already passed; no future study time can be assigned.";
                } else if (!endDate.plusDays(1).atStartOfDay().isAfter(earliestStart)) {
                    reason = "The selected planning window has already ended. Choose current or future dates.";
                } else if (!task.getDeadline().isAfter(startDate.atStartOfDay())) {
                    reason = "The deadline is before the planning window.";
                } else if (buildFreeTimeSlots(availability, startDate, endDate, earliestStart).stream()
                        .noneMatch(slot -> slot.cursor.isBefore(task.getDeadline()))) {
                    reason = "No available study time exists before this task's deadline.";
                } else {
                    reason = "Not enough study time remains before the deadline, including session breaks.";
                }
                reasons.add(task.getTitle() + ": " + reason);
            }
            unscheduledMinutes += remainingMinutes;
        }

        if (!conflictDetector.isScheduleFeasible(schedule, availability)) {
            throw new IllegalStateException(
                    "Generated schedule contains a time conflict.");
        }

        double detectorDeficit = conflictDetector.calculateWorkloadDeficit(
                prioritizedTasks, usableAvailability);
        double allocationDeficit = unscheduledMinutes / 60.0;
        double workloadDeficit = Math.max(detectorDeficit, allocationDeficit);
        if (workloadDeficit > EPSILON) {
            throw new ScheduleInfeasibleException(schedule, workloadDeficit, String.join(" ", reasons));
        }
        return schedule;
    }

    private List<TimeSlot> buildFreeTimeSlots(
            List<AvailabilityBlock> availability,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime earliestStart) {
        LocalDateTime windowStart = laterOf(startDate.atStartOfDay(), earliestStart);
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

    private List<TimeSlot> prioritizeStudyPeriod(
            List<TimeSlot> slots, StudyPeriod preferredStudyPeriod) {
        if (preferredStudyPeriod == StudyPeriod.ANY) {
            return slots;
        }

        int preferredStartHour = switch (preferredStudyPeriod) {
            case MORNING -> 6;
            case AFTERNOON -> 12;
            case EVENING -> 17;
            case ANY -> throw new IllegalStateException("ANY is handled above.");
        };
        int preferredEndHour = switch (preferredStudyPeriod) {
            case MORNING -> 12;
            case AFTERNOON -> 17;
            case EVENING -> 24;
            case ANY -> throw new IllegalStateException("ANY is handled above.");
        };

        List<TimeSlot> preferredSlots = new ArrayList<>();
        List<TimeSlot> fallbackSlots = new ArrayList<>();
        for (TimeSlot slot : slots) {
            List<TimeSlot> remaining = new ArrayList<>();
            remaining.add(new TimeSlot(slot.cursor, slot.end));
            for (LocalDate day = slot.cursor.toLocalDate();
                 !day.isAfter(slot.end.toLocalDate());
                 day = day.plusDays(1)) {
                LocalDateTime preferredStart = day.atTime(preferredStartHour, 0);
                LocalDateTime preferredEnd = preferredEndHour == 24
                        ? day.plusDays(1).atStartOfDay()
                        : day.atTime(preferredEndHour, 0);
                LocalDateTime intersectionStart = laterOf(
                        slot.cursor, preferredStart);
                LocalDateTime intersectionEnd = earlierOf(slot.end, preferredEnd);
                if (intersectionStart.isBefore(intersectionEnd)) {
                    preferredSlots.add(new TimeSlot(
                            intersectionStart, intersectionEnd));
                    remaining = subtractInterval(
                            remaining, intersectionStart, intersectionEnd);
                }
            }
            fallbackSlots.addAll(remaining);
        }

        Comparator<TimeSlot> byStart = Comparator.comparing(slot -> slot.cursor);
        preferredSlots.sort(byStart);
        fallbackSlots.sort(byStart);
        List<TimeSlot> prioritized = new ArrayList<>(preferredSlots);
        prioritized.addAll(fallbackSlots);
        return prioritized;
    }

    private List<TimeSlot> subtractInterval(
            List<TimeSlot> slots, LocalDateTime start, LocalDateTime end) {
        List<TimeSlot> result = new ArrayList<>();
        for (TimeSlot slot : slots) {
            if (!overlaps(slot.cursor, slot.end, start, end)) {
                result.add(slot);
                continue;
            }
            if (slot.cursor.isBefore(start)) {
                result.add(new TimeSlot(slot.cursor, earlierOf(slot.end, start)));
            }
            if (end.isBefore(slot.end)) {
                result.add(new TimeSlot(laterOf(slot.cursor, end), slot.end));
            }
        }
        return result;
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
