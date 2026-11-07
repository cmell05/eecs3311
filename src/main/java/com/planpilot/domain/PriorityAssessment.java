package com.planpilot.domain;

import java.util.List;
import java.util.Map;

/** Current, read-only advice inputs; never a reconstruction of past scheduling decisions. */
public record PriorityAssessment(String assessedAt, String strategy, List<TaskPriority> tasks) {
    public PriorityAssessment { tasks = List.copyOf(tasks); }

    public record TaskPriority(
            String taskId, String title, String courseCode, String deadline, double hoursUntilDeadline,
            double remainingHours, int difficulty, PriorityLevel importance, Double assessmentWeight,
            String deferredUntil, double priorityScore, Map<String, Double> scoreContributions,
            String scoringRule, int priorityRank) {
        public TaskPriority { scoreContributions = Map.copyOf(scoreContributions); }
    }
}
