package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityServiceTest {
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);

    @Test
    void addsAndQueriesOverlappingAvailability() {
        AvailabilityService service = new AvailabilityService();
        AvailabilityBlock morning = block(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE);
        AvailabilityBlock evening = block(
                "A2", start.plusHours(8), start.plusHours(10), AvailabilityType.AVAILABLE);
        service.addAvailability(morning);
        service.addAvailability(evening);

        assertEquals(List.of(morning),
                service.getAvailability(start.plusHours(1), start.plusHours(3)));
    }

    @Test
    void updatesAndDeletesAvailability() {
        AvailabilityService service = new AvailabilityService();
        service.addAvailability(block(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));
        AvailabilityBlock replacement = block(
                "A1", start.plusHours(3), start.plusHours(4), AvailabilityType.CLASS);

        service.updateAvailability(replacement);

        assertEquals(List.of(replacement),
                service.getAvailability(start, start.plusHours(5)));

        service.deleteAvailability("A1");

        assertTrue(service.getAvailability(start, start.plusHours(5)).isEmpty());
    }

    @Test
    void rejectsInvalidIntervalsAndMissingBlocks() {
        AvailabilityService service = new AvailabilityService();

        assertThrows(IllegalArgumentException.class,
                () -> block("A1", start, start, AvailabilityType.AVAILABLE));
        assertThrows(IllegalArgumentException.class,
                () -> service.getAvailability(start.plusHours(1), start));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateAvailability(block(
                        "missing", start, start.plusHours(1), AvailabilityType.AVAILABLE)));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteAvailability("missing"));
    }

    @Test
    void rejectsContradictoryOverlappingEntries() {
        AvailabilityService service = new AvailabilityService();
        service.addAvailability(block(
                "A1", start, start.plusHours(3), AvailabilityType.AVAILABLE));

        assertThrows(IllegalArgumentException.class,
                () -> service.addAvailability(block(
                        "A2", start.plusHours(1), start.plusHours(2), AvailabilityType.CLASS)));
    }

    private AvailabilityBlock block(
            String id, LocalDateTime blockStart, LocalDateTime blockEnd,
            AvailabilityType type) {
        return new AvailabilityBlock(id, blockStart, blockEnd, type);
    }
}
