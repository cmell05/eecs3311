package com.planpilot.domain;

import java.util.List;
import java.util.Map;

/** Immutable generation-time facts. ISO date/time strings keep the journal and AI representation identical. */
public record SchedulingEvidence(
        String generatedAt, String earliestStart, String priorityStrategy, String orderingRule,
        StudyPeriod preferredStudyPeriod, int maxSessionMinutes, int breakMinutes,
        List<AvailabilityFact> availability, List<SlotFact> slotsInAllocationOrder, List<TaskDecision> tasks) {
    public SchedulingEvidence {
        availability = List.copyOf(availability);
        slotsInAllocationOrder = List.copyOf(slotsInAllocationOrder);
        tasks = List.copyOf(tasks);
    }

    public record AvailabilityFact(String id, AvailabilityType type, String start, String end) { }
    public record SlotFact(String start, String end) { }

    public record TaskDecision(
            String taskId, String title, String courseCode, String deadline, String deferredUntil,
            double estimatedHours, double completedHours, double remainingHours, int difficulty,
            PriorityLevel importance, Double assessmentWeight,
            double priorityScore, Map<String, Double> scoreContributions, String scoringRule,
            int allocationRank, String orderingReason, List<Placement> placements,
            double unscheduledHours, String unscheduledReason) {
        public TaskDecision {
            scoreContributions = Map.copyOf(scoreContributions);
            placements = List.copyOf(placements);
        }
    }

    public record Placement(
            String sessionId, String start, String end, int usableSlotOrder,
            long remainingTaskMinutesBefore, long availableMinutesBeforeDeadline,
            long allocatedMinutes, String placementRule) { }
}
