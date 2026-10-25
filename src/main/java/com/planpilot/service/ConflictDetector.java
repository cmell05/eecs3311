package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ConflictDetector {
    public boolean hasConflict(StudyBlock first, StudyBlock second) {
        Objects.requireNonNull(first, "First study block cannot be null.");
        Objects.requireNonNull(second, "Second study block cannot be null.");
        return overlaps(first.getStart(), first.getEnd(), second.getStart(), second.getEnd());
    }

    public boolean hasConflict(
            StudyBlock block, List<AvailabilityBlock> availability) {
        Objects.requireNonNull(block, "Study block cannot be null.");
        Objects.requireNonNull(availability, "Availability cannot be null.");

        return availability.stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.getType() != AvailabilityType.AVAILABLE)
                .anyMatch(entry -> overlaps(
                        block.getStart(), block.getEnd(),
                        entry.getStart(), entry.getEnd()));
    }

    public double calculateWorkloadDeficit(
            List<? extends Task> tasks, List<AvailabilityBlock> availability) {
        Objects.requireNonNull(tasks, "Tasks cannot be null.");
        Objects.requireNonNull(availability, "Availability cannot be null.");

        double requiredHours = tasks.stream()
                .map(Objects::requireNonNull)
                .mapToDouble(Task::getRemainingHours)
                .sum();
        double availableHours = calculateUsableAvailabilityHours(availability);
        return Math.max(0.0, requiredHours - availableHours);
    }

    public boolean isScheduleFeasible(Schedule schedule) {
        Objects.requireNonNull(schedule, "Schedule cannot be null.");
        List<StudyBlock> blocks = schedule.getStudyBlocks();
        for (int firstIndex = 0; firstIndex < blocks.size(); firstIndex++) {
            for (int secondIndex = firstIndex + 1;
                 secondIndex < blocks.size(); secondIndex++) {
                if (hasConflict(blocks.get(firstIndex), blocks.get(secondIndex))) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isScheduleFeasible(
            Schedule schedule, List<AvailabilityBlock> availability) {
        if (!isScheduleFeasible(schedule)) {
            return false;
        }
        return schedule.getStudyBlocks().stream()
                .noneMatch(block -> hasConflict(block, availability));
    }

    private double calculateUsableAvailabilityHours(
            List<AvailabilityBlock> availability) {
        List<LocalDateTime> boundaries = new ArrayList<>();
        for (AvailabilityBlock block : availability) {
            Objects.requireNonNull(block, "Availability block cannot be null.");
            boundaries.add(block.getStart());
            boundaries.add(block.getEnd());
        }
        List<LocalDateTime> sortedBoundaries = boundaries.stream()
                .distinct()
                .sorted()
                .toList();

        long usableMinutes = 0;
        for (int index = 0; index + 1 < sortedBoundaries.size(); index++) {
            LocalDateTime segmentStart = sortedBoundaries.get(index);
            LocalDateTime segmentEnd = sortedBoundaries.get(index + 1);
            boolean availableSegment = availability.stream()
                    .anyMatch(block -> block.getType() == AvailabilityType.AVAILABLE
                            && covers(block, segmentStart, segmentEnd));
            boolean fixedSegment = availability.stream()
                    .anyMatch(block -> block.getType() != AvailabilityType.AVAILABLE
                            && covers(block, segmentStart, segmentEnd));
            if (availableSegment && !fixedSegment) {
                usableMinutes += Duration.between(segmentStart, segmentEnd).toMinutes();
            }
        }
        return usableMinutes / 60.0;
    }

    private boolean covers(
            AvailabilityBlock block, LocalDateTime start, LocalDateTime end) {
        return !block.getStart().isAfter(start) && !block.getEnd().isBefore(end);
    }

    private boolean overlaps(
            LocalDateTime firstStart, LocalDateTime firstEnd,
            LocalDateTime secondStart, LocalDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }
}
