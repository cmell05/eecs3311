package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public class AvailabilityBlock {
    private final String id;
    private final LocalDateTime start;
    private final LocalDateTime end;
    private AvailabilityType type;

    public AvailabilityBlock(String id, LocalDateTime start, LocalDateTime end,
                             AvailabilityType type) {
        this.id = requireText(id);
        this.start = Objects.requireNonNull(start, "Start cannot be null.");
        this.end = Objects.requireNonNull(end, "End cannot be null.");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("End must be after start.");
        }
        this.type = Objects.requireNonNull(type, "Availability type cannot be null.");
    }

    public String getId() { return id; }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd() { return end; }
    public AvailabilityType getType() { return type; }

    public void setType(AvailabilityType type) {
        this.type = Objects.requireNonNull(type, "Availability type cannot be null.");
    }

    public boolean overlaps(AvailabilityBlock other) {
        Objects.requireNonNull(other, "Other availability block cannot be null.");
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public double durationHours() {
        return java.time.Duration.between(start, end).toMinutes() / 60.0;
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "Availability id cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Availability id cannot be blank.");
        }
        return value.trim();
    }
}
