package com.planpilot.ai;

import java.util.Objects;

public record RecoverySuggestion(
        RecoveryAction action,
        String explanation) {

    public RecoverySuggestion {
        Objects.requireNonNull(action, "Recovery action cannot be null.");
        explanation = requireText(explanation);
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "Recovery explanation cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "Recovery explanation cannot be blank.");
        }
        return value.trim();
    }
}
