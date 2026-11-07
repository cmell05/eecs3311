package com.planpilot.config;

import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.UserPreferences;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PlanPilotApplicationFactoryTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private PlanPilotApplicationContext freshContext() {
        return PlanPilotApplicationFactory.create(directory.resolve(java.util.UUID.randomUUID() + ".json"), java.time.Clock.systemDefaultZone());
    }
    @Test
    void createsSharedObserverWiringAndDefaultPreferences() {
        PlanPilotApplicationContext context =
                freshContext();
        int[] notifications = {0};
        context.eventPublisher().subscribe(() -> notifications[0]++);

        context.controller().addTask(new GeneralTask(
                "T1", "Factory test", LocalDateTime.of(2026, 11, 4, 17, 0),
                1, 2, PriorityLevel.MEDIUM));

        UserPreferences preferences = context.controller().getUserPreferences();
        assertEquals(1, notifications[0]);
        assertEquals(60, preferences.getMaxSessionLength());
        assertEquals(StudyPeriod.ANY, preferences.getPreferredStudyPeriod());
        assertEquals(10, preferences.getBreakLength());
    }

    @Test
    void createsIndependentApplicationContexts() {
        PlanPilotApplicationContext first =
                freshContext();
        PlanPilotApplicationContext second =
                freshContext();

        assertNotSame(first.controller(), second.controller());
        assertNotSame(first.eventPublisher(), second.eventPublisher());
    }
}
