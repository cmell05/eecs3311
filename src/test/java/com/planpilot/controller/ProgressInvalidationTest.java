package com.planpilot.controller;

import com.planpilot.config.PlanPilotApplicationFactory;
import com.planpilot.domain.*;
import com.planpilot.service.ReplanOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class ProgressInvalidationTest {
    @TempDir Path directory;
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private final Clock clock = Clock.fixed(day.atTime(8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private PlanPilotController controller(Path file) {
        return PlanPilotApplicationFactory.create(file, clock).controller();
    }
    private Schedule plan(PlanPilotController controller) {
        controller.addTask(new GeneralTask("Report", day.atTime(18, 0), 2, 3, PriorityLevel.MEDIUM));
        controller.addAvailability(new AvailabilityBlock(day.atTime(9, 0), day.atTime(17, 0), AvailabilityType.AVAILABLE));
        return controller.generateSchedule(day, day);
    }

    @Test void skippingUnreportedWorkMarksTheSameScheduleOutdatedAndPersistsIt() {
        Path file = directory.resolve("skip.json"); var controller = controller(file);
        Schedule schedule = plan(controller); var block = schedule.getStudyBlocks().get(0);
        controller.updateProgress(block, ProgressStatus.SKIPPED, 0);
        assertSame(schedule, controller.getCurrentSchedule().orElseThrow());
        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
        assertEquals(2, block.getTask().getRemainingHours());
        assertEquals(2, schedule.getStudyBlocks().size());
        var restarted = controller(file);
        assertEquals(schedule.getId(), restarted.getCurrentSchedule().orElseThrow().getId());
        assertEquals(ScheduleStatus.OUTDATED, restarted.getCurrentSchedule().orElseThrow().getStatus());
        assertEquals(ProgressStatus.SKIPPED, restarted.getProgressSessions().get(0).getProgressStatus());
    }

    @Test void zeroPartialAndZeroHourStatusCorrectionsInvalidateWithoutChangingEffort() {
        var controller = controller(directory.resolve("partial.json"));
        Schedule original = plan(controller); var block = original.getStudyBlocks().get(0);
        controller.updateProgress(block, ProgressStatus.PARTIAL, 0);
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertEquals(0, block.getTask().getCompletedHours());
        Schedule replanned = controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        assertEquals(ScheduleStatus.CURRENT, replanned.getStatus());
        controller.updateProgress(block, ProgressStatus.SKIPPED, 0);
        assertSame(replanned, controller.getCurrentSchedule().orElseThrow());
        assertEquals(ScheduleStatus.OUTDATED, replanned.getStatus());
        assertEquals(2, block.getTask().getRemainingHours());
    }

    @Test void repeatingAnIdenticalReportedOutcomeDoesNotInvalidateAFreshPlan() {
        var controller = controller(directory.resolve("repeat.json"));
        var block = plan(controller).getStudyBlocks().get(0);
        controller.updateProgress(block, ProgressStatus.SKIPPED, 0);
        Schedule replanned = controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        int historySize = controller.getProgressHistory(block.getTask()).size();
        // A replacement object with the same identity must use the saved session's previous status.
        var replacement = new StudyBlock(block.getId(), block.getTask(), block.getStart(), block.getEnd());
        controller.updateProgress(replacement, ProgressStatus.SKIPPED, 0);
        assertEquals(ScheduleStatus.CURRENT, replanned.getStatus());
        assertEquals(historySize, controller.getProgressHistory(block.getTask()).size());
    }

    @Test void invalidProgressDoesNotInvalidateOrChangeTheSavedWorkspace() throws Exception {
        Path file = directory.resolve("invalid.json"); var controller = controller(file);
        Schedule schedule = plan(controller); var block = schedule.getStudyBlocks().get(0);
        byte[] before = java.nio.file.Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class, () -> controller.updateProgress(block, ProgressStatus.PARTIAL, 99));
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus());
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file));
    }
}
