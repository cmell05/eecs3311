package com.planpilot.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Objects;

public final class GeminiConfiguration {
    public static final String API_KEY_VARIABLE = "GEMINI_API_KEY";

    private final String apiKey;

    private GeminiConfiguration(String apiKey) {
        this.apiKey = apiKey;
    }

    public static GeminiConfiguration load() {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        return fromValues(
                System.getenv(API_KEY_VARIABLE),
                dotenv.get(API_KEY_VARIABLE));
    }

    static GeminiConfiguration fromValues(
            String environmentValue, String dotenvValue) {
        String value = firstNonBlank(environmentValue, dotenvValue);
        if (value == null || isPlaceholder(value)) {
            throw new IllegalStateException(
                    API_KEY_VARIABLE + " is not configured. Set it in the "
                            + "process environment or in a local .env file.");
        }
        return new GeminiConfiguration(value);
    }

    public String getApiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "GeminiConfiguration{apiKey=<redacted>}";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private static boolean isPlaceholder(String value) {
        String normalized = Objects.requireNonNull(value).toUpperCase();
        return normalized.contains("YOUR_LOCAL_GEMINI_API_KEY")
                || normalized.contains("PASTE_YOUR_GEMINI_API_KEY_HERE");
    }
}
