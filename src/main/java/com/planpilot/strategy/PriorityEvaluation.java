package com.planpilot.strategy;

import java.util.Map;
import java.util.Objects;

/** A score and the exact contributions used to calculate it. */
public record PriorityEvaluation(double score, Map<String, Double> contributions, String rule) {
    public PriorityEvaluation {
        if (!Double.isFinite(score)) throw new IllegalArgumentException("Priority score must be finite.");
        contributions = Map.copyOf(contributions);
        Objects.requireNonNull(rule, "Priority rule cannot be null.");
    }
}
