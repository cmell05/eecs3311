package com.planpilot.strategy;

import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Task;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

public class BalancedPriorityStrategy implements PriorityStrategy {
    private final Clock clock;

    public BalancedPriorityStrategy() {
        this(Clock.systemDefaultZone());
    }

    public BalancedPriorityStrategy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null.");
    }

    @Override
    public double calculatePriority(Task task) {
        return evaluate(task, LocalDateTime.now(clock)).score();
    }

    @Override
    public PriorityEvaluation evaluate(Task task, LocalDateTime referenceTime) {
        Objects.requireNonNull(task, "Task cannot be null.");
        Objects.requireNonNull(referenceTime, "Priority reference time cannot be null.");
        var contributions = new java.util.LinkedHashMap<String, Double>();
        long minutes = Duration.between(referenceTime, task.getDeadline()).toMinutes();
        contributions.put("deadlineUrgency", minutes <= 0 ? 40.0 : 40.0 / (1.0 + minutes / 1440.0));
        contributions.put("remainingEffort", Math.min(20.0, task.getRemainingHours() * 2.0));
        contributions.put("difficulty", task.getDifficulty() * 2.0);
        contributions.put("userImportance", importanceScore(task.getUserImportance()));
        contributions.put("unfinishedProportion", task.getEstimatedHours() == 0 ? 0.0
                : 10.0 * task.getRemainingHours() / task.getEstimatedHours());
        contributions.put("assessmentWeight", task instanceof AcademicTask academic ? academic.getAssessmentWeight() * .2 : 0.0);
        // Preserve the formula's original addition order.
        double score = contributions.values().stream().reduce(0.0, Double::sum);
        return new PriorityEvaluation(score, contributions,
                "Sum of: deadline urgency = 40/(1 + days until deadline), capped at 40 for expired deadlines; "
                + "remaining effort = min(20, 2*remaining hours); difficulty = 2*difficulty; "
                + "user importance LOW/MEDIUM/HIGH = 0/10/20; unfinished proportion = 10*remaining/estimated; "
                + "academic assessment weight = 0.2*percentage (zero for GeneralTask).");
    }

    private double importanceScore(PriorityLevel importance) {
        return switch (importance) {
            case LOW -> 0.0;
            case MEDIUM -> 10.0;
            case HIGH -> 20.0;
        };
    }
}
