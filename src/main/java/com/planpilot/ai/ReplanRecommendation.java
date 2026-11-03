package com.planpilot.ai;

import com.planpilot.service.ReplanOption;

import java.util.Objects;

public record ReplanRecommendation(
        String impactSummary,
        ReplanOption recommendedOption,
        String rationale) {

    public ReplanRecommendation {
        impactSummary = requireText(impactSummary, "Impact summary");
        Objects.requireNonNull(recommendedOption,
                "Recommended replanning option cannot be null.");
        rationale = requireText(rationale, "Replanning rationale");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }
}
