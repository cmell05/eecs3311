package com.planpilot.service;

import com.planpilot.domain.*;
import com.planpilot.strategy.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingEvidenceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T08:00:00Z"), ZoneOffset.UTC);
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private Task general(String id, LocalDateTime deadline, double hours) {
        return new GeneralTask(id, id, deadline, hours, 3, PriorityLevel.MEDIUM);
    }
    private AvailabilityBlock available(int start, int end) {
        return new AvailabilityBlock(day.atTime(start, 0), day.atTime(end, 0), AvailabilityType.AVAILABLE);
    }
    private Schedule plan(PriorityStrategy strategy, List<Task> tasks, List<AvailabilityBlock> available, StudyPeriod period) {
        return new SchedulePlanner(strategy, new ConflictDetector(), clock)
                .createSchedule(tasks, available, new UserPreferences(60, period, 0), day, day.plusDays(3));
    }

    @Test void equalUserImportanceCanProduceDifferentScoresDespiteAnEarlierDeadline() {
        var chemistry = new AcademicTask("chem", "Chem midterm", day.plusDays(3).atTime(17, 0), 8, 5,
                PriorityLevel.MEDIUM, new Course("CHEM1000", "Chemistry"), 40, AcademicTaskType.EXAM);
        var design = new AcademicTask("eecs", "Assignment 2", day.plusDays(2).atTime(22, 0), 1, 2,
                PriorityLevel.MEDIUM, new Course("EECS3311", "Design"), 10, AcademicTaskType.ASSIGNMENT);
        var strategy = new BalancedPriorityStrategy(clock);
        var schedule = plan(strategy, List.of(design, chemistry), List.of(available(9, 23)), StudyPeriod.ANY);
        var evidence = schedule.getDecisionEvidence().orElseThrow();
        assertEquals("2026-11-08T08:00", evidence.generatedAt());
        assertEquals("chem", evidence.tasks().get(0).taskId());
        var chem = evidence.tasks().get(0); var eecs = evidence.tasks().get(1);
        assertEquals(chem.importance(), eecs.importance());
        assertTrue(chem.priorityScore() > eecs.priorityScore());
        assertTrue(chem.scoreContributions().get("deadlineUrgency") < eecs.scoreContributions().get("deadlineUrgency"),
                "EECS has the earlier deadline; chemistry wins on other factors in this fixture");
        assertEquals(strategy.calculatePriority(chemistry), chem.priorityScore());
        assertEquals(chem.priorityScore(), chem.scoreContributions().values().stream().mapToDouble(Double::doubleValue).sum(), .000001);
        assertEquals("chem", schedule.getStudyBlocks().get(0).getTask().getId());
        assertEquals(1, chem.allocationRank()); assertEquals(2, eecs.allocationRank());
        assertThrows(UnsupportedOperationException.class, () -> chem.scoreContributions().clear());
        assertThrows(UnsupportedOperationException.class, () -> evidence.tasks().clear());
        assertThrows(IllegalStateException.class, () -> schedule.setDecisionEvidence(evidence));
    }

    @Test void eachScoreIsEvaluatedOnceAndTiesUseDeadlineThenIdentity() {
        AtomicInteger calls = new AtomicInteger();
        PriorityStrategy strategy = task -> { calls.incrementAndGet(); return 10; };
        var late = general("later", day.plusDays(2).atTime(17, 0), 1);
        var first = general("a", day.plusDays(1).atTime(17, 0), 1);
        var second = general("b", first.getDeadline(), 1);
        var evidence = plan(strategy, List.of(late, second, first), List.of(available(9, 17)), StudyPeriod.ANY)
                .getDecisionEvidence().orElseThrow();
        assertEquals(3, calls.get());
        assertEquals(List.of("a", "b", "later"), evidence.tasks().stream().map(SchedulingEvidence.TaskDecision::taskId).toList());
        assertTrue(evidence.tasks().get(1).orderingReason().contains("internal task ID"));
        assertTrue(evidence.tasks().get(2).orderingReason().contains("earlier deadline"));
        assertTrue(evidence.tasks().get(0).scoreContributions().isEmpty(), "Do not fabricate factors for a custom strategy");
    }

    @Test void allocationRankIsDifferentFromChronologicalOrderWhenPreferredSlotsCannotMeetADueTime() {
        var higher = general("high", day.plusDays(1).atTime(17, 0), 1);
        var lower = general("low", day.atTime(12, 0), 1);
        var schedule = plan(task -> task.getId().equals("high") ? 100 : 10,
                List.of(lower, higher), List.of(available(10, 11), available(17, 18)), StudyPeriod.EVENING);
        var evidence = schedule.getDecisionEvidence().orElseThrow();
        var high = evidence.tasks().get(0); var low = evidence.tasks().get(1);
        assertEquals(1, high.allocationRank()); assertEquals(2, low.allocationRank());
        assertEquals("2026-11-08T17:00", high.placements().get(0).start());
        assertEquals("2026-11-08T10:00", low.placements().get(0).start());
        assertEquals(1, high.placements().get(0).usableSlotOrder());
        assertEquals(2, low.placements().get(0).usableSlotOrder());
        assertEquals(StudyPeriod.EVENING, evidence.preferredStudyPeriod());
        assertTrue(evidence.orderingRule().contains("not necessarily chronological"));
    }

    @Test void recordsWhySessionsSplitAndPreservesCommitmentAndBreakConstraints() {
        var task = general("reading", day.atTime(15, 0), 2);
        var availability = List.of(available(9, 15),
                new AvailabilityBlock(day.atTime(10, 0), day.atTime(11, 0), AvailabilityType.WORK));
        var schedule = new SchedulePlanner(new BalancedPriorityStrategy(clock), new ConflictDetector(), clock)
                .createSchedule(List.of(task), availability, new UserPreferences(45, StudyPeriod.ANY, 10), day, day);
        var evidence = schedule.getDecisionEvidence().orElseThrow();
        assertEquals(45, evidence.maxSessionMinutes()); assertEquals(10, evidence.breakMinutes());
        assertEquals(AvailabilityType.WORK, evidence.availability().get(1).type());
        evidence.tasks().get(0).placements().forEach(placement -> {
            assertEquals(Math.min(placement.remainingTaskMinutesBefore(),
                    Math.min(placement.availableMinutesBeforeDeadline(), 45)), placement.allocatedMinutes());
            assertTrue(schedule.getStudyBlocks().stream().anyMatch(block -> block.getId().equals(placement.sessionId())));
        });
        assertTrue(new ConflictDetector().isScheduleFeasible(schedule, availability));
    }

    @Test void infeasibleSchedulesStillContainScoresAndUnscheduledReasons() {
        var task = general("report", day.atTime(17, 0), 2);
        var failure = assertThrows(ScheduleInfeasibleException.class, () ->
                plan(new BalancedPriorityStrategy(clock), List.of(task), List.of(available(9, 10)), StudyPeriod.ANY));
        var evidence = failure.getPartialSchedule().getDecisionEvidence().orElseThrow();
        assertEquals(1, evidence.tasks().get(0).unscheduledHours());
        assertTrue(evidence.tasks().get(0).unscheduledReason().contains("Not enough study time"));
        assertEquals(1, evidence.tasks().get(0).placements().size());
    }
}
