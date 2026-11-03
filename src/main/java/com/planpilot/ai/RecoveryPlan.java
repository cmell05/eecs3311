package com.planpilot.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record RecoveryPlan(
        String summary,
        List<RecoverySuggestion> suggestions) {

    private static final int MAX_SUGGESTIONS = 4;

    public RecoveryPlan {
        summary = requireText(summary, "Recovery summary");
        Objects.requireNonNull(suggestions,
                "Recovery suggestions cannot be null.");
        suggestions = List.copyOf(suggestions);
        if (suggestions.isEmpty() || suggestions.size() > MAX_SUGGESTIONS) {
            throw new IllegalArgumentException(
                    "Recovery plan must contain between 1 and 4 suggestions.");
        }
        Set<RecoveryAction> actions = new HashSet<>();
        for (RecoverySuggestion suggestion : suggestions) {
            Objects.requireNonNull(suggestion,
                    "Recovery suggestions cannot contain null.");
            if (!actions.add(suggestion.action())) {
                throw new IllegalArgumentException(
                        "Recovery actions cannot be duplicated.");
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }
}
