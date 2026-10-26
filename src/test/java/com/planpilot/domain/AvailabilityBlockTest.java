package com.planpilot.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityBlockTest {
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 24, 9, 0);

    @Test
    void calculatesDurationInHours() {
        AvailabilityBlock block = new AvailabilityBlock(
                "A1", start, start.plusMinutes(90), AvailabilityType.AVAILABLE);

        assertEquals(1.5, block.durationHours(), 0.0001);
    }

    @Test
    void detectsOverlapsButNotTouchingBoundaries() {
        AvailabilityBlock block = new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE);
        AvailabilityBlock overlapping = new AvailabilityBlock(
                "A2", start.plusHours(1), start.plusHours(3), AvailabilityType.CLASS);
        AvailabilityBlock touching = new AvailabilityBlock(
                "A3", start.plusHours(2), start.plusHours(3), AvailabilityType.WORK);

        assertTrue(block.overlaps(overlapping));
        assertFalse(block.overlaps(touching));
    }
}
