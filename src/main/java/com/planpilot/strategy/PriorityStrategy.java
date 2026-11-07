package com.planpilot.strategy;

import com.planpilot.domain.Task;

public interface PriorityStrategy {
    double calculatePriority(Task task);

    default PriorityEvaluation evaluate(Task task, java.time.LocalDateTime referenceTime) {
        return new PriorityEvaluation(calculatePriority(task), java.util.Map.of(),
                "This strategy supplies a score without a factor breakdown.");
    }
}
