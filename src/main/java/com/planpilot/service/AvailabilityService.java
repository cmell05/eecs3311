package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AvailabilityService {
    private final Map<String, AvailabilityBlock> availability = new LinkedHashMap<>();

    public void addAvailability(AvailabilityBlock block) {
        validateBlock(block);
        if (availability.containsKey(block.getId())) {
            throw new IllegalArgumentException(
                    "An availability block with id " + block.getId() + " already exists.");
        }
        rejectContradictions(block, null);
        availability.put(block.getId(), block);
    }

    public void updateAvailability(AvailabilityBlock block) {
        validateBlock(block);
        if (!availability.containsKey(block.getId())) {
            throw new IllegalArgumentException(
                    "No availability block exists with id " + block.getId() + ".");
        }
        rejectContradictions(block, block.getId());
        availability.put(block.getId(), block);
    }

    public void deleteAvailability(String blockId) {
        requireText(blockId, "Availability block id");
        if (availability.remove(blockId) == null) {
            throw new IllegalArgumentException(
                    "No availability block exists with id " + blockId + ".");
        }
    }

    public List<AvailabilityBlock> getAvailability(
            LocalDateTime start, LocalDateTime end) {
        validateInterval(start, end);
        return availability.values().stream()
                .filter(block -> block.getStart().isBefore(end)
                        && start.isBefore(block.getEnd()))
                .toList();
    }

    private void validateBlock(AvailabilityBlock block) {
        Objects.requireNonNull(block, "Availability block cannot be null.");
        requireText(block.getId(), "Availability block id");
        validateInterval(block.getStart(), block.getEnd());
        Objects.requireNonNull(block.getType(), "Availability type cannot be null.");
    }

    private void rejectContradictions(AvailabilityBlock candidate, String ignoredId) {
        boolean contradiction = availability.values().stream()
                .filter(existing -> !existing.getId().equals(ignoredId))
                .anyMatch(existing -> candidate.overlaps(existing)
                        && hasConflictingAvailability(candidate, existing));
        if (contradiction) {
            throw new IllegalArgumentException(
                    "Available study time cannot overlap an unavailable commitment.");
        }
    }

    private boolean hasConflictingAvailability(
            AvailabilityBlock first, AvailabilityBlock second) {
        boolean firstIsAvailable = first.getType() == AvailabilityType.AVAILABLE;
        boolean secondIsAvailable = second.getType() == AvailabilityType.AVAILABLE;
        return firstIsAvailable != secondIsAvailable;
    }

    private static void validateInterval(LocalDateTime start, LocalDateTime end) {
        Objects.requireNonNull(start, "Start cannot be null.");
        Objects.requireNonNull(end, "End cannot be null.");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("End must be after start.");
        }
    }

    private static void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }
}
