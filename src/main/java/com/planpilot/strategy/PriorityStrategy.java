package com.planpilot.strategy;

import com.planpilot.domain.Task;

public interface PriorityStrategy {
    double calculatePriority(Task task);
}
