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
        Objects.requireNonNull(task, "Task cannot be null.");

        double urgencyScore = calculateUrgency(task);
        double effortScore = Math.min(20.0, task.getRemainingHours() * 2.0);
        double difficultyScore = task.getDifficulty() * 2.0;
        double importanceScore = importanceScore(task.getUserImportance());
        double progressScore = task.getEstimatedHours() == 0
                ? 0
                : 10.0 * task.getRemainingHours() / task.getEstimatedHours();
        double assessmentScore = task instanceof AcademicTask academicTask
                ? academicTask.getAssessmentWeight() * 0.2
                : 0;

        return urgencyScore
                + effortScore
                + difficultyScore
                + importanceScore
                + progressScore
                + assessmentScore;
    }

    private double calculateUrgency(Task task) {
        LocalDateTime now = LocalDateTime.now(clock);
        long minutesUntilDeadline = Duration.between(now, task.getDeadline()).toMinutes();
        if (minutesUntilDeadline <= 0) {
            return 40.0;
        }
        double daysUntilDeadline = minutesUntilDeadline / (24.0 * 60.0);
        return 40.0 / (1.0 + daysUntilDeadline);
    }

    private double importanceScore(PriorityLevel importance) {
        return switch (importance) {
            case LOW -> 0.0;
            case MEDIUM -> 10.0;
            case HIGH -> 20.0;
        };
    }
}
